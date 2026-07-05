# Plugins & Unified Cross-Platform Setup — Implementation Plan

> Decision locked: **embedded SQLite + sqlite-vec + FTS5** as the sole chunk store (zero install on
> macOS / Linux / Windows). Re-litigates the settled "Postgres+pgvector first" decision — recorded in
> `DECISIONS.md`. Setup is now unified: users install/run/enable plugins from a **Plugins** UI page
> instead of OS-specific manual steps.

## Plugin model (3 plugins)

| Plugin | Category | Install action | Run control | Enable/disable |
| --- | --- | --- | --- | --- |
| **Embedded vector store** (SQLite + sqlite-vec + FTS5) | Required · core storage | Auto-download sqlite-vec native lib (~1–2MB) per OS/arch; create SQLite DB + `vec0` + FTS5 tables | Always-on (in-process) | N/A — core, always on once installed |
| **Nomic embedding model** | Required · core RAG | Download `model_quantized.onnx` + `tokenizer.json` (~131MB) from HuggingFace | Always-on (lazy in JVM) | N/A — core |
| **SearXNG** | Optional · web augmentation | `python -m venv` + pip install (detects `python3`/`python`/`py`) | start/stop child process on :8888 | Yes — toggle hides the web-search affordance |

**Degraded mode** = a user who installs nothing gets **file management only** (folders, upload,
in-memory nodes); ingestion/search return a structured "install required plugins" state pointing at
`/plugins`.

## Backend changes (`mcp-server/src/main/java/com/mcpserver/`)

- **New `plugins/` package**: `Plugin` interface (`id, name, description, category, status(),
  isInstalled(), install(), isEnabled(), enable/disable, start()/stop(), health()`),
  `PluginRegistry`, `PluginStateStore` (persists to `mcp-server/plugins-state.json` — JSON, not DB,
  so it works pre-install), `PluginController` (`/api/plugins`: list+status,
  `POST /{id}/install`, `POST /{id}/{enable,disable,start,stop}`, `GET /jobs/{jobId}` progress).
  Async installs on a single-thread executor → job map the UI polls.
- **Three impls**: `SqliteVecStorePlugin`, `NomicEmbeddingPlugin`, `SearXngPlugin`.
  OS/arch detection (`os.name` + `os.arch`) picks the right sqlite-vec prebuilt
  (`vec-*.dylib`/`.so`/`.dll` for mac/linux/win × arm64/x64). Python detection for SearXNG.
- **`rag/embedding/OnnxEmbeddingClient.java:42`**: move model load out of `@PostConstruct` into a
  lazy `init()` on first `embed()`; tolerate missing model (clear "not installed" error per-call);
  add `isReady()`. `@PreDestroy close()` unchanged.
- **`repositories/ChunkRepository.java` → rewrite for SQLite**: SQLite JDBC
  (`org.xerial:sqlite-jdbc`, bundled native SQLite per-platform in-jar, FTS5 included). Swap pgvector
  `<=>` → sqlite-vec `vec_distance_cosine`; swap `to_tsquery`/`ts_rank` → FTS5 `match`/`bm25`.
  Schema: `chunks` (metadata) + `chunks_vec` (`vec0` virtual, 768-dim) + `chunks_fts` (FTS5 virtual).
- **`schema.sql`**: rewrite to SQLite dialect + `vec0` + `FTS5`. Runs on startup
  (`spring.sql.init.mode=always`), tolerates re-run.
- **`application.yml`**: datasource → `jdbc:sqlite:./data/mcpserver.db`; remove `DB_USER`/Postgres
  bits; keep `rag.*` keys.
- **`pom.xml`**: drop `org.postgresql`; add `org.xerial:sqlite-jdbc` (pin a version bundling
  SQLite ≥ 3.41 for sqlite-vec compatibility).
- **`services/SearchService.java` + `services/IngestionService.java`**: gate on
  `pluginRegistry.isReady(...)` — if a required plugin isn't installed, return a structured
  `{ "notReady": [...pluginIds] }` response instead of a 500; `IngestionService` skips embedding
  when the model isn't installed (file node still stored in-memory).
- **`controllers/SearchController.java`**: surface the not-ready state as a 200 with a
  `requiresSetup` body (not an error), so the UI can render the banner.

