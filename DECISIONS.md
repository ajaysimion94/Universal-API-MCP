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

### 2026-07-06 — SQLite uses one shared connection so sqlite-vec stays loaded
**Decision:** Use Spring's `SingleConnectionDataSource` for embedded SQLite instead of returning fresh connections from `SQLiteDataSource`.
**Why:** SQLite loadable extensions are connection-scoped. The sqlite-vec plugin can load `vec0` successfully at startup, but later ingestion/search calls on a different connection see `no such module: vec0`. A single shared embedded connection keeps the extension active across plugin init, chunk writes, and search.
**Status:** active
**Refs:** `config/DatasourceConfig.java`; `repositories/ChunkRepository.java`; `plugins/SqliteVecStorePlugin.java`

### 2026-07-12 — Durable event queue for connectors is a SQLite table, not Postgres outbox
**Decision:** The Confluence/Jira ingestion connectors' durable event queue (webhook intake) is the `ingestion_events` SQLite table, claimed by a single background worker (`EventQueueWorker`) — not the Postgres outbox / `FOR UPDATE SKIP LOCKED` design in the original plan. Claiming is a plain two-step `SELECT` + `UPDATE` (no `SKIP LOCKED` needed) because exactly one worker thread claims events in this process. On startup, any row left `PROCESSING` by a crash is reset to `PENDING` and retried, satisfying "kill workers mid-burst, restart → no events lost" without a second datastore.
**Why:** The 2026-07-05 SQLite pivot already eliminated Postgres from this app for the zero-install goal; introducing Postgres solely to host an outbox table would contradict that goal. `docs/plan.md`'s "settled decisions" block still listed "Postgres-outbox-first eventing" — that line was stale and is corrected by this entry.
**Status:** active (supersedes the Postgres-outbox eventing decision inherited from the original plan)
**Refs:** `docs/plan.md` Phase 2 / "settled decisions"; `connectors/IngestionEventRepository.java`; `connectors/EventQueueWorker.java`; `schema.sql`

### 2026-07-12 — Connection credentials encrypted with a local AES-GCM file key, not Vault/KMS
**Decision:** Confluence/Jira connection credentials (username + password/API-token) are encrypted at rest with AES-256-GCM, keyed by a 256-bit key generated on first use and stored at `./data/connections.key` (owner-only file permissions where the OS supports it). No Vault/KMS integration.
**Why:** This is the first credential-storage need anywhere in the app; there is no external secrets service yet, and the app remains pre-Phase-6 (no auth, trusted-network-only, single-JAR, no Docker/K8s). A local encryption key adequately protects against casual disclosure of the SQLite file (e.g. an accidental backup or repo commit) without requiring an external dependency the "no external services" goal (`docs/plugins-plan.md`) explicitly avoids. Vault/KMS is the documented Phase 5 ("Secret management productionized") upgrade path — revisit there.
**Status:** active (adequate pre-Phase-6; revisit at Phase 5)
**Refs:** `docs/plan.md` Phase 5 "Secret management productionized"; `connectors/CredentialCipher.java`

### 2026-07-12 — Confluence/Jira ACL tags are connection/space/project-level, not per-user group resolution
**Decision:** Confluence/Jira chunks are tagged `connection:{connectionId}` plus `confluence:space:{spaceKey}` or `jira:project:{projectKey}`, plus the connection's admin-set `acl_scope` default. No per-user Confluence/Jira group-membership or permission-scheme resolution is performed at ingestion time.
**Why:** `product-idea.md` only specifies precise ACL derivation for SharePoint (site permission groups + item-level overrides); Confluence/Jira just says "derived from the source system" with no further detail. Full group-membership resolution requires querying each source system's permission APIs per user, which is Phase 6 enforcement-design territory (`acl_tags && userAclTags` filtering isn't switched on until then) — building it now would be speculative. This satisfies "ACL capture is never deferred" without over-building ahead of the enforcement design.
**Status:** active (revisit when Phase 6 designs enforcement — may need finer-grained tags then)
**Refs:** `docs/plan.md` Phase 2 ACL capture rule, Phase 6; `docs/product-idea.md` §6.1; connector `backfill()`/`pollDelta()` implementations

