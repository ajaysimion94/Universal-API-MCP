# DECISIONS.md

Append-only log of significant choices made during this project. Each entry: **what**, **why**, **when**, **status**, and where it's documented. Update this when you make a non-trivial decision (architecture, stack, scope, sequencing, conventions).

Format:

```
### YYYY-MM-DD — Title
**Decision:** ...
**Why:** ...
**Status:** active | superseded by <id> | deferred to <phase>
**Refs:** ...
```

---

### 2026-07-04 — Phase 1 starts; file manager ships first
**Decision:** Begin Phase 1 (Foundation) per `docs/plan.md`. First deliverable is a working Spring Boot 3 + Java 21 backend with a file manager UI (Files & Folders, §5.8 slice 1), ahead of the MCP SDK + golden-set harness items in the Phase 1 checklist.
**Why:** Get a runnable, exercisable app end to end early (UI + API + single JAR + SPA-in-JAR wiring) so later phases build on a live surface. The file manager is also the Method 4 ingestion path (upload → embed), so it's foundational for Phase 2 search.
**Status:** active
**Refs:** `docs/plan.md` Phase 1; `docs/product-idea.md` §5.8

### 2026-07-04 — Backend stack: Spring Boot 3.3.4, Java 21, single Maven module
**Decision:** One Maven module (`mcp-server/`) builds both the Java backend and the React SPA (via `frontend-maven-plugin` + `maven-resources-plugin`). Single runnable JAR.
**Why:** Native-only constraint (no Docker/K8s); the SPA ships inside the JAR's `static/` resources, served by Spring Boot. No separate web server process.
**Status:** active
**Refs:** `docs/product-idea.md` §3; `mcp-server/pom.xml`

### 2026-07-04 — File store is in-memory for now
**Decision:** `InMemoryFileRepository` (ConcurrentHashMap) backs the files & folders API. Resets on restart.
**Why:** Phase 1 doesn't require persistence; PostgreSQL + pgvector is a Phase 2 deliverable. Avoid persistence workarounds now — swap cleanly when Phase 2 lands.
**Status:** active (superseded when Phase 2 ships)
**Refs:** `docs/plan.md` Phase 1 / Phase 2; `AGENTS.md` "Backend conventions"

### 2026-07-04 — Folder upload is idempotent (reuse folders, skip existing files)
**Decision:** Bulk folder upload (`POST /api/files/{parentId}/upload-folder`) recreates the on-disk hierarchy under the target folder. Existing folders are reused; existing files at the same path are skipped (counted as `filesSkipped`), not overwritten or rejected.
**Why:** Re-uploading the same folder (a common user action) should be a no-op-ish refresh, not a 409 storm or a destructive overwrite. Matches "replace-with-versioning" intent without committing to full versioning yet.
**Status:** active
**Refs:** `README.md` "Folder upload"; `FileService.uploadFolder`

### 2026-07-04 — Frontend design direction: refined utilitarian, dark, amber accent, anti-AI-slop
**Decision:** Dark-only theme, OKLCH amber accent used sparingly (active/focus/primary/selection), neutrals tinted faintly warm. Fonts: Hanken Grotesk (UI) + JetBrains Mono (paths/IDs/metadata). Banned patterns: side-stripe borders, gradient text, glassmorphism, glow, decorative drop shadows.
**Why:** Enterprise dev tool used alongside IDE/terminal — should feel like it belongs with Postgres/Prometheus, not like a marketing site. Distinctive without being decorative. Picked via the `impeccable` design-context protocol.
**Status:** active
**Refs:** `.impeccable.md` (full design context + principles); `AGENTS.md` "Frontend conventions"

### 2026-07-04 — CSS split: `styles.css` (tokens) + `components.css` (components)
**Decision:** Two CSS files imported in `src/main.tsx`: `styles.css` holds OKLCH design tokens + base reset; `components.css` holds all component styles. No CSS-in-JS, no Tailwind, no preprocessor.
**Why:** Keep tokens authoritative and component styles greppable. Matches the "use the tokens, don't hardcode colors" rule.
**Status:** active
**Refs:** `mcp-server/webui/src/styles.css`, `components.css`

### 2026-07-04 — Single source locations for icons and API calls
**Decision:** All inline SVG icons live in `src/icons.tsx` (16px default, `currentColor`, no icon library). All fetch/API logic lives in `src/api.ts` (components call functions, never `fetch` directly).
**Why:** One place to find/update icons and one place for API wiring (so the Vite proxy, error handling, and types stay consistent).
**Status:** active
**Refs:** `AGENTS.md` "Frontend conventions"

