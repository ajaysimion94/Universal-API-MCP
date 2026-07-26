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

### 2026-07-25 — Web UI Chat now generates answers via Microsoft Copilot (external); supersedes the same-day "no answer generation" stance for the UI chat path only
**Decision:** Plain-text messages on the Chat page are now answered by generated text: the server grounds each message with RAG retrieval (top-6 chunks, cited [n]) and streams an answer from **Microsoft Copilot** (`copilot.microsoft.com`, reverse-engineered WebSocket client in `copilot/`) over SSE at `POST /api/chat`. This **reverses the 2026-07-25 "no server-side answer generation" entry above for the Web UI chat path only** — the user wrote the Copilot client themselves after declining the *in-process* LLM (whose ~3-4GB local memory cost was the actual blocker; an external backend has none). The MCP context path (`/mcp`) stays retrieval-only; `#`/`@` tool invocation stays deterministic on `/api/search`. History remains client-side; the Copilot `conversationId` is kept in `localStorage` for follow-up turns. Generation failure degrades gracefully: the turn's retrieved excerpts are still delivered (`sources` event) and rendered raw.
**Why:** A chat page that only returns excerpts forces users to read chunks; the declined alternative (in-process LLM) cost too much RAM. External Copilot is free and in-process-nothing. Known costs, accepted by the user: (1) plain chat messages + retrieved excerpts **leave the device** for Microsoft's consumer API — the empty-state copy says so; (2) the API is undocumented and can break or change anytime; (3) **anonymous chat is gated server-side** (verified 2026-07-25: conversation creation works anonymously, the WS handshake 401s) — answer generation needs `chat.copilot.access-token` (+ `identity-type`/`cookies`) copied from a signed-in browser session, set via env vars (`CHAT_COPILOT_ACCESSTOKEN`, …), never committed. Also observed: a cookie session may create only **one** conversation (second create → 401), so the client resets to a pristine cookie session per new conversation.
**Status:** active
**Refs:** `copilot/Copilot*.java`; `services/CopilotChatService.java`; `services/ChatPromptBuilder.java`; `controllers/ChatController.java` (`/api/chat` SSE); `webui/src/components/ChatPage.tsx`; `webui/src/components/MarkdownText.tsx`; `application.yml` (`chat.copilot.*`)