### 2026-07-13 — Jira connector auto-falls-back between search API generations for version compatibility
**Decision:** `JiraConnector` tries `POST /rest/api/3/search/jql` (cursor pagination via `nextPageToken`, explicit `fields` array) first for every issue search; on HTTP 404/410 it falls back to the classic `GET /rest/api/2/search` (offset pagination via `startAt`). The result is cached per connection (`modernSearchSupported`) so only the first call per connection pays for a failed probe.
**Why:** Cross-checked against current Atlassian community reports and third-party (Adaptavist) deprecation docs — not primary Atlassian reference pages, which failed to load via WebFetch — that Atlassian retired the classic `GET/POST /rest/api/2|3/search` endpoints on **Jira Cloud** in 2025 (now 410 Gone there), while **Server/Data Center** (not on Atlassian's forced migration schedule) still generally only exposes the classic endpoint. That part is reasonably well corroborated by multiple independent secondary sources. The exact request/response shape of the replacement endpoint is much less certain — sourced from community forum paraphrasing (some of those same threads report the new endpoint behaving inconsistently), not a verified primary spec, and never tested against a real Jira instance. Because of that uncertainty, the fallback also treats an HTTP 400 (malformed request — i.e. the assumed shape being wrong) as "try the classic endpoint" **on the first probe for a connection only**; once modern is confirmed working, a later 400 surfaces as a real error instead of being silently swallowed.
**Status:** active — the endpoint-deprecation fact is well corroborated; the modern endpoint's exact request/response shape is unverified against a real instance and is the main residual risk.
**Refs:** `connectors/JiraConnector.java` (`searchIssues`, `postSearchJql`, `getLegacySearch`); `JiraConnectorTests#backfillUsesModernSearchEndpointWhenAvailable`, `#fallsBackToLegacySearchWhenModernEndpointReturns410Gone`, `#fallsBackToLegacySearchOn400OnFirstProbeOnly`, `#doesNotFallBackOn400OnceModernIsConfirmedWorking`

### 2026-07-13 — Confluence v1 content API left as-is; version-compatibility risk documented, not fixed
**Decision:** `ConfluenceConnector` still only uses REST API v1 (`/rest/api/content`, `/rest/api/content/search`). No v1/v2 fallback was built, unlike the Jira search migration above.
**Why:** Checked current Atlassian sources: Confluence's v1 content-API deprecation deadline has been pushed multiple times (Feb 2024 → Nov 2024 → Mar 2025 → Apr 2025) with no confirmed final shutdown found, and Atlassian states not all v1 endpoints have v2 equivalents — unlike Jira's search API, this is not a confirmed-broken case. Building a v1/v2 fallback here would also be substantially larger than the Jira fix: Confluence API v2 has a different resource/ID model and body representation (not just different pagination), so it isn't a small addition. Speculatively building it now, against an unconfirmed and possibly-still-extended deprecation, would be premature; the Cloud-vs-Server base-path split (`/wiki/rest/api` vs `/rest/api`) this connector relies on for deployment detection was independently confirmed still accurate.
**Status:** active — residual risk, not a known defect. Revisit if Atlassian confirms a firm v1 shutdown date, or if a real Confluence Cloud connection starts failing on content endpoints.
**Refs:** `connectors/ConfluenceConnector.java`

### 2026-07-13 — Zero-code API onboarding (Postman/OpenAPI → tools) pulled forward from Phase 3/4
**Decision:** Implemented §8 zero-code tool onboarding now, as a fourth connection type `API_COLLECTION` plus a new `com.mcpserver.tools` package: import a Postman v2.1 collection or OpenAPI 3.x spec (file upload or URL — Swagger UI page URLs are resolved to the underlying spec via a multi-strategy ladder), one tool per request named `{app_slug}_{request_slug}`, auto-categorized (Postman folders / OpenAPI tags), JSON Schema generated per endpoint. GET tools auto-enable on import; state-changing tools start `pending` until approved in the Connections UI (a middle path between §8's all-pending and zero-friction). Tools are invocable three ways off one executor: `POST /api/tools/{id}/invoke`, the search bar `#`/`@` grammar, and MCP. GET tools can be flagged as knowledge sources — invoked on the existing `ConnectionPollingScheduler` cadence (or the connection's Backfill button) and ingested through `IngestionService` with ACL tags `connection:{id}` + `api:{appSlug}`. This pulls the Phase 3 `#keyword` grammar (extended with `@app` scoping plus inline JSON/XML argument payloads) and the Phase 4 import wizard forward; the Phase 2 `SearchController` `#` stub is now real. Writes invoked from search NEVER execute directly — they return a preview card and the UI's Run button calls the invoke endpoint (§5.8/§7.2 preview→approve, without the §7.2 token machinery, which stays in Phase 3).
**Why:** User-requested feature. The §8 spec was already fully written; the connectors subsystem, ingestion pipeline, and search stub were all built with this exact seam in mind.
**Status:** active
**Refs:** `docs/product-idea.md` §5.8/§8; `tools/` package; `connectors/ApiCollectionConnector.java`; `controllers/SearchController.handleToolQuery`; `webui/src/components/ToolFormPanel.tsx`/`ToolConfirmPanel.tsx`/`ToolResultPanel.tsx`

### 2026-07-13 — Spec parsers are hand-rolled Jackson tree-walkers, not swagger-parser
**Decision:** `PostmanCollectionParser` and `OpenApiParser` are hand-written `JsonNode` walkers producing a shared `ApiToolDefinition` IR; the only new dependency is `jackson-dataformat-yaml` (Boot BOM-managed) for YAML specs. Local `$ref`s resolve with a depth-10 cycle guard; external refs degrade to `{"type":"object"}`. `SchemaValidator` is likewise hand-rolled for exactly the subset we generate (type/required/enum).
**Why:** swagger-parser would add ~10MB of transitive jars to an already-huge jar (Tika + ONNX) and its full-fidelity model fights the Postman side, which has no maintained Java parser anyway — a Jackson walker was being written regardless. We control schema generation, so a full draft-07 validator buys nothing; violations must be structured (§8 self-correction) either way. Swagger-UI-page URL resolution is the flakiest part (every deployment differs) — mitigated by the well-known-paths ladder and the always-offered file-upload fallback.
**Status:** active
**Refs:** `tools/PostmanCollectionParser.java`, `OpenApiParser.java`, `SpecFetcher.java`, `SchemaValidator.java`; parser fixtures under `src/test/resources/specs/`

### 2026-07-13 — MCP protocol endpoint: core Java SDK + servlet Streamable HTTP, no Spring AI
**Decision:** The app finally has a real MCP endpoint: `io.modelcontextprotocol.sdk:mcp-core` 2.0.0 + `mcp-json-jackson2` (keeps the SDK on Boot's Jackson 2 — the `mcp` aggregator would pull Jackson 3), with `HttpServletStreamableServerTransportProvider` (ships inside mcp-core; the separate `server-servlet` artifact died at 0.18.x) registered via `ServletRegistrationBean` at `/mcp` on the app's own Tomcat — inheriting the `127.0.0.1` bind guardrail, no second server, no Spring AI (`mcp-spring-webmvc` couples to Spring AI's release train). SDK touch-surface is exactly two classes: `McpServerConfig` (bootstrap) and `McpToolBridge` (a pure `ToolsChangedEvent` listener doing addTool/removeTool + notifyToolsListChanged) — if the SDK breaks, only MCP exposure degrades; REST and search invocation are independent. The bridge also registers the built-in `search-knowledge-base` tool wrapping `SearchPipeline` (the Phase 1 catalogue stub, retired). MCP tool calls re-read the tool row at call time so a disable between registration and invocation can't execute a stale tool. Verified live over raw JSON-RPC: initialize → tools/list → tools/call, runtime list mutation on enable/disable, and the SDK's own inputSchema validation layering on top of ours.
**Why:** "Usable via MCP" was half the point of the feature, and the Java MCP SDK integration was a Phase 1 deliverable deferred at kickoff. The 2.0.0 module split (empty `mcp` aggregator, transports inside mcp-core, BOM not managing `server-servlet`) was discovered by resolving and `javap`-ing the actual artifacts — worth recording since it contradicts most online examples.
**Status:** active
**Refs:** `docs/product-idea.md` §3/§5.3, `docs/plan.md` Phase 1; `mcp/McpServerConfig.java`, `mcp/McpToolBridge.java`; `pom.xml` mcp-bom import

### 2026-07-13 — API connection auth v1: NONE/BASIC/BEARER/API_KEY_HEADER, connection-level
**Decision:** API_COLLECTION connections support four auth modes, stored with the existing `CredentialCipher` exactly like Atlassian credentials: NONE, BASIC, BEARER (`Authorization: Bearer`), and API_KEY_HEADER (header name in the `auth_username` column, key in `auth_secret_encrypted` — no schema change). Postman `auth` blocks / OpenAPI `securitySchemes` are not auto-imported; the admin picks the mode and supplies the credential. OAuth2 flows stay a reserved enum value. "Test connection" for this type means fetch+parse+import the spec (a parse failure is the connection error); the base-URL probe is best-effort only since many APIs 404 at "/". Sandbox-lite per §9: a tool's URL is always `connection.baseUrl + template` (host allowlist is structural, plus an assert), per-tool in-memory rate limit (`tools.rate-limit-per-minute`, default 10), 1MB response cap.
**Why:** These four modes cover the overwhelming majority of real Postman/OpenAPI auth declarations; auto-importing security schemes adds parsing surface without removing the need for the admin to type the actual secret. Full §9 sandboxing (process isolation) is out of scope pre-Phase-6.
**Status:** active (revisit auth auto-detection and OAuth2 with Phase 4 proper)
**Refs:** `connectors/AuthMode.java`; `tools/ApiToolExecutor.java` (applyAuth, guardrails); `connectors/ApiCollectionConnector.testConnection`

### 2026-07-18 — Custom tool groups live in the tools package; group slugs share the `@` namespace with app slugs
**Decision:** Added user-defined **custom groups** on top of the existing `API_COLLECTION` connector (no new import path — Postman/OpenAPI import remains the "connect any app" mechanism). Two new tables: `tool_groups` (id, unique slug, name, description) and `tool_group_members` (member_type `APP` → connections.id | `TOOL` → api_tools.id; no DB FKs, membership cleanup is done in code on connection/tool delete). A group can hold whole apps and/or individual endpoints across apps. Group powers: (1) browse/filter the new `/apps` directory page, (2) batch enable/disable of every endpoint in the group (loops the existing `ApiToolService.setEnabled`, so `ToolsChangedEvent` drives MCP registration as usual), (3) group slugs are resolvable in the `@` position of the search grammar (`@group #action "args"`) via a fallback in `SearchController.handleToolQuery` — **app slugs win on collision** (existing apps keep working; group resolution only kicks in when no tools exist for the slug). Group slugs are stable across renames (renaming only changes the display name, so saved queries don't break). Group CRUD lives in `com.mcpserver.tools` (`ToolGroup`, `ToolGroupRepository`, `ToolGroupService`, `ToolGroupController` at `/api/groups`) because groups span tools and connections and are not a source connector.
**Why:** User-requested feature. The directory page composes existing `listConnections` + `listTools` data client-side — no aggregate endpoint was needed. Groups deliberately do NOT carry ACL semantics or MCP-level filtering (Phase 6 territory); their only MCP effect is via batch enable/disable. No built-in catalog of popular apps was shipped — apps come only from user-imported specs (explicit user choice).
**Status:** active
**Refs:** `tools/ToolGroup*.java`; `controllers/SearchController.java` (group fallback in `handleToolQuery`); `webui/src/components/AppsPage.tsx`; `schema.sql` (`tool_groups`, `tool_group_members`)

### 2026-07-25 — Search page became a Chat page (client-side history); in-process LLM answer synthesis prototyped, then explicitly declined
**Decision:** The Web UI's Search page is now a Chat page: turn-based conversation, history kept **client-side only** (`localStorage`, capped ~50 turns, no new DB table, no cross-device sync) so prior turns stay visible instead of being replaced on each new query. `#`/`@` tool invocations are unaffected, still the pure deterministic action path through `SearchController`. Plain-text messages return cited retrieval results exactly as before — **no server-side answer generation**, keeping `docs/product-idea.md` §1/§5.6/§2.1's "no answer text is generated by the server" principle intact and unmodified.
**Why:** Along the way, a full in-process LLM answer-synthesis path was built and verified working end-to-end (`io.github.inference4j:inference4j-core`, `OnnxTextGenerator.qwen2()` — Qwen2.5-1.5B-Instruct, ~3GB, downloaded on demand via a plugin, no API key) — including a real installed-model, real-generated-answer test. The user then explicitly asked for it to be removed entirely and for the original "no answer text generated by the server" principle to stand everywhere, not just on the MCP path. Recorded here (rather than left as if never attempted) so a future agent doesn't re-propose the same direction without knowing it was tried and declined, and to note the resource cost observed in practice: running the ~3-4GB model alongside an already-running app instance pushed the host to the edge of available memory and left that instance briefly unresponsive.
**Status:** active (chat/history stands; LLM synthesis stands rejected)
**Refs:** `webui/src/components/ChatPage.tsx`; `controllers/SearchController.java`

### 2026-07-26 — Absorb the `.filter` DSL from Postman-excel-report-automation as RQL; redesign the grammar rather than port it
**Decision:** The `.filter` report DSL from the separate `Postman-excel-report-automation` CLI (Java 17, ~6,000 lines in `filter/`) will be brought into this server as a new **RQL** (Report Query Language) subsystem under `com.mcpserver.reports`, with a **breaking grammar redesign** rather than a straight port. Design is written up in `docs/report-query-design.md`; **no code has been written** — this entry records the design decision only. Three parts: (1) *Language* — replace the statement-per-verb grammar and its §7 "target matrix" with a uniform **dataset/pipeline** model (`let x = request "…" |> where … |> select …`), where every stage accepts every dataset. (2) *Fault tolerance* — the parser **never throws**; it returns `ParseResult(Program, List<Diagnostic>)` with `Error*` nodes in the tree and two resync levels (`;` for statements, `|>` for stages), and every node carries a `Span`. Diagnostics become structured records with stable codes (`RQL0xx`–`RQL3xx`), severity, and machine-applicable `Fix`es. (3) *IDE* — a `/reports` SPA page with **CodeMirror 6** wired to a pure `POST /api/reports/analyze` endpoint (diagnostics + completions + symbols, no execution), plus observed-response **schema inference** cached per tool to make field-name completion possible. Execution reuses the existing async job pattern and adds a first-class `PARTIAL` terminal state. Only the *language* and the *POI Excel renderer* (2,643 ln) are ported; the old repo's `http/`, `postman/`, `auth/`, and `cli/` packages are **dropped** in favour of `tools.ApiToolExecutor`, `tools.PostmanCollectionParser`, and `connectors.CredentialCipher`, which already exist here and are stronger.
**Why:** The old query system's blockers are structural, not cosmetic: `FilterQueryParser.error()` throws on the first bad token (one typo blanks all editor feedback — fatal for live editing), diagnostics are formatted strings with no end position or severity, and the arbitrary target matrix is the direct cause of four of the ten rows in the old guide's own "Common Mistakes" table (`FILTER` can't target a union, `COLUMNS` can't target a lookup table, `EXPAND` has no `*`, and `JOIN` doesn't exist despite the runtime supporting it). A uniform dataset model deletes all four by construction. Pipelines over nested SQL were chosen specifically for IDE affordance — after `|>` the legal-token set is small and closed, which is the precondition for good completion — and because `|>` doubles as a free error-recovery resync point. Integration cost is unusually low: same language/build (Java 17→21 is source-compatible), and **`poi-ooxml:5.4.1` is already on the classpath transitively via `tika-parsers-standard-package`** (verified with `mvn dependency:tree`), so Excel output adds zero backend dependencies. CodeMirror 6 (~200KB) would be the SPA's first substantial UI dependency; Monaco was rejected at 2MB+. Accepted costs: (a) existing `.filter` files break — mitigated by hand-translating the 13 files in the old repo's `filters/` into a **workbook-equivalence migration corpus**, which is the only credible proof no semantics were lost; (b) **this is ahead of the tracker** — `docs/plan.md` still has Phases 1–2 open and report generation is Phase 3/4 work (closest to Phase 4's "an unseen API onboards by file"), so the sequencing is a deliberate choice, not an oversight.
**Status:** proposed (design only — not implemented, not scheduled)
**Refs:** `docs/report-query-design.md`; source project `/Users/ajaysimion/Documents/Development/Automation/Postman-excel-report-automation` (`docs/FILTER_GUIDE.md` §7/§10/§11, `filter/FilterQueryParser.java:1188`); `tools/ApiToolExecutor.java`; `tools/PostmanCollectionParser.java`

### 2026-07-26 — Dashboards (RQD) are the primary target, not Excel; chart anti-patterns made unrepresentable in the language
**Decision:** The report subsystem's primary output becomes an **interactive dashboard**, not a workbook. New document type `.rqd` — YAML front matter (title, connection, typed `params`) + markdown prose + fenced ` ```rql ` query blocks + component tags (`<BarChart data={…} x=… y=… />`) + `{{ expr }}` interpolation — i.e. the Evidence.dev/Observable "markdown and SQL mixed" genre, which is what the user's "HTML and SQL mixed" describes. Design in `docs/dashboard-design.md`; **no code written**. RQL (`docs/report-query-design.md`) stays presentation-free as the substrate so two renderers sit on one language, and **Excel is demoted to a projection** of a dashboard (charts → native POI charts where the form allows, otherwise the table twin — never a rasterized image, since a headless renderer would violate the no-Docker constraint). Four sub-decisions: (1) **Guardrails are structural, not documentation** — the grammar has no `y2` prop (dual-axis unrepresentable), no `color="#hex"` prop (color requested by role only: categorical/sequential/diverging/status), `<Filter>` is invalid inside a chart (per-chart filters unrepresentable), every chart auto-emits a non-optional accessible table twin, categorical series auto-fold to "Other" past 8, and scatter/bubble/small-multiples hard-**error** past 3 series; the rest are analyzer lints (`RQD3xx`) such as one-bar-bar-chart → use `<Stat>`. (2) **Client-side React with hand-rolled SVG, no charting library** — Recharts/visx/Plotly would be the largest thing in a bundle with 3 runtime deps and would fight every mark spec; CodeMirror stays the only substantial new frontend dependency. (3) The categorical palette is **computed and validated, not chosen** (see Why). (4) **Raw HTML in `.rqd` is rejected** — markdown + known component tags only; raw HTML buys a literal reading of "HTML mixed" at the cost of an XSS surface and a sanitizer to maintain, which becomes a real vulnerability the moment dashboards are shareable.
**Why:** A dashboard *builder* lets users make misleading charts and hopes they don't; a dashboard *language* can make the bad chart impossible to express — that asymmetry is the whole argument for doing this as a DSL rather than a drag-and-drop UI. Dual-axis is banned because two arbitrary y-scales invent a correlation absent from the data; slot assignment hashes the **series key** rather than the row index so filtering never repaints survivors ("Acme is blue" stays true). On color, the palette was **validated with `dataviz/scripts/validate_palette.js` against this project's real chart surface** — `--bg-surface` `oklch(0.235 0.01 75)` = `#211d19`, not the reference `#1a1a19` — and the 8 dark steps (`#3987e5,#d95926,#199e70,#c98500,#d55181,#008300,#9085e9,#e66767`) pass all six checks (worst adjacent CVD ΔE 8.4 protan, normal-vision 19.3, all ≥3:1). Two project-specific findings came out of running it rather than reasoning: (a) only the **first three** slots clear the all-pairs test (ΔE 9.4), which is what sets the hard scatter cap at 3 — no reordering fixes it; (b) the brand amber `#f5a33a` is **L 0.78 (above the dark band's 0.67 ceiling) and only ΔE 11.1 from categorical slot 4 — below the hard normal-vision floor of 15**, so amber is *reserved* for `<Emphasis>` (where all other series are gray, making collision impossible), selection, hover, and focus, and is never a series slot. That independently confirms `.impeccable.md` principle 3 ("one accent, used like punctuation") by measurement rather than taste. Slot order is itself the CVD-safety mechanism and must not be changed without re-running the validator.
**Status:** proposed (design only — not implemented, not scheduled)
**Refs:** `docs/dashboard-design.md`; `docs/report-query-design.md` §1.4; `.impeccable.md` (principle 3, anti-references); `webui/src/styles.css` (`--bg-surface`, `--accent`; gains `--series-1…8`)

### 2026-07-27 — Chat uses multi-conversation history and progressive evidence disclosure

**Decision:** The Web UI keeps up to 25 independently selectable conversations in `localStorage`, capped at 50 turns each. Each conversation derives its title from the first user message, and appears in a persistent desktop history rail or compact-screen history panel. RAG and web evidence stays collapsed under every answer—including error fallbacks—with distinct document/result counts; expanding the evidence reveals the source list, and expanding an individual RAG file reveals its matching chunks. Consumed topbar query parameters are removed after submission so refreshing the page never resubmits the last prompt.

**Why:** A flat transcript did not behave like a chat product, and expanded retrieval cards made the evidence visually louder than the answer. Client-side conversations add useful continuity without introducing the Phase-6 identity/ACL work needed for server-side history. Progressive disclosure keeps citations accessible while making the conversation the primary reading surface.

**Status:** active

**Refs:** `webui/src/components/ChatPage.tsx`; `webui/src/components.css`; `docs/plan.md`

### 2026-07-27 — An external consumer-chat answer connector was built, then removed

**Decision:** A connector that answered Chat messages through an external consumer chat service was
built and then removed in full — client, browser-profile session handoff, captured credentials,
its plugin, and the `POST /api/chat` endpoint. No code, configuration key, or dependency for it
remains. The Chat page is a persistent client-side conversation whose plain-text messages use the
RAG search endpoint and render cited sources directly; `#`/`@` tool invocation is unchanged.

**Why:** Recorded so the direction is not re-proposed without knowing it was tried. Two things made
it untenable: the upstream surface was undocumented and unsupported for automation, so a captured
session could report success while the chat socket rejected it; and prompts plus retrieved excerpts
had to leave the device for a third party. Keeping retrieval local avoids both and leaves a clean
seam for a supported answer provider later. The earlier in-process LLM alternative was declined
separately for its ~3-4GB memory cost (see the 2026-07-25 entry above).

**Status:** active; no external answer provider is wired in.

**Refs:** `controllers/SearchController.java`; `DEPLOYMENT.md`

### 2026-07-27 — API dashboard vertical slice: RQL + `.rqd`

**Decision:** Deliver an early, read-only dashboard slice ahead of the Phase 4 report/export track.
RQL evaluates enabled GET tools only through `ApiToolExecutor`; `.rqd` documents pair front matter
and fenced RQL with a deliberately constrained component set (`Stat`, `BarChart`, `DataTable`).

**Why:** It makes imported API data explorable now without bypassing the existing credential,
validation, host-pinning, response-cap, or rate-limit protections. A constrained renderer also makes
dual axes and author-defined series colors unrepresentable from the outset.

**Status:** active; saved dashboard definitions, joins/lookups, broader chart catalog,
observed-schema persistence, and Excel jobs remain follow-up work.

**Refs:** `docs/report-query-design.md`; `docs/dashboard-design.md`;
`mcp-server/src/main/java/com/mcpserver/reports`; `mcp-server/src/main/java/com/mcpserver/dashboards`

### 2026-07-27 — Shared Guide catalogue for people and MCP clients

**Decision:** Maintain the short operational guide in a server-side catalogue and project it to the
Web UI through `/api/guides` and to MCP clients as Markdown/JSON resources plus reusable prompts.
Longer setup and integration detail remains versioned in `docs/developer-guide.md` and
`docs/mcp-client-guide.md`.

**Why:** The critical grounding and approval rules must not drift between a person using the app and an
LLM client using the same services. Protocol-native resources and prompts let any compatible client
discover the workflow without vendor-specific hard-coded instructions.

**Status:** active

**Refs:** `guides/GuideCatalog.java`; `controllers/GuideController.java`;
`mcp/McpGuideBridge.java`; `docs/developer-guide.md`; `docs/mcp-client-guide.md`

### 2026-07-27 — Summary preparation exports an explicit local RAG scope

**Decision:** Add a Chat-page source picker that selects uploaded files and connected apps, then
creates a local TXT export from only their already-indexed RAG chunks. Connector selections resolve
through the existing `connectionId:externalId` chunk namespace; uploads resolve by exact file id.
Exports include source/path/system/URL provenance, are capped at 25 MB, and do not contact any external
another answer provider.

**Why:** A single “export everything” action would make accidental disclosure easy and would obscure
which source produced each passage. Explicit scope, a local artifact, and a size boundary create an
auditable preparation step while preserving the decision to remove the unsupported external
connector. A supported answer provider or separately approved browser handoff can consume the artifact
later without coupling export correctness to third-party UI automation.

**Status:** active; automated summarization remains a follow-up.

**Refs:** `summaries/SummaryExportService.java`; `summaries/SummaryExportController.java`;
`webui/src/components/SummarySourceDialog.tsx`; `webui/src/components/ChatPage.tsx`

### 2026-07-27 — Compile and ship against the Java 17 baseline

**Decision:** Compile the backend with Maven `--release 17` by setting `java.version` to `17`.
JDK 17 is the minimum supported build and runtime; non-LTS Java 20 and LTS Java 21 or newer can build
and run the same Java 17-targeted JAR.

**Why:** The implementation uses no APIs or language features newer than Java 17, and Spring Boot
3.3 supports Java 17. A single lowest-common-denominator artifact avoids maintaining per-JDK builds
while supporting environments already standardized on Java 17, 20, or 21.

**Status:** active

**Refs:** `mcp-server/pom.xml`; `DEPLOYMENT.md`; `README.md`

### 2026-07-27 — A connected source has completed its initial sync; legacy Swagger imports are supported

**Decision:** Creating a Confluence, Jira, or GitHub connection now performs its first historical
backfill inside the initial asynchronous verification job and only then changes the status to
`CONNECTED`. Credential re-tests and re-enables remain lightweight and do not force another full
backfill. API definition imports now accept Swagger 2.0 alongside OpenAPI 3.x, and Postman parsing
accepts metadata-light v2 exports and resolves collection variables before deriving the base URL or
normalizing endpoint paths.

**Why:** “Connected” previously meant only that credentials were valid, so a newly added Confluence
source could show green while still containing no searchable content until the separate Backfill
button was discovered. Real `swagger.json` files are frequently Swagger 2.0, and real Postman
collections often compose their host from collection variables; both valid inputs fell outside the
small original fixtures.

**Status:** active

**Refs:** `connectors/ConnectionService.java`; `tools/OpenApiParser.java`;
`tools/PostmanCollectionParser.java`; parser fixtures under `src/test/resources/specs/`

### 2026-07-28 — User documentation is split by task, and the reference documents shipped behaviour only

**Decision:** User-facing documentation is three documents with distinct jobs: `docs/user-guide.md`
(every application process end to end), `docs/query-language-reference.md` (RQL/RQD grammar,
diagnostic codes, endpoint contracts), and `docs/reports-and-dashboards-tutorial.md` (hands-on
build). The reference describes only what the implementation does and carries an explicit
"Current limits" section naming the gaps against `report-query-design.md` / `dashboard-design.md`
(`join`/`lookup` non-executable, `parse date` validate-only, `use collection` advisory, one chart
type, no persistence or export). Two in-app guide articles — `queries` and `dashboards` — were added
to `GuideCatalog` so people and MCP clients get the same short version.

**Why:** The design documents predate the implemented slice and read as capability claims. Someone
following them writes a query with `join` and gets a warning and wrong numbers. Keeping design intent
and shipped behaviour in separate documents, with the reference authoritative on the latter, is what
makes the docs usable without weakening the design docs' role. Every claim in the reference was
verified against a running server (an imported Postman fixture over a local JSON endpoint), not
inferred from the design.

**Status:** active

**Refs:** `docs/user-guide.md`; `docs/query-language-reference.md`;
`docs/reports-and-dashboards-tutorial.md`; `guides/GuideCatalog.java`; `docs/developer-guide.md`

### 2026-07-29 — RQL reaches function parity with the .filter report language

**Decision:** The dashboard query engine now implements every function of the `.filter` language in
`Postman-excel-report-automation`, keeping RQL's pipeline syntax rather than adopting that language's
statement-per-line shape. Added: a ported date engine (`parse date` / `date config` with format and
timezone, ten relative presets, date-aware `between`), `if … then … else` predicates, executable
`lookup` (per-row detail request, merged as `detail.<field>` plus unprefixed when there is no clash)
and `join`, `compare … on <field>` value matrices, `_source` / `_in_<label>` provenance on
`intersect` / `except` / `diff`, `diff` corrected from an alias for `except` to a true symmetric
difference, `expand` child-field prefixing with sparse fields under an exception label, and
case-insensitive text semantics with `regex` find(). Dashboards gained the summary blocks: `<Text>`,
`<KeyValue>`, `<LabelValue>`, `<QuickTable>`, `<LabelTable>`, `<Status>`, `<Metrics>`, plus `title`
and `AS` renames on `<DataTable>`, all sharing one expression language with conditionals.

**Why:** The two systems answer the same question — turn Postman-imported requests into a report —
and a user moving from one to the other should not lose a capability. Parity was defined as
functions, not syntax: RQL's pipelines compose better than repeated `SHAPE`/`FILTER` statements
targeting a key, and the `COLLECTION`/`REQUESTS` selection statements have no meaning here because
the connection is chosen by the page. Two deliberate non-ports: `COLOR` clauses, because colour here
is semantic (true/false, pass/fail) and author-chosen colour is a structural guardrail
(`RQD013`); and `OUTPUT_PREFIX`, which belongs with the workbook export that has not shipped yet.

**Status:** active

**Refs:** `reports/RqlDates.java`; `reports/RqlPredicate.java`; `reports/RqlValues.java`;
`reports/ReportQueryService.java`; `dashboards/DashboardDocumentParser.java`;
`webui/src/dashboardExpr.ts`; `webui/src/components/DashboardPage.tsx`;
`docs/query-language-reference.md` §8 maps every `.filter` keyword to its RQL spelling

### 2026-07-29 — Dashboards become Insights: saved, and free to span several apps

**Decision:** The dashboard slice is renamed **Insights** throughout — route `/insights`, package
`com.mcpserver.insights`, API `/api/insights`, document diagnostics recoded `RQD*` → `RQI*`.
`/dashboards` and `/reports` redirect. Two capabilities land with the rename:

1. **Insights are saved.** A new `insights` table plus REST CRUD (`GET/POST/PUT/DELETE
   /api/insights`) and a library panel in the workspace. A workspace is expected to hold many.
2. **One insight can read from several apps.** A request name resolves qualified
   (`request "CRM: List customers"`), scoped (`use collection "CRM";` — previously advisory, now
   real), preferred (the page's default app), then across every connected API collection. A bare
   name matching two apps fails with `RQL106` naming the candidates instead of silently choosing;
   an unknown app is `RQL107`. `<Status>`/`<Metrics>` gained a `cached` flag so a run served from
   the tool cache does not report HTTP calls it never made.

**Why:** "Dashboard" described the widget grid; the work these documents do is answer a question by
combining sources, and the old name kept the feature sounding narrower than it is. Persistence and
multi-app resolution follow from that framing: an insight worth naming is worth keeping, and the
questions worth asking cross app boundaries — orders in one collection, customers in another. Tying
a document to a single `connectionId` was an artefact of the first slice, not a constraint anything
depended on. Ambiguity is reported rather than resolved by precedence because silently reading the
wrong app's data is the one failure a report must never produce.

**Status:** active

**Refs:** `insights/` (InsightController, InsightService, InsightRepository, SavedInsight,
InsightDocumentParser, InsightModel); `reports/ReportQueryService.java` (Scope, Resolution);
`schema.sql` (insights table); `webui/src/components/InsightPage.tsx`; `config/WebMvcConfig.java`;
`docs/query-language-reference.md` §3.4

### 2026-07-29 — Retrieval uses cross-encoder ranking and contextual web research

**Decision:** Replace pass-through RRF scoring with the portable ONNX
`cross-encoder/ms-marco-MiniLM-L6-v2` model, with a deterministic Nomic-cosine/lexical fallback when
the model cannot load. Filename relevance is a bounded pre-sort feature, RRF ties are deterministic,
and local/web scores are sorted on the same 0–1 relevance scale. Web augmentation generates up to
four intent-preserving query variants, deduplicates SearXNG candidates, fetches a bounded set of
public pages through SSRF-safe HTTP, extracts text with Tika, and ranks with semantic relevance plus
authority, freshness, corroboration, and domain diversity. SearXNG readiness is an HTTP health fact;
a managed PID file permits adoption after an app restart.

**Why:** The prior implementation mutated filename scores after sorting, used a pass-through
reranker, forwarded only the raw question to SearXNG, and preserved provider order over snippets.
Those behaviours made displayed scores contradictory and web ranking overly dependent on SEO.
A 50-query real-model gate now prevents silent quality regressions.

**Status:** active; supersedes the 2026-07-04 pass-through-reranker decision and completes the
page-fetch/semantic-ranking contract of the 2026-07-04 web-search decision.

**Refs:** `rag/reranker/OnnxCrossEncoderReranker.java`; `rag/web/WebSearchService.java`;
`rag/web/WebQueryPlanner.java`; `plugins/SearXngPlugin.java`; `eval-harness/`;
`scripts/run-eval.sh`; `.github/workflows/ci.yml`

### 2026-07-29 — Browser-native UI removes the Node toolchain

**Decision:** Replace the React/TypeScript/Vite frontend with semantic HTML, CSS, and browser-native
JavaScript ES modules under `mcp-server/src/main/resources/static`. Spring Boot serves the source
files directly. The client uses a small History API router (`app.js`), one API boundary (`api.js`),
shared escaping/event/icon utilities (`ui.js`), and one controller module per page. Remove the
`webui/` tree, `frontend-maven-plugin`, frontend resource-copy execution, `-Dskip.frontend` profile,
Vite-only CORS rule, Node/npm CI job, and Node/npm prerequisites.

The existing refined-utilitarian design tokens and component stylesheet remain the visual source of
truth. Search history, files and ingestion progress, plugin lifecycle, connectors, API tools and
groups, request confirmation, saved insights, and the Guide remain first-party browser features.
The insight editor becomes a native monospace textarea; server-side RQL analysis still supplies
diagnostics and parameters.

**Why:** The frontend toolchain was the only reason a clean Java build downloaded and ran Node,
installed a second dependency graph, generated an intermediate bundle, copied that bundle, and
needed a two-process development loop. The UI does not require server-side rendering or third-party
component semantics. Modern browsers already provide modules, fetch, the History API, templates,
forms, SVG, and local storage. Keeping source as shipped resources makes `mvn package` the complete
build, removes supply-chain and cache surface, and makes local development match the runnable JAR.

**Status:** active; supersedes the 2026-07-04 React/Vite build decision and the frontend portions of
the 2026-07-29 dashboard/CodeMirror decision.

**Refs:** `mcp-server/src/main/resources/static/`; `mcp-server/pom.xml`;
`config/WebMvcConfig.java`; `.github/workflows/ci.yml`; `DEVELOPMENT.md`

### 2026-07-29 — The browser-native Apps workspace retains request-runner parity

**Decision:** The Node-free rewrite must preserve the complete request surface already implemented
by the Java executor. The Apps workspace therefore exposes schema and ad-hoc query parameters,
ad-hoc headers, schema/no-body/raw body modes, raw content type, persisted per-endpoint
authentication, dry-run resolution, cURL/browser-fetch snippets, response headers, audit history
with argument reuse, manual-request query/header definitions, and manual-request editing. Write
requests continue through preview and explicit confirmation.

Postman collection import now preserves raw JSON, raw text/XML, URL-encoded, and GraphQL bodies;
the Java executor renders raw template variables and URL-encodes form fields. Postman's
Node-based pre-request/test sandbox, collection runner, cookie jar, client certificates, and
binary file bodies are not silently described as supported: they remain a separate compatibility
runtime track because reproducing them changes the security and execution model rather than merely
adding request-builder controls.

**Why:** The first browser-native Apps page retained route and send-button functionality but
stranded executor capabilities behind an oversimplified three-tab form. A tool imported from
Postman was consequently much less operable in the UI than through the REST API. UI rewrites are
required to preserve operational capability, not only navigation and appearance, while the
approval boundary for external writes remains a deliberate product guardrail.

**Status:** active

**Refs:** `static/pages/apps.js`; `static/components.css`; `tools/ApiToolExecutor.java`;
`tools/PostmanCollectionParser.java`; `ApiToolExecutorTests`; `PostmanCollectionParserTests`

### 2026-07-29 — Outbound HTTPS combines Java trust with enterprise roots

**Decision:** All connector/spec/tool HTTP clients that call enterprise systems use one TLS client
factory. The factory always retains the JVM CA store, automatically adds Windows Trusted Root
Certification Authorities when running on Windows, and can add explicitly configured PEM/DER CA
files through `MCP_TLS_CA_CERTIFICATE_PATHS`. Hostname verification remains enabled and there is no
trust-all or per-request "ignore certificate errors" switch.

**Why:** Edge and Postman commonly trust an enterprise CA installed in Windows while Java uses its
own `cacerts`, causing an internal HTTPS request to fail with `PKIX path building failed` even
though the same request works elsewhere on the machine. Combining explicit trust sources fixes
that platform mismatch without weakening TLS globally, modifying the JDK installation, or asking
users to distribute a replacement truststore.

**Status:** active

**Refs:** `config/TlsHttpClientFactory.java`; `application.yml` (`http.tls`);
`DEPLOYMENT.md` (internal HTTPS APIs); `TlsHttpClientFactoryTests`

### 2026-07-30 — Search sessions are ordered working transcripts

**Decision:** A Search session owns an ordered list of query/tool turns rather than one replaceable
query and response. The composer always appends to the active session; only the explicit New action
starts another. Each tool response retains both a structured Preview and exact Raw response view.
Inline write-tool turns accept either schema-driven form values or a verbatim raw body, while
preserving path/query/header fields and the existing preview/confirmation safety boundary.

**Why:** Enterprise API work is consecutive: inspect a GET collection, identify a record, then issue
a related PUT/PATCH/DELETE while keeping the evidence and identifiers visible. Treating every submit
as a separate saved search destroyed that flow and made “sessions” little more than bookmarks.

**Status:** active

**Refs:** `static/pages/search.js`; `static/components.css`; `SearchController.java`;
`StaticFrontendTests`; `docs/user-guide.md`

### 2026-07-29 — TLS verification bypass is explicit, global, and temporary

**Decision:** Add an opt-in `MCP_TLS_DISABLE_VERIFY=true` emergency mode matching the existing
automation runner's `DISABLE_SSL_VERIFY=true` behavior. The default remains strict verification,
hostname verification remains enabled, and startup emits a security warning when bypass mode is
active. The switch applies globally to outbound connector, spec-fetch, and imported-tool clients;
there is no silent or persisted per-connection bypass.

**Why:** Some controlled corporate troubleshooting environments cannot immediately export or
install the internal issuing CA, while the same Postman collection is already operated with
certificate verification disabled. Making the exception explicit and process-scoped preserves a
visible security boundary and a straightforward return to the preferred Windows-root or explicit
CA configuration.

**Status:** active; narrowly supersedes the prior decision's prohibition on a trust-all switch.

**Refs:** `config/TlsHttpClientFactory.java`; `application.yml` (`http.tls`);
`DEPLOYMENT.md` (internal HTTPS APIs); `TlsHttpClientFactoryTests`

### 2026-07-30 — The Insights renderer implements its documented surface; the reference was ahead of the code

**Decision:** Close the gap between `docs/query-language-reference.md` §6.3 and what
`static/pages/insights.js` actually rendered, rather than trimming the documentation to match the
code. The browser value-expression language now implements what the reference already claimed:
`avg`/`min`/`max` alongside `count`/`sum`, `dataset.field` first-row lookups, `+` concatenation,
and nestable `if … then … else` with `and`/`or`/parentheses — evaluated by a small recursive-descent
parser (precedence `if` → `or` → `and` → comparison → concat → primary) instead of the two regexes
that were there. Comparisons compare numerically first and then case-insensitively, mirroring
`RqlValues.compare` so a `<Stat>` condition and a `where` clause agree. `avg` carries its exact mean
and formats to one decimal only at display, so `if avg(x) > 50` is not decided on a rounded value.
`<QuickTable>`, `<LabelTable>`, and `<Metrics>` gained render branches (previously validated by
`InsightDocumentParser` but silently dropped by the renderer), `<DataTable columns>` parses the
documented `AS` rename, and `<BarChart>` emits the documented "Show chart data table" twin. Bar
charts also now report their true category count and state the 24-bar axis cap instead of silently
charting a subset. Two service-level corrections: a saved insight's `connectionId` must name an
existing connection (400, not a stored dangling reference), and the library query is bounded
(`LIMIT`, default 200) rather than unbounded.

**Why:** The reference is the authority on shipped behaviour by the 2026-07-28 decision, and every
claim in it was supposed to have been verified against a running server. These had drifted: a
document could pass analysis clean and then render `avg(orders.total)` as literal text, or lose a
`<DataTable>` column entirely because `id AS "Order"` matched no field. Roughly half the documented
value-expression and component surface did not work as written — 12 of 15 render assertions fail
against the pre-change file and pass after. Fixing the code rather than the docs was the right
direction because the documented surface is coherent and small, and the parity it describes with
the `.filter` engine (2026-07-29) was the point of that work. The chart twin additionally restores
the "every chart has a table twin, not a prop, cannot be disabled" accessibility guarantee from
`dashboard-design.md` §4.6. Verified end to end against a running server with an imported OpenAPI
collection over a local JSON endpoint, driving the real `renderInsight()` against the real
`/api/insights/data` payload — not a reimplementation of it.

**Status:** active

**Refs:** `static/pages/insights.js`; `insights/InsightService.java` (`requireKnownConnection`);
`insights/InsightRepository.java` (`findAll(int)`); `InsightServiceTests`; `InsightRepositoryTests`;
`docs/query-language-reference.md` §6.3, §9

### 2026-07-31 — Grammar elements that parsed but did nothing are now reported or made real

**Decision:** Audit the RQL/`.rqd` surface for constructs the grammar accepted without effect, and
close each one rather than leave it accepted-and-inert.

1. **Stage validation now matches full forms, not first words.** `RqlParser` compared only a stage's
   first word against a keyword list while the executor matched multi-word prefixes
   (`order by `, `group by `, `parse date `, `date config `). So `order id`, `group id`, and bare
   `date`/`parse` — the latter two present in the list *only* as the openings of `date config` and
   `parse date` — passed analysis clean and then failed at run time with `RQL014`. One `STAGE_FORMS`
   table now drives validation, the did-you-mean hint, and editor completions, replacing three
   separate copies. Matching stays as whitespace-strict as the executor: collapsing runs of spaces
   before comparing would also rewrite string literals (`where name = "hello  world"`), which would
   reintroduce the same disagreement in a subtler form.
2. **Prose renders.** `Document.markdown()` was computed and never read by anything; neither
   `Analysis` nor `Data` carried it, so every heading and sentence in a document was silently
   dropped — including the `# API activity` heading in the editor's own default example. Prose is
   now emitted as ordered `Prose` blocks interleaved with components, so a paragraph between two
   charts stays there, and `{{ expression }}` interpolates using the same value-expression language
   the components use. Substitution happens before the escaping Markdown renderer, so an
   interpolated value cannot inject markup.
3. **Unknown props are reported (`RQI014`, warning).** `delta` and `format` appear in this design
   document's own examples and did nothing; a typo like `titel` was indistinguishable from `title`.
4. **`RQI012` exists.** `dashboard-design.md` §4.5 called a filter nested in a chart a parse error,
   but no such code existed and the flat tag scanner could not have detected it. The scanner now
   tracks open/close tags on a stack. The component list stays flat, so the renderer's
   consecutive-`<Stat>` grouping is untouched; nesting is used for validation only.
5. **`<Filter>` reports itself inert (`RQI311`)** instead of being silently skipped.

**Why:** Each of these made the editor lie in a different way — reporting clean and then failing at
run time, accepting a prop that does nothing, or claiming a guardrail that was never implemented.
The stage split is the most serious: fault-tolerant live feedback is the stated reason
`report-query-design.md` chose a pipeline grammar at all, and it was defeated by a first-word
comparison. `KpiRow` was left alone — it is documented as cosmetic and the renderer groups
consecutive stats regardless, so there is nothing to correct.

Verified against a running server with an imported OpenAPI collection: the rejected stages now
surface at analysis through both `/api/reports/analyze` and `/api/insights/analyze`, and prose,
interpolation, and the three new codes were confirmed on live payloads.

**Status:** active

**Refs:** `reports/ReportQueryService.java` (`STAGE_FORMS`, `isKnownStage`, `suggestedStage`,
`stageSnippets`); `reports/RqlParser.java`; `insights/InsightDocumentParser.java` (`scan`,
`withProse`, `KNOWN_PROPS`); `static/pages/insights.js` (`interpolate`, Prose branch);
`RqlStageFormTests`; `InsightGrammarTests`; `docs/query-language-reference.md`;
`docs/dashboard-design.md` §4.5

### 2026-07-31 — Tool responses preview by content type; HTML is shown as source, never rendered

**Decision:** The search session's response **Preview** now dispatches on content type instead of
treating everything non-JSON as prose. CSV is parsed into a table (RFC 4180-ish: quoted fields,
escaped `""`, embedded commas and newlines, CRLF), XML is re-indented, HTML gets a notice carrying
the page title plus an expandable tag-stripped text extract, and anything else is shown verbatim.
Every branch ends in escaped text inside a whitespace-preserving block. A bare `@app` browse also
gained a `tool-catalog` mode listing an app's whole request catalogue as a table, split into GET and
state-changing sections.

**Why:** Non-JSON bodies were routed through the Markdown renderer, which joins consecutive lines
into one paragraph — correct for retrieval excerpts, destructive for anything line-oriented. A CSV
response rendered as `id,customer,total 1,Acme,10 2,Globex,25`: every row merged onto one line, the
table structure gone. XML and plain text lost their line breaks the same way. The Raw tab was always
correct, so the data was never lost, but the default view silently misrepresented it. Verified
against a live server returning real XML, HTML, CSV, plain text, and a body that claims
`application/json` while containing XML.

**HTML is deliberately not rendered.** A response body is untrusted third-party content; rendering
it would execute whatever an upstream API chose to return inside the application's own page. The
previous behaviour was already safe by accident — Markdown escapes — and that property is now
explicit and tested, with a probe asserting no live `<script>` or `onerror` survives any branch.
Script and style contents are stripped before the text extract, so a token embedded in a script tag
is not surfaced as page copy. The title is escaped rather than trusted.

Frontend coverage is Java source assertions (`ResponsePreviewTests`), not behavioural tests: the
browser application has no Node toolchain by decision (2026-07-29) and `StaticFrontendTests` asserts
that, so adding a JS runner to test this would contradict a standing constraint. The guard was
checked by reintroducing `markdown()` into a preview branch and confirming two tests fail.

**Known scope limits, left as they are:** RQL/Insights parses JSON only (`rowsFromJson`), so an
XML or CSV endpoint cannot back an insight dataset — documented rather than fixed, since dataset
semantics for XML are a design question, not an oversight. Ingestion is a separate path and already
handles HTML via Tika plus text/JSON/XML/YAML directly.

**Status:** active

**Refs:** `static/pages/search.js` (`responsePreview`, `csvPreview`, `xmlPreview`, `htmlPreview`,
`textPreview`, `parseCsv`, `formatXml`, `toolCatalog`); `controllers/SearchController.java`
(`tool-catalog` mode, `appName`); `static/components.css` (`.catalog-*`, `.response-notice`,
`.response-text`); `ResponsePreviewTests`; `docs/user-guide.md`

### 2026-07-31 — Insights open on their last result; the editor moves behind a toggle

**Decision:** The Insights workspace defaults to two columns — saved-insight library on the left,
last rendered result on the right — with the `.rqd` editor behind an **Edit** toggle. The last run
is persisted **server-side on the insight** (`last_run` / `last_run_at` TEXT columns, same precedent
as `connections.spec_document`), so reopening an insight on any browser shows its previous numbers
instead of an empty panel. The page reopens whichever insight was last open, remembered in
`localStorage` (`mcp.insights.workspace.v1`, holding only the id and the editor-visible flag),
falling back to the most recently updated insight.

Running a saved insight goes through a new `POST /api/insights/{id}/run`, which executes **and**
stores. `POST /api/insights/data` is unchanged and stays the path for unsaved drafts.

**Why:** The page read as an editor with a preview attached rather than a set of saved answers.
Opening a saved insight explicitly discarded its result (`state.data = null`), so every visit
started on "Run to fetch API data" — the numbers were the point, and they were the one thing not
kept. This supersedes the "Insights save source, not results" limit recorded in
`query-language-reference.md` §9.

Four design points that carry the weight:

1. **`findAll` must not select `last_run`.** The library loads every insight; returning up to 200
   snapshots would dwarf the list. It selects `last_run_at` (cheap) but not the blob, which is why
   opening an insight goes through `findById` rather than reusing the list entry — a guard test
   asserts the frontend does not "optimise" that back to `state.saved.find(...)`.
2. **Over-cap results are dropped whole, never truncated.** The browser computes
   `count`/`sum`/`avg` over `dataset.rows`, so a shortened snapshot would render confidently wrong
   aggregates with nothing marking them partial. At 512 KiB the run still returns in full; only
   persistence is skipped, and the preview says so.
3. **A result from unsaved edits is run but not stored**, so a reopened insight can never show
   numbers its stored RQL cannot account for. This also makes staleness a plain client-side
   comparison of `source` against the saved source.
4. **A run is not an edit.** `updateLastRun` writes only the two snapshot columns — touching
   `updated_at` would reorder the recency-sorted library every time someone pressed Run. Conversely
   `InsightService.update` carries the snapshot through, or every save would silently wipe the
   result on screen.

Because the snapshot can be arbitrarily old and opening deliberately does **not** re-run (that would
fire upstream API calls on page load), the preview always states when it ran, marks a restored
result `Saved result`, and adds `Document edited since this run` when the source has moved on.

**Accepted cost:** `last_run` stores upstream API response bodies unencrypted in SQLite. Confirmed
acceptable for now — the server is localhost-bound and pre-Phase-6, and the same file already holds
`spec_document` and workflow results. Revisit alongside the Phase 6 auth/ACL work.

Verified end to end against a running server: migration applied to the existing database, run →
reopen → snapshot returns, list omits the blob, unsaved edits and 512 KiB both decline to store
without degrading the returned data, save preserves the snapshot, and `updated_at` does not move on
a run. The stored snapshot renders **byte-identically** to a fresh run through the real
`renderInsight`.

**Status:** active; supersedes the "save source, not results" limit from 2026-07-28.

**Refs:** `schema.sql` (insights `last_run`/`last_run_at`); `insights/SavedInsight.java`;
`insights/InsightRepository.java` (`LIST_COLUMNS`/`FULL_COLUMNS`, `updateLastRun`);
`insights/InsightService.java` (`run`, `MAX_LAST_RUN_BYTES`); `insights/InsightController.java`;
`static/pages/insights.js`; `static/components.css` (`.insight-workspace.is-editing`);
`InsightWorkspaceTests`; `InsightServiceTests`; `InsightRepositoryTests`

### 2026-07-31 — The Guide becomes Help behind a `?` button, and gains hands-on tutorials

**Decision:** The `/guide` page is now `/help`, reached from a **?** icon button in the top bar
rather than a primary-nav link, and a new `/tutorial` page carries ordered, hands-on walkthroughs.
`/guide` still resolves — the client router maps it to `/help` and the server forward stays — so old
links and bookmarks keep working. `pages/guide.js` was renamed to `pages/help.js` (via `git mv`, so
history follows).

Tutorials live in a new `TutorialCatalog` beside `GuideCatalog`, projected over
`GET /api/tutorials`. Two ship: *Ask your first grounded question* (3 steps) and *Build your first
insight* (5 steps). Every step names the route it happens on, the concrete actions, and how to tell
it worked; the page links straight to that route and ticks steps off, with progress in
`localStorage` (`mcp.tutorial.progress.v1`).

**Why:** Documentation was competing with the workspace for nav space — the primary nav should list
places you work, and a permanent "Guide" link sits oddly beside Files and Connections. A `?` is the
conventional, unobtrusive place to put help, and it frees the nav while making help reachable from
every page. Splitting reference from walkthrough follows from what each is for: a guide article
answers "what are the rules", a tutorial answers "how do I get my first result", and the second is
what a new workspace actually needs. Help now leads with the walkthroughs for that reason.

Two deliberate limits:

1. **Tutorials are server-side but not exposed over MCP**, unlike guides (`McpGuideBridge`). The
   2026-07-27 decision put guidance on the server so a person and an MCP client see identical rules;
   that argument holds for reference, but a tutorial instructs someone driving the web UI — an agent
   reading "Open Plugins and press Install" as an operating rule would be misled. They stay
   server-side anyway so the content cannot drift from the app the way SPA-hardcoded copy would.
2. **Progress is a convenience, not a record.** It is per-browser and unversioned; clearing storage
   just empties the checkboxes.

A guard test asserts every client route has a matching `WebMvcConfig` forward — the exact gap that
previously made `/connections` 404 on direct load. It was verified by removing the `/tutorial`
forward and confirming the test names it.

**Status:** active

**Refs:** `guides/TutorialCatalog.java`; `controllers/TutorialController.java`;
`static/pages/help.js` (was `guide.js`); `static/pages/tutorial.js`; `static/app.js` (`?` button,
`/guide` → `/help`); `static/ui.js` (`help`, `route` icons); `config/WebMvcConfig.java`;
`TutorialCatalogTests`; `HelpNavigationTests`; `docs/user-guide.md`

### 2026-08-03 — Search ranking learns from user feedback
**Decision:** Add a self-contained `com.mcpserver.learning` subsystem that captures what the user
found useful and feeds it back into ranking. Ground truth is two logs — `search_impressions` (one
row per served search: ordered result ids, scores, arm, propensity, context, latency) and
`search_feedback` (explicit thumbs plus implicit `EXPAND`/`OPEN`/`COPY` signals). Two learners read
those logs: a **feedback memory** (`feedback_memory`, per-query-family chunk preferences, matched to
new queries by term-Jaccard ≥ 0.60 **or** query-embedding cosine ≥ 0.92, applied as a bounded
post-rerank score delta) and a **disjoint LinUCB contextual bandit** (`ranking_policy_arms`, five
discrete `(w_vector, w_lexical)` arms over the newly weighted `RrfFusion.fuse`). All learning DB
writes go through one `learning-writer` daemon thread modelled on `EventQueueWorker`, so the search
request path does zero learning I/O. Everything learned is a **derived cache**: `reset` clears
learned state without touching the logs, and `rebuild` reconstructs it from them.

Five sub-decisions:

1. **The bandit ships disabled** (`learning.bandit.enabled: false`), behind a shadow mode that logs
   the arm the policy would have picked while serving `baseline`. Capture and the memory ship on.
   With the bandit off, served ranking is bit-identical to a build without this subsystem — the
   `(1,1)` arm is float-for-float the old unweighted fusion, asserted in `RrfFusionTests`.
2. **The memory delta is applied *after* the `min-relevance-score` filter, never before.** This is
   the load-bearing safety property: a thumbs-down can only sink a result, never remove it, so the
   user can always find it again and re-vote. `max-boost` (0.12) and `max-demote` (0.06) are
   asymmetric for the same reason — a wrong demotion hides evidence, a wrong promotion only annoys.
3. **Propensity is logged on every impression** even while the bandit is off. It is the only thing
   that makes unbiased off-policy evaluation possible after the fact, and it cannot be backfilled.
   **Stopping rule, committed here:** if the replay harness reports
   `V̂(learned) ≤ V̂(baseline) + 1 SE`, the bandit is not helping and stays disabled.
4. **The golden set is being rebuilt, not extended.** At P@1 0.98 / nDCG@10 0.9926 over 10
   topically-disjoint 400-character documents it cannot detect an improvement *or* a regression, so
   it cannot judge a learner. It grows to 40 documents in 8 near-duplicate families with graded
   relevance and near-miss negatives, plus a new Recall@candidate metric — the one the fusion
   weights actually control. `GoldenSetRegressionTests` deliberately stays plain JUnit: making it
   `@SpringBootTest` would pull in sqlite-vec, which is absent in CI, silently degrading the vector
   leg so the gate would measure a different pipeline while still reporting green.
5. **Accepted risk, not solved:** the learning writer shares the app's single
   `SingleConnectionDataSource` with request threads, and xerial's connection is not documented as
   thread-safe for concurrent statements. `EventQueueWorker` already does exactly this, so the
   precedent and the risk both pre-date this change. Mitigations are one writer thread (not N), a
   bounded drop-on-overflow queue, batched short transactions, and a busy timeout.

**Why:** Ranking was entirely fixed — `SearchService` ran hand-tuned constants and nothing about it
responded to whether the results were any good, because nothing was recorded. `AuditService.logSearch`
existed but was never called, `MetricsService` was dead code, and the frontend sent no telemetry, so
there was no reward signal to learn from and no way to tell a ranking win from noise. Of the two
learners the **memory is the valuable half**: it changes results within one vote. The bandit's only
knob sits upstream of a cross-encoder that dominates final ordering, and with 40+40 candidates
retrieved from ~400 chunks, reweighting rarely changes what the reranker even sees — expected effect
is near zero and detection would take months at single-user traffic. It is built because it is the
general policy and because the instrumentation to judge it is worth having, but it is built to be
switched off. This is a single-user app, so there is one global policy and no per-user modelling;
the known consequence is that the memory entrenches whatever the user first liked, which is the
feature here and would be a bug anywhere else.

**Status:** active

**Refs:** `learning/` (LearningWriter, FeedbackController, RewardSettler, FeedbackMemory,
RankingPolicy, repositories); `rag/retrieval/RrfFusion.java` (weighted `fuse`);
`services/SearchService.java`; `cache/CacheService.java` (arm-aware key); `schema.sql`
(`search_impressions`, `search_feedback`, `ranking_policy_arms`, `feedback_memory`);
`application.yml` (`learning:`); `static/pages/search.js`, `static/api.js`, `static/ui.js`;
`eval-harness/`; `scripts/run-replay.sh`

### 2026-08-03 — Imported API URL policy is selectable per connection
**Decision:** Persist an `api_url_mode` on every `API_COLLECTION` connection. The default
`CONNECTION_BASE` mode keeps the existing deployment override: all imported paths execute against one
connection base URL. The opt-in `SOURCE_URLS` mode preserves absolute Postman request URLs and resolves
OpenAPI servers with operation → path → document precedence; relative source requests fall back to the
connection base URL. In source mode, execution validates each call against the exact origin persisted
in that tool's imported URL template rather than a connection-wide origin. Changing modes triggers a
reimport so the persisted URL and its structural host allowance cannot drift apart.
**Why:** Real collections can intentionally span multiple services, so replacing every origin with one
base URL changes their meaning. Keeping the current behavior as the default preserves portable
environment overrides, while an explicit source mode supports multi-host collections without accepting
arbitrary runtime hosts. The UI warns that shared connection credentials may be sent to every preserved
host.
**Status:** active
**Refs:** `connectors/ApiUrlMode.java`, `connectors/ApiCollectionConnector.java`,
`tools/PostmanCollectionParser.java`, `tools/OpenApiParser.java`, `tools/ApiToolExecutor.java`,
`static/pages/connections.js`, `docs/user-guide.md` §5.2

### 2026-08-03 — Golden set rebuilt; near-miss negatives are not rejected
**Decision:** Replace the 10-document golden set with 40 documents in 8 near-duplicate families,
160 graded queries and 15 near-miss negatives, and gate on measured values
(P@1 0.86, MRR 0.91, graded nDCG@10 0.92, Recall@candidate 0.98). Two new metrics:
**graded nDCG** — with one relevant document per query the ideal DCG is always 1, which is why the
old nDCG tracked MRR almost exactly (0.9926 vs 0.99) and measured nothing extra — and
**Recall@candidate**, the share of queries whose relevant document reached the reranker. That last
one is the metric the fusion weights actually control; without it, any change to the vector/lexical
blend is invisible to the gate because everything downstream can only reorder the candidate window.
The gate file no longer carries `minimumRelevanceScore`: retrieval knobs are read from
`application.yml`, so a baseline cannot silently redefine the behaviour it measures.

**Finding, recorded rather than fixed:** negative-rejection accuracy measures **0.0**. The near-miss
negatives — plausible in-domain questions with no answer in the corpus — score 0.020–0.079 from the
cross-encoder, which is *above* the 0.015 `rag.search.min-relevance-score` floor, while genuinely
correct answers score 0.73–0.93 (median 0.93). The floor was calibrated against off-topic queries,
which fall below it, and does not separate in-domain unanswerable ones. Measured trade-off: a floor
of 0.05 rejects 12/15 negatives and loses 4/157 correct answers; 0.08 rejects 15/15 and loses 9/157.
Raising it is a change to shipped ranking behaviour, outside the scope of the feedback work that
uncovered it, so the gate is set at the measured 0.0 and can only be tightened once that call is
made. Note the lexical-rescue path (term coverage ≥ 0.30) retains a result regardless of the floor,
so the floor alone cannot reject every negative.

**Why:** The previous fixture was saturated — P@1 0.98 over 10 topically disjoint 400-character
documents, with correct answers scoring 20× their nearest distractor. It could not detect an
improvement or a regression, which makes it useless for judging a learner, and that is the whole
reason the corpus was rebuilt. Difficulty comes from structure, not volume: within a family the
documents share most of their prose and differ in one decisive dimension (Cloud vs Server setup, a
30-day vs 90-day retention policy, an "ACL tag" / "permission label" pair naming different
mechanisms), which defeats pure embedding similarity and forces the reranker to use one
discriminating phrase. The set found a real pipeline weakness on its first run, which is the
strongest available evidence that it is measuring something.

**Known gap:** the harness scores one chunk per document, so chunk-level ranking *within* a long
document is still not exercised. Closing it needs the harness to run the real chunker, not just a
longer corpus.

**Status:** active

**Refs:** `eval-harness/README.md` (fixture shape, measured baseline, the floor trade-off table);
`eval-harness/corpus/documents.json`; `eval-harness/golden-set/{search,negative-search,baseline}.json`;
`GoldenSetRegressionTests`; `rag/retrieval/TextSignals.java`; `scripts/run-replay.sh`;
`ReplayHarnessTests`

### 2026-08-03 — Atlassian connectors use explicit auth, overlap-safe cursors, and reconciliation

**Decision:** Jira and Confluence connections now persist an explicit Basic-or-Bearer choice: Cloud
email/API-token and Server/DC username/password use Basic; Server/DC personal access tokens use
Bearer. Confluence Cloud fetching moves from REST v1 to REST v2 cursor pagination while Server/DC
stays on v1. Backfills persist the crawl's start time—not its completion time—as the delta cursor.
Polling runs in a bounded executor with a per-connection overlap guard, and each connector performs a
daily lightweight full-ID inventory that purges indexed IDs no longer returned by the source. Manual
backfills reconcile immediately. Server/DC webhook callback URLs carry a random per-connection token;
the token is AES-GCM encrypted in `connection_webhook_tokens`, verified in constant time before an
event is queued, and deleted with the connection.

**Why:** A completion-time cursor can skip edits made while a long backfill is running. Delta APIs do
not emit reliable tombstones on polling-only Cloud connections, so webhooks alone cannot meet delete
propagation. Serial polling lets one slow tenant delay all others. Atlassian Data Center PATs require
Bearer rather than the Cloud Basic email/token pattern. Finally, an externally reachable callback
that trusts only a connection ID lets a forged Jira delete event purge indexed content. These fixes
close those correctness and security gaps without adding Phase-6 user authentication or an external
queue/database.

**Status:** active

**Refs:** `connectors/AtlassianAuth.java`, `JiraConnector.java`, `ConfluenceConnector.java`,
`ConnectionPollingScheduler.java`, `WebhookTokenService.java`, `ConnectionController.java`;
`repositories/ChunkRepository.java`; `schema.sql` (`connection_webhook_tokens`);
`JiraConnectorTests`, `ConfluenceConnectorTests`, `ConnectionPollingSchedulerTests`,
`WebhookTokenServiceTests`; `static/pages/connections.js`

### 2026-08-04 — The tutorials become the Help page's main content, and "guide" finishes becoming "help"

**Decision:** Two changes to the surface introduced on 2026-07-31.

First, the tutorial catalogue grew from 2 walkthroughs (8 steps, 1 example) to 7 walkthroughs
(34 steps, 55 examples) covering the first grounded answer, the query bar, Confluence/Jira ingestion,
importing an API and its approval path, building an insight, RQL across apps, and connecting an MCP
client. The step model gained structure to carry that: `List<TutorialExample> examples` replaces the
single `code` string, where an example is `{ label, description, language, code, result }`; a step
also carries `List<TutorialFix> troubleshooting` (`{ symptom, fix }`); and a tutorial declares
`level`, `prerequisites`, and `nextTutorials`. Summaries advertise `level` and `examples` so the Help
cards and the picker can say what a reader is choosing between. The tutorial page renders each
example as a labelled unit with a language chip, a copy button, and its expected result, puts the
catalogue in a sticky rail, and ends on cross-links to related walkthroughs.

Second, the 2026-07-31 rename was only half done: the route was `/help` but the code underneath still
said guide. `com.mcpserver.guides` → `com.mcpserver.help`, `GuideCatalog` → `HelpCatalog` (records
`TopicSummary`/`HelpTopic`/`TopicSection`), `GuideController` → `HelpController`, `GET /api/guides` →
`GET /api/help`, and the `.guide-*` CSS classes → `.help-*`. All moves used `git mv` so history
follows. `/guide` still resolves to `/help`.

**Why:** A tutorial with one snippet per step is an outline — a reader can copy it but cannot check
themselves, which is the thing that makes a walkthrough trustworthy. Pairing every example with what
the application should do in response is what makes it verifiable, and it is why the example count is
an asserted property rather than incidental. The topics the walkthroughs now cover are the ones where
getting it wrong is expensive (credentials, the write-approval contract, cross-app queries), so those
are where worked examples earn their space.

Two things deliberately keep the "guide" name: the MCP resource URIs
(`mcp://enterprise-mcp/guides/operating-guide`, `…/llm-playbook.json`) and `McpGuideBridge`. Those are
a protocol contract clients bind to, and "operating guide" is the right name for an LLM-facing
document regardless of what the human page is called. Renaming them would break clients to satisfy
internal consistency.

**Status:** active

**Refs:** `help/TutorialCatalog.java`, `help/HelpCatalog.java` (both were `guides/`);
`controllers/HelpController.java` (was `GuideController`); `static/pages/tutorial.js`,
`static/pages/help.js`, `static/api.js`, `static/components.css`; `TutorialCatalogTests`,
`HelpCatalogTests`, `HelpNavigationTests`; `docs/user-guide.md`, `AGENTS.md`

### 2026-08-04 — Jira and Confluence switch to metadata-first, title-triggered lazy ingestion

**Decision:** Connecting or backfilling Jira/Confluence no longer downloads, chunks, or embeds every
page/issue body. The connectors catalogue lightweight containers and resources in SQLite:
`connector_containers` stores Confluence spaces/Jira projects; `connector_resources` plus
`connector_resources_fts` stores page titles/issue summaries, external IDs, source versions,
credentialed API paths, human-facing URLs, and `METADATA_ONLY`/`INDEXED` state. Before cached/local RAG
retrieval, `ConnectorContentResolver` hydrates a bounded number (default 3) of metadata-only items only
when the query contains the complete title or exactly matches the external key. Generic space/project
queries do not hydrate their children. Deltas/webhooks update metadata; a version change purges stale
chunks and returns the item to `METADATA_ONLY`; deletion/reconciliation removes catalogue rows and chunks.
Full scans stream remote IDs through `connector_inventory` and use a SQLite anti-join for deletions, so
reconciliation heap usage is bounded rather than retaining a tenant-sized Java set.

**Why:** Eager body backfill makes connection time, ONNX embedding cost, SQLite growth, Jira comment
fan-out, and remote API pressure proportional to the tenant's entire history. Metadata is small enough
to index comprehensively and gives a fast discovery layer; fetching on a strong title/key intent makes
content work proportional to what users actually request. Keeping both credentialed `api_path` and
citation `web_url` separates machine retrieval from the location shown to a user.

**Status:** active (supersedes eager Jira/Confluence body backfill and eager delta re-ingestion)

**Refs:** `connectors/SourceCatalogRepository.java`, `ConnectorContentResolver.java`,
`ConfluenceConnector.java`, `JiraConnector.java`; `schema.sql`; `SearchService.java`;
`SourceCatalogRepositoryTests`, `ConfluenceConnectorTests`, `JiraConnectorTests`

---

### 2026-08-07 — Insights get a Power BI-style design surface, with the .rqd document still the source of truth

**Decision:** The Insights workspace gains a third mode (`View` / `Design` / `Code`) rather than a
second editor. Design mode adds the three panes a BI tool is recognised by — a Visualizations picker,
field wells for the selected visual, and a Fields list of the run's dataset columns (click or drag to
bind) — plus selection, reordering, and deletion directly on the canvas. Every one of those gestures
edits the `.rqd` text: `setTagProp` rewrites a single prop inside the selected component's raw tag and
`spliceSource` splices it back at the span the parser reported, shifting the spans that follow. There
is no parallel dashboard model, no layout JSON, and no generated-file mode — Design and Code are two
views of the same document, and switching between them mid-edit is lossless. Components are identified
across re-parses by the source offset their tag starts at, not by their index in the outline.
`LineChart` and `PieChart` join `BarChart` sharing one `data`/`x`/`y` prop vocabulary.

**Why:** The document format was chosen precisely because click-built dashboards are unversionable and
unreviewable (`docs/dashboard-design.md` §1), so a visual editor that owned its own model would trade
away the reason for the format. Editing the text surgically instead of regenerating each tag keeps a
Format-pane change readable as a one-prop diff rather than a whole-file reformat. Offsets rather than
indices because a re-analysis can split or merge the prose around an edit, which silently shifts every
index after it. One prop vocabulary across the charts is what lets a visual's type be swapped without
rebinding its fields.

**Deliberately not included:** a free-form drag-and-resize canvas (components remain a vertical block
flow), and cross-filtering / slicers / drill-through. Both are large enough to be their own decision,
and neither is required for the document to stay the source of truth. Field lists populate from the
last run, so a brand-new document must be run once before its fields can be dragged — an upfront
schema probe was not added.

**Status:** active

**Refs:** `static/pages/insights.js` (`CATALOG`, `setTagProp`, `spliceSource`, `editComponent`,
`componentKey`), `static/components.css`; `InsightDocumentParser.java`, `InsightService.java`;
`InsightWorkspaceTests`

---

### 2026-08-07 — Insight run progress stays indeterminate; workspace state moves to a status bar

**Decision:** The Insights workspace gains a persistent status bar deriving every segment's value and
tone from one pure function (`statusSegments`): run freshness, dataset/row counts, request count and
cost, analyzer error/warning counts, saved/unsaved state, the bound app, declared parameters, and the
Design-mode selection. Segments are omitted when they do not apply, and the two that lead somewhere
(**Checks**, **Document**) are buttons. Run feedback becomes an explicit **indeterminate** progress
bar carrying a live elapsed clock plus what the previous run of that document cost — no
`aria-valuenow`, no percentage. `<Status />` gains per-request duration bars scaled to the slowest
request; cache hits are labelled, never plotted.

**Why:** A run's request count cannot be known when it starts — a `lookup` stage issues one request
per row (`ReportQueryService.lookup`) — so any filling bar would be inventing its denominator, and a
progress bar that does not track progress is worse than an honest spinner. Reporting elapsed time and
the prior run's cost is the most that can be said truthfully. The status bar's rules live in a pure
function because the failure mode that matters is a *wrong* readout — "Saved" over unsaved edits, or
"Clean" over a broken binding — which is only preventable if the conditions are testable apart from
the markup. Duration bars because a column of millisecond figures makes you compare numbers to find
the expensive call.

**Rejected:** a determinate bar fed by a run-progress side-channel (a completion callback threaded
through `ReportQueryService` plus a polled endpoint). It would report real progress for documents
without a `lookup` and still have to fall back to indeterminate for those with one, which buys a
partial guarantee at the cost of a new endpoint and a callback through a shared service. Revisit if
runs routinely grow long enough that elapsed time alone stops being useful.

**Status:** active

**Refs:** `static/pages/insights.js` (`statusSegments`, `statusBar`, `runProgress`, `tickElapsed`,
`statusBlock`), `static/components.css`; `InsightWorkspaceTests`

---

### 2026-08-21 — ONNX models may be omitted from the build and installed through verified local uploads

**Decision:** Keep the zero-install `mvn package` behavior as the default, but add the narrower
`-Dskip.models=true` build property. It omits only the Nomic embedding and MiniLM reranker model and
tokenizer downloads; sqlite-vec and SearXNG remain bundled. The Plugins page exposes the four pinned
Hugging Face download links and accepts one model/tokenizer pair at a time through
`POST /api/plugins/models/{embedding|reranker}`. Uploads ignore client filenames for path selection,
stream into temporary sibling files, enforce size/extension limits, verify the same SHA-256 values as
the Maven build, release native ONNX sessions before replacement, and reload afterward. Replacement
keeps recoverable sibling backups until both files are in place. Arbitrary bring-your-own models are
not supported by this path; compatibility remains tied to the pinned model architecture and tokenizer.

**Why:** Windows and enterprise build machines often cannot let Maven reach Hugging Face because of
proxy/TLS policy, while an operator can still transfer approved artifacts through a browser or removable
media. `-Dskip.bundle=true` was too broad for this workflow because it also removes sqlite-vec and
SearXNG. Exact digest validation preserves the reproducibility and supply-chain guarantee of the
original bundled build, and unloading first avoids Windows file-lock failures during replacement.

**Status:** active

**Refs:** `pom.xml` (`skip.models`); `plugins/OnnxModelUploadService.java`, `PluginController.java`,
`NomicEmbeddingPlugin.java`; `rag/reranker/OnnxCrossEncoderReranker.java`;
`static/pages/plugins.js`, `static/api.js`; `OnnxModelUploadServiceTests`, `StaticFrontendTests`
