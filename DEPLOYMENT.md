# Deployment

How to set up, build, and run the MCP Server. For dev workflow (hot reload, testing, build internals),
see [`DEVELOPMENT.md`](DEVELOPMENT.md). For Windows/Linux-specific setup, see
[`SETUP-WINDOWS.md`](SETUP-WINDOWS.md) / [`SETUP-LINUX.md`](SETUP-LINUX.md).

> **Cross-platform note:** the build/run commands below work identically on macOS, Linux, and Windows.
> Only the native-prerequisite setup differs by OS — see
> [`README.md`](README.md#cross-platform--read-this-before-setup).

---

## Frequently used deploy commands

```sh
# build the single runnable JAR (backend + SPA bundled)
cd mcp-server && mvn package

# run it (serves SPA + API on http://127.0.0.1:8080)
cd mcp-server && java -jar target/mcp-server.jar

# start SearXNG separately (only if you want the web search toggle)
SEARXNG_SETTINGS_PATH=/tmp/searxng-settings.yml /tmp/searxng/venv/bin/searxng-run

# quick API check
curl http://127.0.0.1:8080/api/files
curl "http://127.0.0.1:8080/api/search?q=database+outage+failover"
```

The server binds to `127.0.0.1` only — trusted internal network, no auth yet (Phase 6 per
[`docs/plan.md`](docs/plan.md)).

---

## Prerequisites

| Tool | Version | Check |
| --- | --- | --- |
| Java (JDK) | 21+ | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Node.js | 20+ (built and tested on 24) | `node -v` |
| npm | 10+ | `npm -v` |
| PostgreSQL | 15+ | `psql --version` |
| pgvector | 0.8.4 | `psql -d mcpserver -c "SELECT extversion FROM pg_extension WHERE extname='vector';"` |
| Python | 3.10+ | `python3 --version` (only for SearXNG / web toggle) |

No Docker or virtualization is required — the server ships as a single runnable JAR and all backing
services run as native processes.

> **Windows users:** see [`SETUP-WINDOWS.md`](SETUP-WINDOWS.md) (Visual Studio build tools for
> pgvector, PowerShell venv activation, `python -m searx.webapp` instead of `searxng-run`).
> **Linux users:** see [`SETUP-LINUX.md`](SETUP-LINUX.md) (apt/dnf/pacman, `build-essential` for
> pgvector, systemd, SELinux/firewall notes).

---

## One-time setup

### 1. PostgreSQL + pgvector

pgvector must be built from source against your Postgres version (the Homebrew bottle only ships for
pg17/18). The `mcpserver` DB must exist with the `vector` extension enabled:

```sh
# build + install pgvector against pg15
git clone --depth 1 --branch v0.8.4 https://github.com/pgvector/pgvector.git /tmp/pgvector
cd /tmp/pgvector && make PG_CONFIG=$(pg_config --bindir)/pg_config install

# create the DB + enable the extension
createdb mcpserver
psql -d mcpserver -c "CREATE EXTENSION vector;"
```

The `chunks` table (with `vector(768)` HNSW index + `tsvector` lexical leg) is created automatically
from `src/main/resources/schema.sql` on every startup (`spring.sql.init.mode=always`).

### 2. nomic-embed-text-v1.5 ONNX model (~131MB, gitignored)

The embedding model runs in-process via ONNX Runtime — no sidecar. Download it to `mcp-server/models/`:

```sh
mkdir -p mcp-server/models/nomic-embed-text-v1.5
cd mcp-server/models/nomic-embed-text-v1.5
curl -L -o model_quantized.onnx "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/resolve/main/onnx/model_quantized.onnx"
curl -L -o tokenizer.json "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/resolve/main/tokenizer.json"
```

### 3. SearXNG (optional — only for the Web search toggle)

SearXNG is a self-hosted open-source meta-search engine. It runs as a **native Python process**,
separate from the MCP server JAR — it is **not** bundled. The Web toggle on the search page won't
return web results unless SearXNG is running on `127.0.0.1:8888`.

```sh
git clone --depth 1 https://github.com/searxng/searxng.git /tmp/searxng
cd /tmp/searxng && python3 -m venv venv && source venv/bin/activate
pip install -r requirements.txt && pip install --no-build-isolation -e .
```

Create a settings file that enables the JSON API (the default config disables it):

```yaml
# /tmp/searxng-settings.yml
use_default_settings: true
general:
  instance_name: "MCP Local SearXNG"
search:
  formats: [html, json]      # ← JSON API must be enabled
  default_lang: "en"
server:
  secret_key: "change-me-to-a-random-string"
  bind_address: "127.0.0.1"
  port: 8888
  limiter: false             # ← disable rate limiting for local use
redis:
  url: false
```

Start it (separate terminal — it stays running alongside the MCP server):

```sh
SEARXNG_SETTINGS_PATH=/tmp/searxng-settings.yml /tmp/searxng/venv/bin/searxng-run
```

Verify: `curl "http://127.0.0.1:8888/search?q=test&format=json"` should return JSON with results.

---

## Build & run

```sh
cd mcp-server
mvn package                          # builds JAR + SPA, bundles SPA into JAR
java -jar target/mcp-server.jar      # serves SPA + API on http://127.0.0.1:8080
```

Open **http://127.0.0.1:8080** — the universal search page (landing). Files & folders is at **/files**.

To run the backend without rebuilding the SPA (fast):

```sh
cd mcp-server
mvn package -Dskip.frontend=true
java -jar target/mcp-server.jar      # API works; UI 404s unless SPA was built once before
```

---

## Configuration

`mcp-server/src/main/resources/application.yml`:

| Key | Default | Notes |
| --- | --- | --- |
| `server.port` | `8080` | HTTP port |
| `server.address` | `127.0.0.1` | Trusted-network bind — do not expose publicly until Phase 6 |
| `spring.sql.init.mode` | `always` | Runs `schema.sql` on startup (idempotent) |
| `spring.servlet.multipart.max-file-size` | `100MB` | Upload limit |
| `rag.embedding.model-dir` | `${user.dir}/models/nomic-embed-text-v1.5` | Path to the ONNX model + tokenizer |
| `rag.search.vector-top-k` | `40` | Candidates from the pgvector leg |
| `rag.search.lexical-top-k` | `40` | Candidates from the FTS leg |
| `rag.search.rrf-k` | `60` | Reciprocal Rank Fusion constant |
| `rag.web.searxng-url` | `http://127.0.0.1:8888` | SearXNG JSON API endpoint (web toggle) |
| `rag.web.page-count` | `5` | Max web pages fetched per query |
| `rag.web.chunk-per-query` | `8` | Max web chunks embedded in-memory per query |

Override DB credentials via env vars: `DB_USER`, `DB_PASSWORD`.

---

## The files & folders API

| Method | Path | Description |
| --- | --- | --- |
| `GET`  | `/api/files` | Root folder |
| `GET`  | `/api/files/{id}/children` | List a folder's children (folders first, then files) |
| `GET`  | `/api/files/{id}/path` | Breadcrumb path from root to the node |
| `POST` | `/api/files/{parentId}/folders` | Create a subfolder (JSON body: `{"name": "..."}`) |
| `POST` | `/api/files/{parentId}/upload` | Upload a single file (`multipart/form-data`, field `file`) |
| `POST` | `/api/files/{parentId}/upload-folder` | Bulk upload a folder tree (see below) |
| `DELETE` | `/api/files/{id}` | Delete a node and its descendants (root is protected) |

### Folder upload (`upload-folder`)

Upload a whole folder hierarchy in one request. Send multiple `files` parts (the files) and a parallel
`paths` part per file giving its relative path with `/` separators (e.g. `MyFolder/sub/a.txt`). The
server recreates the folder tree under `{parentId}`, **reusing existing folders** and **skipping files
that already exist** — so re-uploading the same folder is idempotent. Returns a summary:

```json
{"foldersCreated": 3, "filesUploaded": 4, "filesSkipped": 0, "totalFiles": 4}
```

The Web UI's **Upload folder** button uses the browser's `webkitdirectory` picker and sends each
file's `webkitRelativePath` as its `paths` entry, so the on-disk structure is mirrored exactly.

Errors return `{"error": "..."}` with status `400` (bad request) or `409` (conflict, e.g. duplicate
name).

```sh
curl http://127.0.0.1:8080/api/files
curl -X POST http://127.0.0.1:8080/api/files/root/folders \
  -H "Content-Type: application/json" -d '{"name":"Runbooks"}'
curl http://127.0.0.1:8080/api/files/root/children
```

---

## The search API

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/search?q=...&topK=20&web=false` | RAG search — cited context from ingested documents |

Plain keywords run the RAG pipeline (embed query → hybrid search: pgvector cosine + PostgreSQL FTS,
RRF-merged → rerank → cited context). `#keyword` invocations are the deterministic tool path (Phase 3
seam — returns "no tools registered" until then). The server returns **cited context, not generated
answers**.

