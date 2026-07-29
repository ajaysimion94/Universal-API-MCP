# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An enterprise MCP (Model Context Protocol) server — Java 17+ / Spring Boot 3.3.4 backend with a
browser-native HTML/CSS/JavaScript Web UI. Currently implements: a SharePoint-like files & folders
manager, RAG search over ingested content (embedded SQLite + sqlite-vec + in-process ONNX
embeddings), a Confluence/Jira ingestion connectors subsystem, and an optional web-search toggle
(SearXNG). `mcp-server/` is the only buildable module — one Maven build produces backend + frontend
as a single runnable JAR. This is currently Phase 1/2 work per `docs/plan.md`; check that file for
current phase status before starting new feature work.

**Source of truth, in order of authority:**
- `docs/product-idea.md` — architecture blueprint (**what** and **why**); all `§` references
  across the repo point into it.
- `docs/plan.md` — execution tracker (**in what order**, per-phase E2E checklists). Check the
  current phase before adding features.
- `DECISIONS.md` — append-only log of significant choices (stack, scope, sequencing). **Read it
  for context, and add an entry whenever you make a non-trivial decision.**
- `AGENTS.md` — the fuller agent-conventions doc this file summarizes; `DEVELOPMENT.md` /
  `DEPLOYMENT.md` for dev workflow / setup detail.
- `.impeccable.md` — **read before any UI/design work.** Design context (brand, aesthetic
  direction, anti-references) required by the frontend conventions below.

## Commands

```sh
# build + run everything (one JAR, serves SPA + API on :8080)
cd mcp-server && mvn package && java -jar target/mcp-server.jar

# dev mode — one process, open http://127.0.0.1:8080
cd mcp-server && mvn spring-boot:run

# backend tests
cd mcp-server && mvn test
cd mcp-server && mvn test -Dtest=FileServiceTests                                     # one class
cd mcp-server && mvn test -Dtest=FileServiceTests#canUploadFileAndItAppearsAsAChild    # one method
cd mcp-server && mvn -q compile
cd mcp-server && mvn package -Dskip.bundle=true
```

Notes:
- `mvn test` boots a full `@SpringBootTest` context (ONNX model load + SQLite) for most test
  classes — expect ~7s+ per class, not instant.
- **Tests write to the real SQLite DB** (`mcp-server/data/mcpserver.db`), which is not reset
  between runs. If assertions depend on searching for specific text/counts, make the test data
  unique per run (e.g. embed a UUID in the content) rather than assuming a clean DB — accumulated
  data across runs can push a test's own rows out of a small `topK` result window. For a genuinely
  clean slate: `rm -f mcp-server/data/mcpserver.db`.
- The UI has no framework or build step. Keep API calls in `static/api.js`, shared DOM safety and
  icons in `static/ui.js`, and route controllers under `static/pages/`.

## Architecture

### Backend package layout (`com.mcpserver`)

- `config/` — `WebMvcConfig` (SPA route forwards — **any new client-side route must be added here
  as `registry.addViewController("/newroute")` or it 404s on direct navigation/refresh**),
  `DatasourceConfig` (SQLite, single shared connection — see below).
- `controllers/` — REST layer only; adapts `services/` to HTTP. Convention:
  `IllegalArgumentException` → 400, `IllegalStateException` → 409, via `@ExceptionHandler` in each
  controller.
- `services/` / `repositories/` — business logic, reusable outside MCP. `services/` must not leak
  HTTP/servlet types; the Web UI is a REST channel over these same services, never a second
  implementation.
- `models/` — plain records (`Chunk`, `FileNode`, etc.).
- `rag/{chunking,embedding,retrieval,reranker,web}/` — the search pipeline, built around swappable
  seams (`Chunker`, `EmbeddingClient`, `SearchPipeline`, `Reranker`, `WebFetcher`).
- `plugins/` — local-infrastructure lifecycle management (see below).
- `connectors/` — remote-source ingestion (Confluence/Jira; see below).

### Storage: embedded SQLite, not Postgres

The chunk store is **embedded SQLite + sqlite-vec + FTS5** (`org.xerial:sqlite-jdbc`), not
Postgres/pgvector — despite some stale references to Postgres in older doc text, this was a
deliberate pivot (`DECISIONS.md`, 2026-07-05) for zero-install cross-platform UX. `schema.sql`
creates the base `chunks` table + `chunks_fts` (FTS5) on every boot (`spring.sql.init.mode: always`,
`continue-on-error: true` — this is what makes it safe to add new columns via plain
`ALTER TABLE ... ADD COLUMN` statements in `schema.sql`: a "column already exists" failure on an
existing DB is tolerated and the rest of the script still runs). The `chunks_vec` (vec0 virtual
table) is created separately at runtime by `SqliteVecStorePlugin` after the native extension loads.
`DatasourceConfig` uses a **single shared connection** (`SingleConnectionDataSource`), not a pool —
SQLite loadable extensions are connection-scoped, so a fresh connection per request would see
`no such module: vec0`.

### RAG ingestion pipeline (the reuse seam for any new content source)

`IngestionService.ingest()` / `.enqueue()` is the one source-agnostic entry point: raw bytes +
mimeType + sourceFileId/Name/Path + aclTags → text extraction (Tika for binary formats, direct
handling for text/html/json/etc.) → `Chunker.chunk()` (heading-aware, `StructureAwareChunker`) →
`EmbeddingClient.embed()` (nomic-embed-text-v1.5 ONNX, in-process) → `ChunkRepository.save()`
(writes the `chunks` row + FTS5 + vec0 in one call). Anything that needs to make content
searchable — file upload, a new connector, anything — should write through this, not duplicate
extraction/chunking/embedding logic. Search (`SearchService` → `SearchPipeline` → hybrid
vector+lexical, RRF-merged, then reranked) has no awareness of where a chunk came from — new
sources need zero search-side changes.

