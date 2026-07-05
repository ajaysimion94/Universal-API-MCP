# Linux setup guide

> **Cross-platform setup — read this first.** See the table at the top of
> [`README.md`](README.md) for what varies by OS and what doesn't. The short version: the app itself
> (`mvn package` → `java -jar`) is fully cross-platform; only the **three native prerequisites**
> (PostgreSQL, pgvector, SearXNG) and **shell syntax** differ on Linux. Windows users see
> [`SETUP-WINDOWS.md`](SETUP-WINDOWS.md).

---

## Frequently used commands

```sh
# build + run everything (one JAR, serves SPA + API on :8080)
cd mcp-server && mvn package && java -jar target/mcp-server.jar

# dev mode — two terminals, open http://localhost:5173
cd mcp-server && mvn spring-boot:run -Dskip.frontend=true     # terminal 1: backend
cd mcp-server/webui && npm install && npm run dev             # terminal 2: frontend (HMR)

# fast loops
cd mcp-server && mvn test                                     # backend tests
cd mcp-server && mvn package -Dskip.frontend=true             # build JAR, skip SPA rebuild
cd mcp-server/webui && npm run typecheck                      # frontend type check

# start SearXNG separately (only if you want the web search toggle)
SEARXNG_SETTINGS_PATH=/opt/searxng-settings.yml /opt/searxng/venv/bin/searxng-run

# quick API check
curl http://127.0.0.1:8080/api/files
curl "http://127.0.0.1:8080/api/search?q=database+outage+failover"
```

Open **http://127.0.0.1:8080** (JAR) or **http://localhost:5173** (dev mode).

---

Linux-specific setup for the MCP Server. The main [`README.md`](README.md) covers macOS;
[`SETUP-WINDOWS.md`](SETUP-WINDOWS.md) covers Windows. This file covers everything that differs on
Linux. Steps that are identical across platforms (Java, Maven, Node, `mvn package`, `java -jar`, the
API, the SPA) are not repeated here — see the README and [`DEVELOPMENT.md`](DEVELOPMENT.md) for those.

## Prerequisites (Linux-specific)

Use your distro's package manager. Examples below cover the common ones — substitute as needed.