The `web=true` toggle augments results with live web content: the server queries the local SearXNG
instance for relevant URLs, fetches + extracts (Tika) + chunks + embeds each page **in-memory** (not
persisted), and merges the web chunks into results tagged `sourceKind="web"` with the source URL as
provenance. Requires SearXNG running; if it's down, the toggle degrades gracefully to local-only.

```sh
# upload a document (triggers ingestion: chunk → embed → store in pgvector)
curl -X POST http://127.0.0.1:8080/api/files/root/upload -F "file=@runbook.md;type=text/markdown"

# search it (local only)
curl "http://127.0.0.1:8080/api/search?q=database+outage+failover"

# search with web augmentation (requires SearXNG on :8888)
curl "http://127.0.0.1:8080/api/search?q=database+outage+failover&web=true"
```

---

## Troubleshooting

- **Server fails to start: "extension vector is not available"** — pgvector isn't installed for your
  Postgres version. Build it from source (see [One-time setup](#one-time-setup)) and run
  `CREATE EXTENSION vector;` in the `mcpserver` DB.
- **Server fails to start: embedding model not found** — the nomic ONNX model isn't in
  `mcp-server/models/nomic-embed-text-v1.5/`. Download it (see [One-time setup](#one-time-setup)).
- **Server fails to start: "connection refused" to Postgres** — Postgres isn't running, or auth
  config blocks the connection. Override credentials: `DB_USER=postgres DB_PASSWORD=... java -jar ...`
- **Port 8080 already in use** — set `server.port` in `application.yml` or run with
  `--server.port=8081`.
- **Web toggle shows "web results unavailable"** — SearXNG isn't running on `127.0.0.1:8888`. Start
  it separately (it's a native Python process, not part of the JAR), and confirm JSON API is enabled
  in its settings (`search.formats: [html, json]`).
- **404 on a deep UI route** — only happens if the SPA wasn't bundled (you ran with
  `-Dskip.frontend=true` and there's no `static/index.html`). Build the frontend once with
  `mvn package`, or use the Vite dev server (see [`DEVELOPMENT.md`](DEVELOPMENT.md)).
- **Tests leave stale chunks in the DB** — `@SpringBootTest` writes to the real `mcpserver` DB. Clean
  with `psql -d mcpserver -c "TRUNCATE chunks;"`.

---

## Roadmap

This is Phase 1 work. See [`docs/plan.md`](docs/plan.md) for the full six-phase plan — MCP SDK
integration, the workflow engine, connectors, and auth all land in later phases.