### Plugins system (local infrastructure, singleton, `plugins/`)

`Plugin` interface (`install/enable/disable/start/stop/health/isReady`), `PluginRegistry`
(auto-discovers all `Plugin` beans, runs an async single-thread install-job pattern polled via
`GET /api/plugins/jobs/{jobId}`), `PluginStateStore` (flat JSON file, not DB — works pre-install).
Three plugins: `SqliteVecStorePlugin`, `NomicEmbeddingPlugin` (both `builtIn()`, ship in the jar),
`SearXngPlugin` (native Python child process). The app boots in degraded mode when required
plugins aren't ready — file management still works; search/ingestion return a structured
"not ready" response rather than 500ing. **This is a singleton-local-capability pattern, not a
place to add multi-instance remote connections** — see `connectors/` for that shape instead.

### Connectors system (remote ingestion, multi-instance, `connectors/`)

A separate, self-contained package (mirrors `plugins/`'s self-containment, not its shape) for
credentialed connections to remote systems — currently Confluence and Jira (`ConnectionType`
reserves a `SHAREPOINT` slot, not implemented). Key pieces:
- `Connection` (record, SQLite-backed via `ConnectionRepository`) — id, type, base URL, deployment
  type (Cloud/Server-DC, auto-detected), encrypted credentials, sync cursor, status.
- `CredentialCipher` — AES-256-GCM, keyed by a locally generated file (`./data/connections.key`);
  no Vault/KMS yet (that's a Phase 5 item), documented deliberate scope limit in `DECISIONS.md`.
- `SourceConnector` interface (`detectDeployment`/`testConnection`/`backfill`/`registerWebhook`/
  `pollDelta`/`handleWebhookPayload`) — implemented by `ConfluenceConnector`, `JiraConnector`.
  Both write through `IngestionService`, not a parallel pipeline.
- `ConnectionService` — CRUD + the same async-job pattern as `PluginRegistry` (test-connection and
  backfill jobs, polled via `GET /api/connections/jobs/{jobId}`).
- `EventQueueWorker` + `ingestion_events` table — the durable webhook-intake queue. This is a
  SQLite table polled by a single background thread, **not** a Postgres outbox — the plan docs
  originally specified Postgres outbox before the SQLite storage pivot; that line is stale and
  corrected in `DECISIONS.md` (2026-07-13 entries). A row left `PROCESSING` by a crash is reset to
  `PENDING` on startup so nothing is lost.
- `ConnectionPollingScheduler` (`@Scheduled`, `connectors.poll-interval-ms`) — delta-polls every
  `CONNECTED` connection; a `DISABLED` connection is simply excluded from the query, which is the
  entire pause mechanism.
- **Jira API version compatibility**: `JiraConnector` tries `POST /rest/api/3/search/jql` (cursor
  pagination, required on current Jira Cloud — Atlassian retired the classic search endpoint there
  in 2025) first, falling back to classic `GET /rest/api/2/search` (offset pagination) on
  404/410/400-on-first-probe, caching the result per connection. This dual-path design exists
  because Cloud and Server/Data Center have diverged; see `DECISIONS.md` (2026-07-13) for the
  confidence level behind this and `docs/connectors-manual-verification.md` for how to validate it
  against a real instance — the WireMock-based tests only prove internal consistency with an
  assumed API shape, not correctness against real Confluence/Jira.
- Webhook intake (`POST /api/connections/{id}/webhook`) durably inserts into `ingestion_events` and
  returns 202 immediately — actual processing happens on `EventQueueWorker`'s thread, which is what
  keeps intake within a 3-second ack SLA regardless of processing time.

### Frontend (`mcp-server/src/main/resources/static/`)

- Single-fetch-layer convention: all API calls in `api.js`, all icons and HTML escaping in
  `ui.js` — don't `fetch()` directly in page modules or pull an icon
  library.
- CSS split: `styles.css` (OKLCH design tokens, dark theme, amber accent) + `components.css`
  (component styles) — use the tokens, don't hardcode colors. Design direction is refined
  utilitarian / anti-AI-slop (no glow, no gradient text, no glassmorphism) — `.impeccable.md` has
  the full context.
- Routing uses the browser History API, but **every route also needs a server-side forward**
  in `WebMvcConfig` (see above) for direct navigation/refresh to work — a real bug from this exact
  gap: `/connections` 404'd on direct load until added there, despite working fine via in-app
  navigation.
- Maven includes `src/main/resources/static/` directly in `BOOT-INF/classes/static/`; there is no
  Node/npm install, transpile, bundle, or resource-copy phase.

## Hard constraints (don't violate without an explicit phase change)

- `server.address=127.0.0.1` in `application.yml` is a deliberate guardrail, not a bug — no auth
  until Phase 6, trusted-internal-network only. Don't add public exposure or Spring
  Security/OAuth2/JWT/Keycloak before that phase.
- No Docker / Kubernetes / virtualization — single runnable JAR, in-process ML inference, natively
  installed backing services only.
- **ACL tags are captured on every chunk from Phase 2; enforcement lands in Phase 6.** Any new
  ingestion path must tag chunks with ACL metadata before its first merge — never defer capture,
  even though nothing filters on it yet.
- Open-source-first; proprietary services (Microsoft Graph/Entra) are deferred to Phase 4 and
  currently unimplemented (SharePoint connector).
- **E2E or it didn't ship** — a phase/feature isn't done until its E2E flow works end to end, not
  just unit-tested in isolation. Prefer verifying against a running app (`mvn spring-boot:run` +
  `curl`/the UI) before calling something complete, not just a green test suite.
