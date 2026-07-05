# AGENTS.md

High-signal notes for OpenCode sessions. Read this before working in this repo.

## Source of truth

- `docs/product-idea.md` — architecture blueprint (**what** and **why**). All `§` references across the repo point into it.
- `docs/plan.md` — execution tracker (**in what order**, E2E checklists per phase). Check which phase is current before adding features; deliver per the phase's build + E2E checklist.
- `.impeccable.md` — **read this before any UI/design work**. It holds the design context (users, brand, aesthetic direction, anti-references) that the `impeccable` skill requires. Do not start frontend work without it.
- `DECISIONS.md` — append-only log of significant choices made (stack, scope, sequencing, conventions). **Read it for context, and add an entry whenever you make a non-trivial decision** — don't let decisions live only in chat.
- `DEVELOPMENT.md` — dev workflow, project layout, testing, build internals. `DEPLOYMENT.md` — setup, build, run, config, API reference. `README.md` is the navigation hub.

## Layout

- `mcp-server/` — the only buildable module. Java 21 + Spring Boot 3.3.4 backend, React 18 + TypeScript + Vite SPA in `mcp-server/webui/`.
- `docs/` — pre-implementation docs; the repo was docs-first and `mcp-server/` landed in Phase 1.
- No monorepo tooling; it's a single Maven module that also builds the frontend.

## Commands (frequently used)

```sh
# build + run
cd mcp-server && mvn package && java -jar target/mcp-server.jar
# dev mode (two terminals, open http://localhost:5173)
cd mcp-server && mvn spring-boot:run -Dskip.frontend=true
cd mcp-server/webui && npm run dev
# tests + fast loops
cd mcp-server && mvn test
cd mcp-server && mvn test -Dtest=FileServiceTests#canUploadFileAndItAppearsAsAChild   # single test
cd mcp-server && mvn package -Dskip.frontend=true      # JAR only, skip SPA rebuild
cd mcp-server/webui && npm run typecheck                # tsc --noEmit (script is "typecheck", not "tsc")
```

Full command reference + project layout: [`DEVELOPMENT.md`](DEVELOPMENT.md). Setup + API + config: [`DEPLOYMENT.md`](DEPLOYMENT.md).

## Dev mode wiring (non-obvious)

Two-process dev mode is the norm, not a single process:

- Terminal 1: `mvn spring-boot:run -Dskip.frontend=true` → backend on `127.0.0.1:8080`
- Terminal 2: `cd webui && npm run dev` → Vite on `:5173`
- Open **http://localhost:5173** (not 8080). `vite.config.ts` proxies `/api/*` → `127.0.0.1:8080`. If the UI hangs on "connecting…", the backend isn't up on 8080.

## How the SPA ships inside the JAR

`mvn package` runs `frontend-maven-plugin` (installs Node/npm into `target/`, runs `npm ci` + `npm run build` in `webui/`), then `maven-resources-plugin` copies `webui/dist/` into `src/main/resources/static/` so it lands at `BOOT-INF/classes/static/` in the JAR. Spring Boot serves `index.html` as the welcome page; `WebMvcConfig` forwards unknown non-API paths to `index.html` for client-side routing. **If you skip the frontend build, there is no `static/index.html` and the UI 404s** — either run `mvn package` once, or use the Vite dev server.

## Hard constraints from the plan (don't violate)

- **`server.address=127.0.0.1` is a deliberate guardrail**, not a bug. No auth until Phase 6; the server runs on trusted internal networks only. Do not change the bind or add public exposure without implementing Phase 6.
- **No Docker / Kubernetes / virtualization.** Single runnable JAR, in-process ML inference, natively installed backing services. Revisited only in Phase 5+ if the constraint lifts.
- **Open-source-first.** Proprietary services (Microsoft Graph/Entra for Teams) are deferred to Phase 4.
- **ACL tags are captured on every chunk from Phase 2; enforcement lands in Phase 6.** Never defer capture. When adding an intake path, tag chunks before first merge.
- **Auth & access control land last (Phase 6).** Don't add Spring Security / OAuth2 / JWT / Keycloak before that phase.
- **E2E or it didn't ship.** A phase exits only when its E2E test checklist in `docs/plan.md` passes end to end.

## Backend conventions

