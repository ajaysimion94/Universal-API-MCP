# Deployment

How to set up, build, and run the MCP Server. For dev workflow (hot reload, testing, build internals),
see [`DEVELOPMENT.md`](DEVELOPMENT.md).

> **Cross-platform:** the build/run commands below work identically on macOS, Linux, and Windows.
> No external database or services are required — all dependencies are embedded or downloaded
> via the Plugins page.

---

## Frequently used deploy commands

```sh
# build the single runnable JAR (backend + SPA bundled)
cd mcp-server && mvn package

# run it (serves SPA + API on http://127.0.0.1:8080)
cd mcp-server && java -jar target/mcp-server.jar

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
| Java (JDK) | 17+ (17, 20, and 21 supported) | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Node.js | 20+ (built and tested on 24) | `node -v` |
| npm | 10+ | `npm -v` |
| Python | 3.10+ | `python3 --version` (only for SearXNG / web toggle) |
| Visual C++ x64 Redistributable | Latest v14 (Windows only) | Required by ONNX Runtime for semantic search |

No Docker, PostgreSQL, or other external services are required. The app uses embedded SQLite
(bundled in the JAR via `org.xerial:sqlite-jdbc`) and downloads the sqlite-vec extension + nomic
embedding model on demand via the Plugins page.

---

## One-time setup

There is no app-specific one-time setup. On Windows, install the latest Microsoft Visual C++ x64
Redistributable before enabling the Nomic embedding model; ONNX Runtime depends on it. If that native
runtime is missing or incompatible, the server still starts and automatically falls back to
keyword-only search. Build and run the JAR, then open **http://127.0.0.1:8080** and go to the
**Plugins** page to install what you need:

| Plugin | What it does | Install action |
| --- | --- | --- |
| **Embedded vector store** | SQLite + sqlite-vec + FTS5 — vector and lexical search | **Built-in** — native extension ships inside the jar, active on first boot |
| **Nomic embedding model** | nomic-embed-text-v1.5 (768-dim) — in-process ONNX embedding | **Built-in** — model ships inside the jar, loads on first boot; toggle off on low-RAM machines |
| **SearXNG web search** | Self-hosted meta-search engine for web augmentation (optional) | One click on the Plugins page. Source ships in the jar (no git needed); requires **Python 3.10+** and internet for pip dependencies |

Copy the jar to any machine with the platform prerequisites and run `java -jar` — file management
and full search work immediately and offline. Only the optional SearXNG web toggle needs an app-level
install step. Bundled artifacts are extracted next to the jar (`models/`, `lib/`) on first run.

### Low-memory machines (< 4GB RAM)

Uploads return immediately — extraction, chunking, and embedding run on a background
queue (the Files page shows live progress). Even so, in-process ONNX embedding needs
roughly 700MB–1GB of RAM on top of the OS; on a 2GB Windows machine that means heavy
swapping and very slow ingestion. On such machines:

- **Disable the Nomic embedding model** (toggle on the Plugins page) — this unloads the
  model from memory. Files are still chunked and FTS-indexed, so keyword (lexical) search
  works — the search page shows a "semantic search is off" notice. ~4GB is the practical
  minimum for full semantic search.
- Cap the heap explicitly: `java -Xmx512m -jar target/mcp-server.jar`.
- Don't install/run SearXNG there (a Python process costs a few hundred MB more).

---

## Build & run

```sh
cd mcp-server
mvn package                          # builds JAR + SPA + bundles model/native libs/searxng source (~390MB jar)
java -jar target/mcp-server.jar      # serves SPA + API on http://127.0.0.1:8080
```

The first `mvn package` downloads the bundled artifacts (sha256-verified) into a cache under
`~/.m2` — later builds, including after `mvn clean`, reuse it. `-Dskip.bundle=true` skips
bundling for fast dev loops (the app then relies on already-extracted `models/` and `lib/`).

Open **http://127.0.0.1:8080** — the universal search page (landing). Files & folders is at **/files**.
Plugins is at **/plugins**.

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
| `spring.datasource.url` | `jdbc:sqlite:./data/mcpserver.db` | SQLite database path |
| `spring.sql.init.mode` | `always` | Runs `schema.sql` on startup (idempotent) |
| `spring.servlet.multipart.max-file-size` | `100MB` | Upload limit |
| `rag.embedding.model-dir` | `${user.dir}/models/nomic-embed-text-v1.5` | Path to the ONNX model + tokenizer |
| `rag.reranker.model-dir` | `${user.dir}/models/ms-marco-MiniLM-L6-v2` | Path to the cross-encoder ONNX model + tokenizer |
| `rag.search.vector-top-k` | `40` | Candidates from the vector leg |
| `rag.search.lexical-top-k` | `40` | Candidates from the FTS leg |
| `rag.search.rrf-k` | `60` | Reciprocal Rank Fusion constant |
| `rag.search.min-relevance-score` | `0.015` | Local abstention threshold after cross-encoder reranking |
| `rag.web.searxng-url` | `http://127.0.0.1:8888` | SearXNG JSON API endpoint (web toggle) |
| `rag.web.engines` | `bing,brave,duckduckgo,startpage,stackoverflow` | Explicit provider fallback set; successful engines still contribute when another is rate-limited |
| `rag.web.page-count` | `5` | Max ranked web results returned |
| `rag.web.query-count` | `4` | Contextual query variants generated from user intent |
| `rag.web.candidate-count` | `24` | Deduplicated SearXNG candidates retained before reranking |
| `rag.web.page-fetch-count` | `10` | Strongest candidates whose full pages are safely extracted |
| `rag.web.page-fetch-timeout-seconds` | `8` | Per-page HTTP timeout |
| `rag.web.max-response-bytes` | `2097152` | Hard response-body download cap |
| `rag.web.max-content-chars` | `20000` | Extracted text cap per page |
| `rag.web.min-relevance-score` | `0.20` | Minimum calibrated score for a web result |