## Frontend changes (`mcp-server/webui/src/`)

- **`App.tsx`**: add `<Route path="/plugins" element={<PluginsPage />} />`.
- **`components/Topbar.tsx:16-23`**: add `<NavLink to="/plugins">Plugins</NavLink>`.
- **New `components/PluginsPage.tsx`**: list mirroring `FileTable`'s CSS-grid row pattern — one row
  per plugin: icon + name + category tag + status pill (Not installed / Installing / Ready /
  Running / Disabled) + actions. Install shows inline progress polling
  `GET /api/plugins/jobs/{jobId}`. Add a reusable **toggle switch** component (flat, amber-on /
  neutral-off, no glow — per `.impeccable.md`).
- **New `components/Toggle.tsx`**: the reusable switch (used for SearXNG enable/disable now;
  reusable later).
- **`icons.tsx`**: add `PuzzleIcon` (plugin), `PowerIcon` (start/stop), `DownloadIcon` (install) —
  same 24×24 / 1.5 stroke / `currentColor` convention.
- **`api.ts`**: `listPlugins()`, `installPlugin(id)`, `enablePlugin(id)`, `disablePlugin(id)`,
  `startPlugin(id)`, `stopPlugin(id)`, `getPluginJob(jobId)`, plus `Plugin`/`PluginStatus`/
  `PluginJob` types.
- **`components.css`**: plugin row grid, status pills (amber=ready/running, muted=not-installed,
  danger=error), toggle switch, inline progress bar (quiet, no spinner overlay per design
  principles).
- **`SearchPage.tsx` + `FilesPage.tsx`**: quiet inline banner when required plugins aren't ready,
  linking to `/plugins` (no modal — per anti-references).

## Docs updates (decision re-litigation)

- **`DECISIONS.md`**: new entry — "Storage switched from Postgres+pgvector to embedded
  SQLite+sqlite-vec+FTS5" with rationale (zero-install UX, cross-platform parity). Append-only per
  AGENTS.md.
- **`docs/plan.md`** Phase 1 storage line + the "settled decisions" block: update to reflect
  SQLite.
- **`AGENTS.md`**: replace the "chunk store uses PostgreSQL + pgvector" paragraph and the
  setup-prerequisites block (drop Postgres/pgvector/SearXNG manual steps → replaced by the Plugins
  page); update commands.
- **`DEVELOPMENT.md`** / **`DEPLOYMENT.md`**: swap Postgres references for SQLite; update setup +
  config sections.
- **`README.md`**: drop the OS-specific setup table rows that no longer apply; point to the Plugins
  page.
- **`SETUP-WINDOWS.md` / `SETUP-LINUX.md`**: remove (no longer needed — setup is unified into the
  Plugins page).

## Verification (per "E2E or it didn't ship")

- **Backend**: `mvn test` — `FileServiceTests` (`@SpringBootTest`) now boots against SQLite +
  auto-downloaded sqlite-vec lib; verify upload→chunk→search still passes end-to-end. Single-test
  fast loop: `mvn test -Dtest=FileServiceTests#canUploadFileAndItAppearsAsAChild`.
- **Frontend**: `npm run typecheck` (strict gate). Build: `mvn package` (frontend-maven-plugin) to
  confirm the SPA still ships in the JAR.
- **Manual E2E on a clean state** (all three OSes ideally, at least macOS now): delete
  `./data/mcpserver.db` + `models/` → `mvn spring-boot:run -Dskip.frontend=true` + `npm run dev` →
  app boots in degraded mode → `/plugins` shows 3 "Not installed" → install vector store → install
  embedding model → upload a file on `/files` → search on `/` returns cited results → install +
  enable + start SearXNG → web toggle on `/` augments results → disable SearXNG → toggle off, search
  still works → stop SearXNG process → status pill flips to "Stopped".

## Build/run changes

- `mvn package && java -jar target/mcp-server.jar` still produces one runnable JAR (SQLite is
  in-jar; only the ~1–2MB sqlite-vec lib and the 131MB model are fetched on demand from `/plugins`).
- Dev mode unchanged: two terminals, `:5173` proxies `/api/*` → `:8080`.
