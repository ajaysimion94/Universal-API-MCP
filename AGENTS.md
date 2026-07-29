# AGENTS.md

High-signal notes for AI coding sessions in this repo. Read this before modifying code.

## Source of truth

- `docs/product-idea.md` — architecture blueprint (**what** and **why**). All `§` references in code/comments point into it.
- `docs/plan.md` — execution tracker (**in what order**, E2E checklists per phase). Check the current phase before adding features.
- `.impeccable.md` — **read this before any UI/design work**. It holds the design context (users, brand, aesthetic direction, anti-references).
- `DECISIONS.md` — append-only log of significant architecture/scope/sequencing choices. Read it for context; add an entry when you make a non-trivial decision.
- `DEVELOPMENT.md` — dev workflow, layout, testing, build internals.
- `DEPLOYMENT.md` — setup, build, run, config, API reference.
- `README.md` — navigation hub.

## Project overview

An enterprise MCP (Model Context Protocol) server with a browser-native HTML/CSS/JavaScript Web UI.
It exposes:

- A **files & folders manager** (SharePoint-like upload + folder tree).
- A **RAG search** pipeline over ingested documents (hybrid sqlite-vec + FTS5, deterministic RRF,
  in-process ONNX cross-encoder reranking).
- **Connectors** for Confluence, Jira, and zero-code API onboarding via Postman/OpenAPI import.
- Imported API tools that can be invoked from the Web UI `#`/`@` grammar or over MCP.
- A real **MCP Streamable HTTP endpoint** at `/mcp` for AI clients.

The project is currently in **Phase 1** per `README.md`, with Phase 2 knowledge/search features partially complete (see `docs/plan.md` checkboxes for exact state). Authentication and ACL enforcement are deliberately deferred to **Phase 6**; until then the server binds only to `127.0.0.1` and must not be exposed publicly.

## Technology stack

- **Backend:** Java 17+ bytecode/API baseline, Spring Boot 3.3.4, Maven 3.9+
- **Frontend:** semantic HTML, CSS, and browser-native JavaScript ES modules; no Node/npm toolchain
- **Database:** embedded SQLite via `org.xerial:sqlite-jdbc:3.46.1.3`, WAL mode, `SingleConnectionDataSource`
- **Vector search:** sqlite-vec 0.1.9 (loadable extension)
- **Lexical search:** SQLite FTS5 (built in)
- **Embeddings:** nomic-embed-text-v1.5 (768-dim) via ONNX Runtime 1.22 + DJL tokenizers 0.30
- **Document text extraction:** Apache Tika 2.9.4 (`tika-core` + `tika-parsers-standard-package`)
- **MCP protocol:** Java MCP SDK 2.0.0 (`mcp-core` + `mcp-json-jackson2`)
- **HTTP tests:** WireMock standalone 3.9.1
- **Testing:** JUnit 5, Spring Boot Test, AssertJ

No external database, Docker, or Kubernetes is required for the current phase.

## Build and run commands

From `mcp-server/`:

```sh
# Full build: backend + static UI + bundled model/native libs/searxng source (~390MB JAR)
mvn package

# Run the single runnable JAR (serves SPA + API on http://127.0.0.1:8080)
java -jar target/mcp-server.jar

# Development server (API + UI on the same origin)
mvn spring-boot:run

# Skip downloading/bundling model + sqlite-vec + searxng source (uses already-extracted files)
mvn package -Dskip.bundle=true

# Compile check only
mvn -q compile

# Backend tests
mvn test
mvn test -Dtest=FileServiceTests#canUploadFileAndItAppearsAsAChild
```

Open **http://127.0.0.1:8080**. Static UI changes appear after a browser refresh.

## Project layout

```
├── docs/                         # Pre-implementation docs (product-idea.md, plan.md, etc.)
├── mcp-server/                   # Sole buildable Maven module
│   ├── pom.xml
│   ├── data/                     # SQLite DB (gitignored)
│   ├── models/                   # ONNX model + tokenizer extracted at runtime (gitignored)
│   ├── lib/                      # sqlite-vec extension extracted at runtime (gitignored)
│   ├── src/main/java/com/mcpserver/
│   │   ├── McpServerApplication.java
│   │   ├── config/               # DatasourceConfig, WebMvcConfig
│   │   ├── controllers/          # REST controllers (FileController, SearchController)
│   │   ├── services/             # Business logic (FileService, IngestionService, SearchService)
│   │   ├── repositories/         # InMemoryFileRepository, ChunkRepository
│   │   ├── models/               # FileNode, Chunk, BulkUploadResult
│   │   ├── plugins/              # Plugin system + SqliteVecStore/Nomic/SearXng plugins
│   │   ├── rag/                  # RAG pipeline seams
│   │   │   ├── chunking/         # Chunker, StructureAwareChunker
│   │   │   ├── embedding/        # EmbeddingClient, OnnxEmbeddingClient
│   │   │   ├── retrieval/        # SearchPipeline, RrfFusion
│   │   │   ├── reranker/         # ONNX cross-encoder + deterministic semantic fallback
│   │   │   └── web/              # query planner, SearXNG, page fetch, semantic ranking
│   │   ├── connectors/           # Confluence/Jira/API_COLLECTION connectors, event queue
│   │   ├── tools/                # Postman/OpenAPI parsers, ApiToolService/Executor/Controller
│   │   └── mcp/                  # McpServerConfig, McpToolBridge (only SDK touch points)
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── schema.sql            # chunks, connections, ingestion_events, api_tools
│   │   └── static/               # UI source: index, ES modules, CSS, page controllers
│   └── src/test/java/com/mcpserver/
```