---

## The files & folders API

| Method | Path | Description |
| --- | --- | --- |
| `GET`  | `/api/files` | Root folder |
| `GET`  | `/api/files/tree` | Flat file-tree snapshot for source selection |
| `GET`  | `/api/files/{id}/children` | List a folder's children (folders first, then files) |
| `GET`  | `/api/files/{id}/path` | Breadcrumb path from root to the node |
| `POST` | `/api/files/{parentId}/folders` | Create a subfolder (JSON body: `{"name": "..."}`) |
| `POST` | `/api/files/{parentId}/upload` | Upload a single file (`multipart/form-data`, field `file`) |
| `POST` | `/api/files/{parentId}/upload-folder` | Bulk upload a folder tree (see below) |
| `DELETE` | `/api/files/{id}` | Delete a node and its descendants (root is protected) |
| `POST` | `/api/summary-exports` | Download selected uploaded-file/connection RAG content as TXT |

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

Plain keywords run the RAG pipeline (embed query → hybrid search: sqlite-vec cosine + FTS5,
deterministic RRF merge → ONNX cross-encoder rerank → cited context). A semantic/lexical fallback
remains available if the reranker model cannot load. `#keyword` invocations are the deterministic tool path (Phase 3
seam — returns "no tools registered" until then). The server returns **cited context, not generated
answers**.

The `web=true` toggle performs contextual research rather than forwarding only the raw question:
the server creates focused primary-source and recency/security variants, retrieves a broad SearXNG
candidate set, canonicalizes and deduplicates URLs, safely fetches the strongest pages, extracts
their text with Tika, and semantically reranks them. Authority, freshness, cross-query/engine
corroboration, and domain diversity are ranking features; provider order is not the final order.
The JSON response includes `webQueries` for transparency. Web content remains in memory and is not
persisted. Requires SearXNG; if it is down, the request degrades gracefully to local-only.

```sh
# upload a document (triggers ingestion: chunk → embed → store)
curl -X POST http://127.0.0.1:8080/api/files/root/upload -F "file=@runbook.md;type=text/markdown"

# search it (local only)
curl "http://127.0.0.1:8080/api/search?q=database+outage+failover"

# search with web augmentation (requires SearXNG on :8888)
curl "http://127.0.0.1:8080/api/search?q=database+outage+failover&web=true"
```

---

## Chat page

The Chat page keeps local conversation history in the browser and displays the cited RAG results for
each question. It uses the same `GET /api/search` endpoint as the search surface; it does not send
prompts, files, browser sessions, or credentials to an external answer-generation service.

Use `#tool_name` or `@app #tool_name` in the composer to invoke imported tools deterministically.

---

## The plugins API

| Method | Path | Description |
| --- | --- | --- |
| `GET`  | `/api/plugins` | List all plugins with status |
| `POST` | `/api/plugins/{id}/install` | Trigger install (returns jobId) |
| `POST` | `/api/plugins/{id}/enable` | Enable a plugin |
| `POST` | `/api/plugins/{id}/disable` | Disable a plugin |
| `POST` | `/api/plugins/{id}/start` | Start a process plugin (SearXNG) |
| `POST` | `/api/plugins/{id}/stop` | Stop a process plugin (SearXNG) |
| `GET`  | `/api/plugins/jobs/{jobId}` | Poll install job progress |

---

## Troubleshooting

- **Port 8080 already in use** — set `server.port` in `application.yml` or run with
  `--server.port=8081`.
- **Web toggle shows "web results unavailable"** — SearXNG isn't running on `127.0.0.1:8888`. Start
  it from the Plugins page.
- **404 on a deep UI route** — only happens if the SPA wasn't bundled (you ran with
  `-Dskip.frontend=true` and there's no `static/index.html`). Build the frontend once with
  `mvn package`, or use the Vite dev server (see [`DEVELOPMENT.md`](DEVELOPMENT.md)).
- **Tests leave stale chunks in the DB** — `@SpringBootTest` writes to the SQLite DB. Delete
  `mcp-server/data/mcpserver.db` for a clean run.

---

## MCP client setup

Configure a compatible Streamable HTTP client with `http://127.0.0.1:8080/mcp`. After initialize,
discover the guide resources and prompts before calling dynamic tools:

```text
resources/list
resources/read  mcp://enterprise-mcp/guides/operating-guide
resources/read  mcp://enterprise-mcp/guides/llm-playbook.json
tools/list
prompts/list
```

The operating guide requires evidence-first answers and explicit human approval before using a write
tool's confirmation token. See [docs/mcp-client-guide.md](docs/mcp-client-guide.md) for the full
client contract. The endpoint remains local-only until Phase 6; do not expose it to an untrusted
network.

---

## Roadmap

This is Phase 1 work. See [`docs/plan.md`](docs/plan.md) for the full six-phase plan — MCP SDK
integration, the workflow engine, connectors, and auth all land in later phases.
