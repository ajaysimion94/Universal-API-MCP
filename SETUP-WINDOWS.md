# Windows setup guide

> **Cross-platform setup — read this first.** See the table at the top of
> [`README.md`](README.md) for what varies by OS and what doesn't. The short version: the app itself
> (`mvn package` → `java -jar`) is fully cross-platform; only the **three native prerequisites**
> (PostgreSQL, pgvector, SearXNG) and **shell syntax** differ on Windows. Linux users see
> [`SETUP-LINUX.md`](SETUP-LINUX.md).

---

## Frequently used commands (PowerShell)

```powershell
# build + run everything (one JAR, serves SPA + API on :8080)
cd mcp-server; mvn package; java -jar target\mcp-server.jar

# dev mode — two terminals, open http://localhost:5173
cd mcp-server; mvn spring-boot:run -Dskip.frontend=true          # terminal 1: backend
cd mcp-server\webui; npm install; npm run dev                    # terminal 2: frontend (HMR)

# fast loops
cd mcp-server; mvn test                                          # backend tests
cd mcp-server; mvn package -Dskip.frontend=true                  # build JAR, skip SPA rebuild
cd mcp-server\webui; npm run typecheck                           # frontend type check

# start SearXNG separately (only if you want the web search toggle)
$env:SEARXNG_SETTINGS_PATH = "C:\dev\searxng-settings.yml"
C:\dev\searxng\venv\Scripts\python -m searx.webapp

# quick API check (use curl.exe, not the PowerShell curl alias)
curl.exe http://127.0.0.1:8080/api/files
curl.exe "http://127.0.0.1:8080/api/search?q=database+outage+failover"
```

Open **http://127.0.0.1:8080** (JAR) or **http://localhost:5173** (dev mode).

---

Windows-specific setup for the MCP Server. The main [`README.md`](README.md) covers macOS; this file
covers everything that differs on Windows. Steps that are identical on both platforms (Java, Maven,
Node, `mvn package`, `java -jar`, the API, the SPA) are not repeated here — see the README and
[`DEVELOPMENT.md`](DEVELOPMENT.md) for those.

> The single biggest Windows difference is **pgvector**: there's no `make` by default, so you either
> use a pre-built DLL or build it with Visual Studio. SearXNG's venv activation and start command also
> differ. Everything else is small path/shell adjustments.

## Prerequisites (Windows-specific)