### 2026-07-04 — Search system: Postgres + pgvector + nomic ONNX, pass-through reranker, search as landing page
**Decision:** Build the Phase 2 search system plan-faithfully. Vector + lexical storage on **PostgreSQL + pgvector** (chunks table: `vector(768)` HNSW index + `tsvector` lexical leg, merged via RRF). Embeddings via **nomic-embed-text-v1.5 ONNX in-process** (ONNX Runtime + DJL tokenizer inside the JVM, 768-dim). Reranker is a **pass-through seam now** (keeps RRF order); bge-reranker ONNX wires in next. Web UI: **universal search is the landing route `/`**; files & folders moves to `/files`. `#keyword` parser seam built now (no tools registered until Phase 3).
**Why:** Plan §5.6/§5.7/§5.8 + Phase 2 specify exactly this stack. In-process ONNX honors the no-sidecar/no-Docker constraint. Capturing ACL tags now keeps the Phase 6 enforcement cutover cheap. Search is the headline surface in §5.8, so it's the landing page.
**Status:** active
**Refs:** `docs/plan.md` Phase 2; `docs/product-idea.md` §5.6, §5.7, §5.8; `AGENTS.md` "Hard constraints"

### 2026-07-04 — Direct JDBC (not JPA) for the chunk store
**Decision:** Access pgvector via Spring `JdbcTemplate` (plain JDBC), not Spring Data JPA.
**Why:** Plan §3 specifies "direct JDBC to pgvector". JPA has no native support for pgvector's `vector` type; JDBC with explicit SQL keeps vector ops and `tsvector` leg transparent and matches the plan.
**Status:** active
**Refs:** `docs/product-idea.md` §3

### 2026-07-04 — Text extraction: Tika for all binary formats (PDF, Word, Excel, PowerPoint)
**Decision:** `IngestionService.extractText()` handles text-based MIME types directly (txt, md, csv, json, xml, yaml, html with tag stripping) and uses **Apache Tika** (`tika-core` + `tika-parsers-standard-package` 2.9.4) for all binary formats — PDF, Word (.docx/.doc), Excel (.xlsx/.xls), PowerPoint (.pptx/.ppt), RTF, OpenOffice, etc.
**Why:** Plan §5.7 specifies Tika for Office/PDF extraction. Tika is the standard Java text-extraction library covering 1000+ file formats via a single `parseToString()` call. The earlier "text-only, Tika staged" decision is now superseded — Tika is wired in and verified with real PDF and DOCX files.
**Status:** active (supersedes the earlier "text-only, Tika staged" decision)
**Refs:** `docs/product-idea.md` §5.7; `IngestionService.extractText()`; `pom.xml` tika dependencies

### 2026-07-04 — Reranker is pass-through; bge-reranker staged
**Decision:** `PassThroughReranker` preserves RRF order with synthetic scores. The `Reranker` seam is in place; bge-reranker ONNX cross-encoder wires in as a follow-up.
**Why:** Reranking is a quality lift, not required for the pipeline to function. Sensible to stage: get hybrid search working, then add the cross-encoder. Avoids an extra model download + slower per-query inference in the first cut.
**Status:** active (bge-reranker to be wired in as a follow-up)
**Refs:** `docs/plan.md` Phase 2; `rag/reranker/Reranker.java`

### 2026-07-04 — Web search toggle: SearXNG (native, open-source) + in-memory per query
**Decision:** Add a "Web" toggle on the search page. When on, the search is augmented with live web content via a local **SearXNG** instance (open-source meta-search, native Python process on `127.0.0.1:8888`). Flow: SearXNG JSON API → top-N URLs → HTTP GET each page → Tika extract → chunk → embed **in-memory** (not persisted to pgvector) → cosine-score against query → merge into results tagged `sourceKind="web"` with the URL as provenance. The `WebFetcher` seam is swappable (DuckDuckGo/Brave/Tavily can plug in later).
**Why:** User-requested feature. SearXNG is the most plan-faithful choice: open-source, self-hosted, native process (no Docker), aggregates Google/Bing/DuckDuckGo. In-memory-only keeps the local index clean and avoids storing copyrighted web content. The seam keeps it provider-agnostic.
**Status:** active
**Refs:** `rag/web/WebFetcher.java`, `SearXngWebFetcher.java`; `SearchService.fetchWebResults()`; `SearchController` `web` param

### 2026-07-05 — Storage switched from Postgres+pgvector to embedded SQLite+sqlite-vec+FTS5
**Decision:** Replace PostgreSQL + pgvector with **embedded SQLite + sqlite-vec + FTS5** as the sole chunk store. The sqlite-vec native extension (~1–2MB) and the nomic embedding model (~131MB) are downloaded on demand via a **Plugins** UI page (`/plugins`). SearXNG is also managed from the same page. The app boots in degraded mode (file management only) when plugins aren't installed — no manual setup required.
**Why:** Zero-install UX across macOS / Linux / Windows. Eliminates the Postgres + pgvector build-from-source pain (especially on Windows where MSVC is needed). SQLite JDBC bundles native SQLite per-platform in the JAR; FTS5 is built in; sqlite-vec is a small loadable extension. The Plugins page gives users explicit control over what to install/run/enable.
**Re-litigates:** The settled "PostgreSQL + pgvector first (HNSW)" decision from plan.md. Scale-up path: sqlite-vec HNSW for moderate corpora; swap the store seam to pgvector if chunks exceed ~50k.
**Status:** active (supersedes the Postgres+pgvector storage decision)
**Refs:** `docs/plugins-plan.md`; `DECISIONS.md`; `AGENTS.md`; `config/DatasourceConfig.java`; `repositories/ChunkRepository.java`; `plugins/` package