- Package root: `com.mcpserver`. Module subpackages per `docs/product-idea.md` §4: `config`, `controllers`, `services`, `repositories`, `models`, plus `rag/{chunking,embedding,retrieval,reranker}` and `plugins/`.
- **Separate business logic from MCP protocol handling** — `services/`/`repositories/` reusable outside MCP; `mcp/` only adapts to the protocol; the Web UI is a REST channel over the same services, never a second implementation.
- The chunk store uses **embedded SQLite + sqlite-vec + FTS5** (via `org.xerial:sqlite-jdbc`, bundled native SQLite per-platform in the JAR). The sqlite-vec extension is downloaded on demand by the `SqliteVecStorePlugin` and loaded via `SELECT load_extension(...)`. FTS5 is built into SQLite. The base `chunks` table + `chunks_fts` (FTS5) are created by `schema.sql` on startup; the `chunks_vec` (vec0) virtual table is created by the plugin after extension load. `DatasourceConfig` provides a `SQLiteDataSource` with `enableLoadExtension=true` and WAL mode.
- The RAG pipeline lives in `rag/{chunking,embedding,retrieval,reranker,web}` with swappable seams (`EmbeddingClient`, `SearchPipeline`, `Reranker`, `WebFetcher`). The default embedding impl runs **nomic-embed-text-v1.5 ONNX in-process** via ONNX Runtime + DJL tokenizer — the model files live in `mcp-server/models/` (gitignored, ~131MB; downloaded via the Plugins page). Web augmentation uses a local **SearXNG** instance (native Python process on `127.0.0.1:8888`) — also managed via the Plugins page.
- **Plugins system** (`plugins/` package): `Plugin` interface, `PluginRegistry`, `PluginStateStore` (JSON file), `PluginController` (`/api/plugins`). Three plugins: `SqliteVecStorePlugin` (vector store), `NomicEmbeddingPlugin` (embedding model), `SearXngPlugin` (web search). The app boots in degraded mode when plugins aren't installed — file management works, search/ingestion return a "not ready" state.
- File nodes are still **in-memory** (`InMemoryFileRepository`) — resets on restart. Chunks persist in SQLite. On restart, orphaned chunks from deleted/re-uploaded files may remain because the file IDs reset; delete the `data/mcpserver.db` file if needed.
- Errors: `IllegalArgumentException` → 400, `IllegalStateException` → 409, handled in controller `@ExceptionHandler` methods. Follow that pattern for new controllers.

## Frontend conventions

- **TypeScript is strict** (`noUnusedLocals`, `noUnusedParameters`, `noFallthroughCasesInSwitch`). Unused imports/vars **fail the build**. The `tsc -b` in `npm run build` is a real gate.
- CSS is split into two files imported in `src/main.tsx`: `styles.css` (design tokens — OKLCH, dark theme, amber accent) and `components.css` (component styles). Use the tokens; don't hardcode colors.
- Design direction is **refined utilitarian, dark, amber accent, anti-AI-slop** (no side-stripe borders, no gradient text, no glassmorphism, no glow). See `.impeccable.md`.
- Fonts: Hanken Grotesk (UI sans) + JetBrains Mono (paths, IDs, metadata). Already loaded in `index.html`.
- Icons live in `src/icons.tsx` — inline SVG, 16px default, `currentColor`. Add new ones there rather than pulling an icon library.
- API calls go through `src/api.ts` — keep fetch logic there, not in components.

## Testing

- Backend: JUnit 5 + Spring Boot Test + AssertJ. `@SpringBootTest` in `FileServiceTests` boots the full context (including ONNX model load + SQLite) — tests take ~7s. Run a single test: `mvn test -Dtest=FileServiceTests#canUploadFileAndItAppearsAsAChild`.
- **Tests write to the SQLite DB** (`data/mcpserver.db`). Delete the file for a clean run: `rm -f mcp-server/data/mcpserver.db`.
- Frontend: no test runner configured yet. `npm run typecheck` is the only gate.
- Phase 1 adds a golden-set eval harness (`eval-harness/golden-set/`) + CI regression gate — not yet present.

## Setup prerequisites

Java 21+, Maven 3.9+, Node 20+ (built on 24), npm 10+. **No external database or services required** — the app uses embedded SQLite (bundled in the JAR via `org.xerial:sqlite-jdbc`) and downloads the sqlite-vec extension + nomic embedding model on demand via the Plugins page. SearXNG (optional, for web search) is also managed from the Plugins page.

```sh
# build + run
cd mcp-server && mvn package && java -jar target/mcp-server.jar
# open http://127.0.0.1:8080 → go to /plugins → install what you need
```