## Main modules and responsibilities

- **`config/`** — Spring configuration. `DatasourceConfig` creates an embedded SQLite `SingleConnectionDataSource` with extensions enabled and WAL mode; `WebMvcConfig` forwards browser routes.
- **`controllers/`** — REST adapters only; no business logic. `FileController` (`/api/files`), `SearchController` (`/api/search`).
- **`services/`** — Reusable business logic: `FileService`, `IngestionService`, `SearchService`, `IngestionProgressTracker`.
- **`repositories/`** — `InMemoryFileRepository` (file tree, resets on restart), `ChunkRepository` (SQLite JDBC for chunks/FTS/vector).
- **`models/`** — POJOs/DTOs: `FileNode`, `Chunk`, `BulkUploadResult`.
- **`plugins/`** — `Plugin` interface, `PluginRegistry`, `PluginStateStore` (JSON file), `PluginController`. Plugins: `SqliteVecStorePlugin`, `NomicEmbeddingPlugin`, `SearXngPlugin`.
- **`rag/`** — Swappable RAG seams: chunking, embedding, retrieval, reranker, web fetcher.
- **`connectors/`** — `Connection` model, `ConnectionService`, `ConnectionController` (`/api/connections`), `ConfluenceConnector`, `JiraConnector`, `ApiCollectionConnector`, durable event queue (`IngestionEventRepository`, `EventQueueWorker`), `CredentialCipher`.
- **`tools/`** — Postman/OpenAPI parsers (`PostmanCollectionParser`, `OpenApiParser`, `SpecFetcher`), `ApiToolService`, `ApiToolExecutor`, `ApiToolController` (`/api/tools`), `ToolQueryParser` for the `#`/`@` grammar, custom tool groups (`ToolGroup`, `ToolGroupRepository`, `ToolGroupService`, `ToolGroupController` at `/api/groups`).
- **`mcp/`** — The only code touching the MCP SDK: `McpServerConfig` bootstraps a Streamable HTTP servlet at `/mcp`; `McpToolBridge` registers/unregisters tools at runtime.

## Backend conventions

- **Separate business logic from protocol handling.** `services/` and `repositories/` are reusable outside MCP; `controllers/` and `mcp/` only adapt them to REST/MCP.
- **Error status mapping:** `IllegalArgumentException` → 400, `IllegalStateException` → 409. Replicate this pattern in new controllers.
- **Tool naming:** imported tools are `{app-name}_{request-name}` in lowercase snake case; this id doubles as the Web UI `#` keyword.
- **ACL tags are captured on every chunk from Phase 2 onward.** Never defer capture when adding an intake path.
- **Direct JDBC for chunk store.** Use `JdbcTemplate` and explicit SQL; no JPA for vector/FTS tables.
- **SQLite connection semantics.** `SingleConnectionDataSource` keeps sqlite-vec loaded across the app lifecycle; do not switch to a pooling datasource for SQLite without handling extension-per-connection behavior.

## Frontend conventions

- **API calls live in `static/api.js`.** Page modules use it; do not call `fetch` directly from pages.
- **Shared DOM utilities and icons live in `static/ui.js`.** Escape dynamic text before inserting it into HTML templates.
- **Routes live in `static/app.js`; pages export `mount()` from `static/pages/`.** Return cleanup functions for timers/listeners.
- **CSS is split:** `styles.css` holds OKLCH design tokens + base reset; `components.css` holds component styles. Use the tokens; do not hardcode colors.
- **Design direction:** refined utilitarian, dark theme, amber accent. Fonts: Hanken Grotesk (UI) and JetBrains Mono (paths/IDs/metadata). See `.impeccable.md` for the full anti-reference list (no gradient text, glassmorphism, glow, side-stripe borders).
- **Routes (`static/app.js`):** `/` search, `/files`, `/plugins`, `/connections`, `/apps`, `/insights`, `/guide`. Deep routes are forwarded by `WebMvcConfig`.

## Testing

```sh
cd mcp-server && mvn test
cd mcp-server && mvn test -Dtest=FileServiceTests#canUploadFileAndItAppearsAsAChild
./scripts/run-eval.sh
cd mcp-server && mvn package -Dskip.bundle=true
```

- Backend: JUnit 5 + Spring Boot Test + AssertJ. `@SpringBootTest` boots the full context (ONNX + SQLite) and takes ~7s per test class.
- Tests use an isolated in-memory SQLite database and do not modify `mcp-server/data/mcpserver.db`.
- Frontend: no generated bundle; verify packaged resources and smoke the served routes.