| Tool | Debian/Ubuntu | Fedora/RHEL | Arch |
| --- | --- | --- | --- |
| Java 21 | `apt install openjdk-21-jdk` | `dnf install java-21-openjdk-devel` | `pacman -S jdk21-openjdk` |
| Maven | `apt install maven` | `dnf install maven` | `pacman -S maven` |
| Node 20+ | [nodesource setup](https://github.com/nodesource/distributions) or `nvm` | `dnf install nodejs` (or nvm) | `pacman -S nodejs npm` |
| PostgreSQL 15 | see below | `dnf install postgresql15-server` | `pacman -S postgresql` |
| Python 3.10+ | `apt install python3 python3-venv python3-pip` | `dnf install python3 python3-pip` | `pacman -S python python-pip` |
| build tools | `apt install build-essential` | `dnf group install "C Development Tools and Libraries"` | `pacman -S base-devel` |
| Git | `apt install git` | `dnf install git` | `pacman -S git` |

Verify:

```sh
java -version
mvn -version
node -v
psql --version
python3 --version
make --version          # needed for pgvector build
```

> **Java note:** if `apt` installs an older default JDK, use `update-alternatives --config java` to
> pick 21, or install via [SDKMAN!](https://sdkman.io/) (`sdk install java 21.0.*-tem`) which works
> identically to macOS.
>
> **Node note:** distro packages are often outdated. [nvm](https://github.com/nvm-sh/nvm) is the
> most reliable cross-distro way to get Node 20+.

## One-time setup

### 1. PostgreSQL + pgvector

**Install PostgreSQL 15** (Debian/Ubuntu example — Fedora/RHEL uses `dnf` and `postgresql-setup`):

```sh
# Debian/Ubuntu — add the official PG repo for version 15 (distro default may be older)
sudo install -d /usr/share/postgresql/pgdg
sudo curl -o /usr/share/postgresql/pgdg/apt.postgresql.org.asc --fail \
  https://www.postgresql.org/media/keys/ACCC4CF8.asc
sudo sh -c 'echo "deb [signed-by=/usr/share/postgresql/pgdg/apt.postgresql.org.asc] \
  https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
  > /etc/apt/sources.list.d/pgdg.list'
sudo apt update
sudo apt install postgresql-15 postgresql-server-dev-15
```

Initialize and start (Fedora/RHEL — Debian/Ubuntu starts it automatically):

```sh
# Fedora/RHEL only
sudo /usr/pgsql-15/bin/postgresql-15-setup initdb
sudo systemctl enable --now postgresql-15
```

**Build + install pgvector from source** (Linux has `make` out of the box once `build-essential` /
`base-devel` is installed — this is the easiest platform for pgvector):

```sh
sudo apt install build-essential postgresql-server-dev-15   # Debian/Ubuntu
# or: sudo dnf install postgresql-devel                     # Fedora
# or: (base-devel already covers it on Arch)

git clone --depth 1 --branch v0.8.4 https://github.com/pgvector/pgvector.git /tmp/pgvector
cd /tmp/pgvector
make PG_CONFIG=/usr/lib/postgresql/15/bin/pg_config
sudo make PG_CONFIG=/usr/lib/postgresql/15/bin/pg_config install
```

> `pg_config` path varies by distro. Find it with `which pg_config` or
> `ls /usr/lib/postgresql/*/bin/pg_config`. On Fedora it's typically `/usr/pgsql-15/bin/pg_config`
> or just `pg_config` if the bin dir is on PATH.

**Create the DB + enable the extension:**

```sh
sudo -u postgres createdb mcpserver
sudo -u postgres psql -d mcpserver -c "CREATE EXTENSION vector;"
```

> **Auth note:** the default Debian/Ubuntu Postgres config uses `peer` auth for local sockets, so
> `psql -d mcpserver` works as your own user only if that user exists in Postgres. The app connects
> via TCP (`jdbc:postgresql://localhost:5432/mcpserver`), which uses `md5`/`scram` auth by default —
> you may need to set a password for the `postgres` user and pass it via `DB_USER`/`DB_PASSWORD`:
> ```sh
> sudo -u postgres psql -c "ALTER USER postgres PASSWORD 'yourpassword';"
> export DB_USER=postgres
> export DB_PASSWORD=yourpassword
> ```
> On Fedora/RHEL, also check `pg_hba.conf` allows TCP localhost connections with `scram-sha-256`.

Verify the extension:

```sh
psql -d mcpserver -c "SELECT extname, extversion FROM pg_extension WHERE extname='vector';"
```

Should print `vector | 0.8.4`. The `chunks` table (`vector(768)` HNSW index + `tsvector` lexical leg)
is created automatically from `schema.sql` on every server startup.

### 2. nomic-embed-text-v1.5 ONNX model (~131MB, gitignored)

Identical to macOS — `curl` and `mkdir -p` work the same on Linux:

```sh
mkdir -p mcp-server/models/nomic-embed-text-v1.5
cd mcp-server/models/nomic-embed-text-v1.5
curl -L -o model_quantized.onnx "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/resolve/main/onnx/model_quantized.onnx"
curl -L -o tokenizer.json "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/resolve/main/tokenizer.json"
```

ONNX Runtime auto-loads the correct native library for linux-x86_64 (shipped inside the Maven
`onnxruntime` artifact) — no extra setup. On **linux-aarch64** you may need to ensure glibc is recent
enough; if the bundled lib fails to load, install `libonnxruntime` from your package manager or
build ONNX Runtime from source for arm64.

### 3. SearXNG (optional — only for the Web search toggle)

SearXNG runs as a **native Python process**, separate from the MCP server JAR — it is **not**
bundled. The Web toggle won't return web results unless SearXNG is running on `127.0.0.1:8888`.
On Linux the setup is essentially identical to macOS (same `source venv/bin/activate`).

```sh
sudo apt install python3-venv python3-pip   # ensure venv support is present
git clone --depth 1 https://github.com/searxng/searxng.git /opt/searxng
cd /opt/searxng
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
pip install --no-build-isolation -e .
```

> **lxml build:** if `pip install` tries to build `lxml` from source and fails, install the pre-built
> wheel: `pip install lxml --only-binary :all:`. On Debian/Ubuntu you can also
> `apt install python3-lxml` but the venv won't pick it up — prefer the wheel.

Create the settings file (same content as macOS):

```yaml
# /opt/searxng-settings.yml
use_default_settings: true
general:
  instance_name: "MCP Local SearXNG"
search:
  formats: [html, json]      # JSON API must be enabled
  default_lang: "en"
server:
  secret_key: "change-me-to-a-random-string"
  bind_address: "127.0.0.1"
  port: 8888
  limiter: false             # disable rate limiting for local use
redis:
  url: false
```

Start it (separate terminal — it stays running alongside the MCP server):

```sh
SEARXNG_SETTINGS_PATH=/opt/searxng-settings.yml /opt/searxng/venv/bin/searxng-run
```

Verify: `curl "http://127.0.0.1:8888/search?q=test&format=json"` should return JSON with results.

> **Optional: run SearXNG as a systemd service** so it auto-starts on boot:
> ```ini
> # /etc/systemd/system/searxng.service
> [Unit]
> Description=SearXNG (local web search for MCP Server)
> After=network.target
>
> [Service]
> Type=simple
> User=youruser
> Environment=SEARXNG_SETTINGS_PATH=/opt/searxng-settings.yml
> ExecStart=/opt/searxng/venv/bin/searxng-run
> Restart=on-failure
>
> [Install]
> WantedBy=multi-user.target
> ```
> Then `sudo systemctl enable --now searxng`.

## Running the app

Identical to macOS — `mvn package` and `java -jar` are cross-platform:

```sh
cd mcp-server
mvn package
java -jar target/mcp-server.jar
```

Open **http://127.0.0.1:8080**.

## Development mode

```sh
# terminal 1 — backend
cd mcp-server
mvn spring-boot:run -Dskip.frontend=true

# terminal 2 — frontend
cd mcp-server/webui
npm install
npm run dev
```

Open **http://localhost:5173** (Vite proxies `/api` to `127.0.0.1:8080`).

## Linux-specific troubleshooting

- **`make: command not found`** — install build tools: `apt install build-essential` (Debian),
  `dnf group install "C Development Tools and Libraries"` (Fedora), `pacman -S base-devel` (Arch).
- **`pg_config: command not found`** — install the server dev package: `apt install postgresql-server-dev-15`
  (Debian/Ubuntu) or `dnf install postgresql-devel` (Fedora). Then locate it with
  `find /usr -name pg_config -type f` and pass the full path to `make PG_CONFIG=...`.
- **`CREATE EXTENSION vector` fails with "could not open extension control file"** — the `make install`
  didn't target the right `PG_CONFIG`. Recheck the path; the `.control` and `.sql` files must land in
  the `share/extension` dir of the *running* Postgres, not a different version's.
- **Server fails to start: "connection refused" to Postgres** — Postgres isn't running
  (`sudo systemctl status postgresql`) or auth config blocks TCP. On Debian/Ubuntu, TCP auth is
  `scram-sha-256` by default — set a password (`ALTER USER postgres PASSWORD '...'`) and pass it via
  `DB_USER`/`DB_PASSWORD` env vars. On Fedora/RHEL, check `pg_hba.conf` for a
  `host ... 127.0.0.1/32 scram-sha-256` line.
- **SELinux blocks the server from connecting to Postgres or SearXNG** (RHEL/Fedora) — check
  `sudo ausearch -m avc -ts recent` and either adjust policy or temporarily
  `sudo setenforce 0` to confirm SELinux is the cause. The permanent fix is
  `sudo setsebool -P httpd_can_network_connect 1` (or the equivalent for your setup).
- **Firewall blocks port 8080 or 8888** — on `ufw` (Ubuntu): `sudo ufw allow 8080/tcp` (only needed
  if you want remote access; the server binds to `127.0.0.1` by default so this is rarely needed).
  On `firewalld` (Fedora): `sudo firewall-cmd --add-port=8080/tcp --permanent && sudo firewall-cmd --reload`.
- **`pip install lxml` fails building from source** — use `pip install lxml --only-binary :all:`.
- **Port 8080 in use** — find and kill: `sudo lsof -i :8080` or `sudo ss -tlnp | grep 8080`.
- **Tests leave stale chunks** — `@SpringBootTest` writes to the real `mcpserver` DB. Clean with:
  ```sh
  psql -d mcpserver -c "TRUNCATE chunks;"
  ```
- **ONNX Runtime fails to load on linux-aarch64** — the Maven artifact ships x86_64 libs by default.
  On arm64 you may need to install `libonnxruntime` from source or your package manager and ensure
  it's on `java.library.path`.

## What's identical to macOS

These don't change between macOS and Linux — see the [main README](README.md):

- `mvn package`, `mvn test`, `mvn spring-boot:run -Dskip.frontend=true`
- `npm install`, `npm run dev`, `npm run build`, `npm run typecheck`
- `java -jar target/mcp-server.jar`
- The `application.yml` config keys (including `${user.dir}` which resolves correctly on Linux)
- `schema.sql` — runs identically in Postgres on both platforms
- The REST API and all `curl` test commands
- The ONNX embedding model files (platform-agnostic; ONNX Runtime loads the right native lib)
- The `chunks` table schema and pgvector queries
- SearXNG venv activation (`source venv/bin/activate`) and the `searxng-run` entry point