| Tool | Version | How to install |
| --- | --- | --- |
| Java (JDK) | 21+ | [Adoptium Temurin 21](https://adoptium.net/) — run the `.msi`, check "Set JAVA_HOME" |
| Maven | 3.9+ | [maven.apache.org](https://maven.apache.org/download.cgi) → unzip, add `bin\` to PATH |
| Node.js | 20+ | [nodejs.org](https://nodejs.org/) LTS installer |
| PostgreSQL | 15+ | [postgresql.org/download/windows](https://www.postgresql.org/download/windows/) — the EDB installer; remember the superuser password you set |
| Python | 3.10+ | [python.org](https://www.python.org/downloads/windows/) — check "Add Python to PATH" (only needed for SearXNG) |
| Git | any | [git-scm.com](https://git-scm.com/download/win) (for cloning pgvector/SearXNG) |

Verify in **PowerShell**:

```powershell
java -version
mvn -version
node -v
psql --version
python --version
```

If `psql` isn't on PATH after the PostgreSQL install, add it:

```powershell
# default EDB install path — adjust if you chose a different one
$env:PATH += ";C:\Program Files\PostgreSQL\15\bin"
# to make it permanent:
[Environment]::SetEnvironmentVariable("PATH", $env:PATH + ";C:\Program Files\PostgreSQL\15\bin", "User")
```

## One-time setup

### 1. PostgreSQL + pgvector

After the PostgreSQL installer finishes, create the `mcpserver` database and enable the `vector`
extension. The extension **is not included** with the Windows installer — you must install pgvector
separately (two options below).

```powershell
# create the DB (will prompt for the superuser password you set during install)
createdb -U postgres mcpserver

# enable the extension (do this AFTER installing pgvector below)
psql -U postgres -d mcpserver -c "CREATE EXTENSION vector;"
```

**Option A — pre-built pgvector DLL (easier, if available)**

pgvector's GitHub releases page ships Windows DLLs for some PostgreSQL versions. This is the path of
least resistance:

1. Go to [github.com/pgvector/pgvector/releases](https://github.com/pgvector/pgvector/releases)
2. Find the release matching your Postgres version (e.g. `0.8.4`) and download `pgvector.zip` (or
   the Windows asset if listed separately)
3. Extract `vector.dll` into PostgreSQL's `lib` folder:
   ```powershell
   # default path — adjust if you installed elsewhere
   Copy-Item vector.dll "C:\Program Files\PostgreSQL\15\lib\vector.dll"
   ```
4. Copy `vector.control` and `vector--0.8.4.sql` into PostgreSQL's `extension` folder:
   ```powershell
   Copy-Item vector.control "C:\Program Files\PostgreSQL\15\share\extension\"
   Copy-Item "vector--0.8.4.sql" "C:\Program Files\PostgreSQL\15\share\extension\"
   ```
5. Restart PostgreSQL (Services → `postgresql-x64-15` → Restart), then run the `CREATE EXTENSION`
   command above.

**Option B — build pgvector from source with Visual Studio**

If a pre-built DLL isn't available for your version, build it with MSVC `nmake`:

1. Install [Visual Studio Build Tools](https://visualstudio.microsoft.com/visual-cpp-build-tools/)
   (the "Desktop development with C++" workload is enough — you don't need the full IDE).
2. Open the **"x64 Native Tools Command Prompt for VS"** (search the Start menu).
3. In that prompt:
   ```cmd
   git clone --depth 1 --branch v0.8.4 https://github.com/pgvector/pgvector.git C:\dev\pgvector
   cd C:\dev\pgvector
   set PG_CONFIG="C:\Program Files\PostgreSQL\15\bin\pg_config.exe"
   nmake /F Makefile.win
   nmake /F Makefile.win install
   ```
4. Restart PostgreSQL, then run `CREATE EXTENSION vector;` as above.

**Verify the extension is installed:**

```powershell
psql -U postgres -d mcpserver -c "SELECT extname, extversion FROM pg_extension WHERE extname='vector';"
```

Should print `vector | 0.8.4`. The `chunks` table (`vector(768)` HNSW index + `tsvector` lexical leg)
is created automatically from `schema.sql` on every server startup.

### 2. nomic-embed-text-v1.5 ONNX model (~131MB, gitignored)

Same download as macOS — only the path syntax differs. In PowerShell:

```powershell
New-Item -ItemType Directory -Force -Path mcp-server\models\nomic-embed-text-v1.5
cd mcp-server\models\nomic-embed-text-v1.5
curl.exe -L -o model_quantized.onnx "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/resolve/main/onnx/model_quantized.onnx"
curl.exe -L -o tokenizer.json "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/resolve/main/tokenizer.json"
```

> Use `curl.exe` (the real curl that ships with Windows 10+), not `curl` — PowerShell aliases `curl`
> to `Invoke-WebRequest` by default, which doesn't handle the `-L -o` flags the same way.

ONNX Runtime auto-loads the correct native library for Windows (it ships Windows x64 binaries inside
the Maven `onnxruntime` artifact), so no extra setup is needed for inference.

### 3. SearXNG (optional — only for the Web search toggle)

SearXNG runs as a **native Python process**, separate from the MCP server JAR — it is **not** bundled.
The Web toggle won't return web results unless SearXNG is running on `127.0.0.1:8888`.

```powershell
git clone --depth 1 https://github.com/searxng/searxng.git C:\dev\searxng
cd C:\dev\searxng
python -m venv venv
venv\Scripts\activate
pip install -r requirements.txt
pip install --no-build-isolation -e .
```

> **lxml on Windows:** if `pip install -r requirements.txt` fails on `lxml`, install the pre-built
> wheel: `pip install lxml --only-binary :all:`. SearXNG's other C-extension deps usually have wheels
> available, so a full Visual Studio install is typically not needed for SearXNG itself.

Create the settings file (same content as macOS, Windows path):

```yaml
# C:\dev\searxng-settings.yml
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

Start it (separate PowerShell window — it stays running alongside the MCP server):

```powershell
$env:SEARXNG_SETTINGS_PATH = "C:\dev\searxng-settings.yml"
C:\dev\searxng\venv\Scripts\python -m searx.webapp
```

> The `searxng-run` entry point used on macOS is a **bash script** and won't work on Windows. Use
> `python -m searx.webapp` instead.

Verify: `curl.exe "http://127.0.0.1:8888/search?q=test&format=json"` should return JSON.

## Running the app

Identical to macOS — `mvn package` and `java -jar` are cross-platform:

```powershell
cd mcp-server
mvn package
java -jar target\mcp-server.jar
```

Open **http://127.0.0.1:8080**.

## Development mode

```powershell
# terminal 1 — backend
cd mcp-server
mvn spring-boot:run -Dskip.frontend=true

# terminal 2 — frontend
cd mcp-server\webui
npm install
npm run dev
```

Open **http://localhost:5173** (Vite proxies `/api` to `127.0.0.1:8080`).

## Windows-specific troubleshooting

- **`nmake` not found** — you're not in the "x64 Native Tools Command Prompt for VS". Open it from the
  Start menu (search "x64 Native Tools"), not a regular PowerShell. `nmake` is part of Visual Studio
  Build Tools, not the base Windows install.
- **`CREATE EXTENSION vector` fails with "could not open extension control file"** — the `vector.dll`
  / `vector.control` / `vector--*.sql` files aren't in PostgreSQL's `lib` and `share\extension`
  folders. Recheck the copy paths (Option A above), and restart PostgreSQL after copying.
- **`curl` behaves oddly in PowerShell** — use `curl.exe` (the real curl), not the `curl` alias which
  points to `Invoke-WebRequest`. Or run the curl commands in `cmd.exe` instead of PowerShell.
- **`source venv/bin/activate` doesn't work** — that's bash syntax. On Windows use
  `venv\Scripts\activate` (PowerShell) or `venv\Scripts\activate.bat` (cmd). If PowerShell blocks the
  script, run `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` once.
- **`searxng-run` not found** — that's a bash entry-point script. On Windows, run
  `venv\Scripts\python -m searx.webapp` instead.
- **Server fails to start: "connection refused" to Postgres** — either Postgres isn't running
  (check Services for `postgresql-x64-15`), or the credentials in `application.yml` don't match what
  you set during install. Override with env vars:
  ```powershell
  $env:DB_USER = "postgres"
  $env:DB_PASSWORD = "your-install-password"
  java -jar target\mcp-server.jar
  ```
- **`pip install lxml` fails on Windows** — use `pip install lxml --only-binary :all:` to get the
  pre-built wheel instead of building from source.
- **Port 8080 in use** — run with `--server.port=8081`, or find and kill the process:
  ```powershell
  Get-NetTCPConnection -LocalPort 8080 | Select-Object OwningProcess
  Stop-Process -Id <pid>
  ```
- **Tests leave stale chunks** — `@SpringBootTest` writes to the real `mcpserver` DB. Clean with:
  ```powershell
  psql -U postgres -d mcpserver -c "TRUNCATE chunks;"
  ```

## What's identical to macOS

These don't change between platforms — see the [main README](README.md):

- `mvn package`, `mvn test`, `mvn spring-boot:run -Dskip.frontend=true`
- `npm install`, `npm run dev`, `npm run build`, `npm run typecheck`
- `java -jar target/mcp-server.jar` (use `\` instead of `/` on Windows, but both work in PowerShell)
- The `application.yml` config keys (including `${user.dir}` which resolves correctly on Windows)
- `schema.sql` — runs identically in Postgres on both platforms
- The REST API and all `curl` test commands
- The ONNX embedding model files (platform-agnostic; ONNX Runtime loads the right native lib)
- The `chunks` table schema and pgvector queries