Key backend test classes:

- `FileServiceTests`
- `ConfluenceConnectorTests`, `JiraConnectorTests`, `ApiCollectionConnectorTests`
- `IngestionEventRepositoryTests`, `AtlassianAuthTests`
- `PostmanCollectionParserTests`, `OpenApiParserTests`, `SpecFetcherTests`
- `ApiToolExecutorTests`, `ToolQueryParserTests`

## Runtime architecture and deployment

- **Single runnable JAR.** `mvn package` includes browser-native static resources directly and downloads/extracts the embedding + cross-encoder ONNX models, per-platform sqlite-vec libs, and SearXNG source snapshot into the JAR.
- **Embedded SQLite** at `./data/mcpserver.db`; no external database is required.
- **Plugins page (`/plugins`)** installs/enables local infrastructure: vector store, embedding model, and optional SearXNG. The app boots in degraded mode (file management works, search/ingestion report "not ready") when plugins are missing.
- **Default bind:** `server.address=127.0.0.1`, `server.port=8080` — trusted internal network only until Phase 6.
- **SearXNG** is optional; it runs as a native Python process on `127.0.0.1:8888` managed from the Plugins page. Web-augmented search degrades gracefully if it is not running.
- **Configuration:** see `mcp-server/src/main/resources/application.yml`. Notable keys:
  - `spring.datasource.url` — SQLite path
  - `rag.embedding.model-dir` — ONNX model location
  - `rag.search.*` — top-k, RRF constant
  - `rag.web.searxng-url` — SearXNG endpoint
  - `connectors.poll-interval-ms` — connector delta-poll cadence
  - `connectors.webhook-base-url` — public base URL for Confluence/Jira webhooks; blank by default because the server is `127.0.0.1`

## REST / MCP surface summary

- **`/api/files`** — files & folders (root, children, path, create folder, upload, upload-folder, delete, ingestion progress)
- **`/api/search?q=...&topK=20&web=false`** — RAG search or `#`/`@` tool invocation
- **`/api/plugins`** — list/install/enable/disable/start/stop plugins, poll install jobs
- **`/api/connections`** — Confluence/Jira/API_COLLECTION connectors (CRUD, backfill, enable/disable, webhook intake at `/{id}/webhook`)
- **`/api/tools`** — imported API tools (list, enable/disable, knowledge-source flag, invoke)
- **`/api/groups`** — custom tool groups (CRUD, member management, batch enable/disable); group slugs work in the `@` position of the search grammar
- **`/mcp`** — MCP Streamable HTTP endpoint (Java MCP SDK)

See `DEPLOYMENT.md` for full endpoint documentation.

## Security considerations and hard constraints

Do not violate these without an explicit project decision recorded in `DECISIONS.md`:

- **`server.address=127.0.0.1` is a deliberate guardrail.** No auth until Phase 6; the server runs on trusted internal networks only. Do not change the bind or expose it publicly without implementing Phase 6.
- **No Docker / Kubernetes / virtualization.** Single runnable JAR, in-process ML inference, natively installed backing services. Revisit only in Phase 5+ if the constraint lifts.
- **Open-source-first.** Proprietary services (Microsoft Graph/Entra for Teams) are deferred to Phase 4.
- **ACL tags are captured on every chunk from Phase 2; enforcement lands in Phase 6.** Never defer capture. When adding an intake path, tag chunks before first merge.
- **Auth & access control land last (Phase 6).** Do not add Spring Security / OAuth2 / JWT / Keycloak before that phase.
- **E2E or it didn't ship.** A phase exits only when its E2E test checklist in `docs/plan.md` passes end to end.

## Common gotchas

- If the dev UI hangs on "connecting…", the backend is not running on `127.0.0.1:8080`.
- A 404 on a deep UI route means its explicit `WebMvcConfig` forward is missing.
- A module 404 means its import path or file under `resources/static` is wrong.
- If the local app has stale chunks, stop it and delete `mcp-server/data/mcpserver.db` only when you intentionally want to reset all local app data.
- Orphaned chunks may remain across restarts because `InMemoryFileRepository` resets while SQLite persists; delete the DB if needed.

## Glossary

- **MCP** — Model Context Protocol; the AI-client-facing protocol surface.
- **RAG** — Retrieval-Augmented Generation pipeline. The MCP/search surface and Web UI Chat page return cited context, not generated answers.
- **RRF** — Reciprocal Rank Fusion, used to merge vector and lexical search results.
- **sqlite-vec** — SQLite loadable extension providing vector search (`vec0`).
- **FTS5** — SQLite built-in full-text search used for the lexical leg.
- **SearXNG** — Self-hosted meta-search engine used for the optional web-augmentation toggle.
- **ACL tags** — String tags captured on every chunk; used for permission filtering in Phase 6.
- **`#keyword` grammar** — Deterministic tool invocation syntax in the Web UI search bar (e.g. `#app_create_item`).
- **`API_COLLECTION`** — Connector type for zero-code Postman/OpenAPI import.
