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

### 2026-07-27 — Consumer Copilot is an opt-in plugin with an isolated browser profile
**Decision:** The reverse-engineered consumer-Copilot answer path is a built-in optional plugin (`copilot-chat`) that defaults to disabled. Enabling it does not expose a token form. Instead, the plugin launches Chrome/Chromium with a dedicated profile at `./data/copilot-browser-profile`, a random localhost-only DevTools port, and an explicit Copilot tab. The user completes Microsoft's sign-in manually, then explicitly selects **Use signed-in session**; the app reads Copilot cookies/local storage only from that isolated profile and transfers the resulting credentials to `CopilotChatService` in memory. Disabling the plugin closes the controlled browser and clears the in-memory handoff. The user's default browser profile is never attached.
**Why:** A normal cross-origin popup cannot expose `copilot.microsoft.com` session state, while Microsoft provides no supported consumer-Copilot OAuth/API flow for this non-tenant setup. A dedicated controlled profile makes the requested manual-login flow technically possible without pasting tokens or granting an extension access to the user's everyday browsing profile. The isolation also satisfies Chrome 136+'s requirement that remote debugging use a non-default user-data directory. Accepted risk: the upstream protocol remains undocumented and may break; prompts and retrieved excerpts still leave the device.

### 2026-07-27 — Prefer Microsoft Edge for the dedicated Copilot browser

**Decision:** The Copilot browser bridge auto-detects Microsoft Edge, Google Chrome, and Chromium on macOS, Windows, and Linux. Edge is preferred when more than one supported browser is installed because Copilot is a Microsoft service; `chat.copilot.browser-executable` remains the deterministic override.

**Why:** Edge and Chrome expose the same Chromium DevTools Protocol used by the isolated manual-login bridge. First-class Edge discovery removes platform-specific setup while retaining the same localhost-only debugging endpoint and dedicated-profile isolation.
**Status:** active (local-only compatibility bridge; replace with the official Microsoft 365 Copilot API if a licensed tenant becomes available)
**Refs:** `plugins/CopilotChatPlugin.java`; `copilot/CopilotBrowserBridge.java`; `copilot/CopilotBrowserController.java`; `copilot/CopilotCredentialStore.java`; `webui/src/components/PluginsPage.tsx`; `DEPLOYMENT.md`

### 2026-07-27 — Chat uses multi-conversation history and progressive evidence disclosure

**Decision:** The Web UI keeps up to 25 independently selectable conversations in `localStorage`, capped at 50 turns each. Each conversation owns its Copilot continuation id, derives its title from the first user message, and appears in a persistent desktop history rail or compact-screen history panel. RAG and web evidence stays collapsed under every answer—including error fallbacks—with distinct document/result counts; expanding the evidence reveals the source list, and expanding an individual RAG file reveals its matching chunks. Consumed topbar query parameters are removed after submission so refreshing the page never resubmits the last prompt.

**Why:** A flat transcript did not behave like a chat product, and expanded retrieval cards made the evidence visually louder than the answer. Client-side conversations add useful continuity without introducing the Phase-6 identity/ACL work needed for server-side history. Progressive disclosure keeps citations accessible while making the conversation the primary reading surface.

**Status:** active

**Refs:** `webui/src/components/ChatPage.tsx`; `webui/src/components.css`; `docs/plan.md`

### 2026-07-27 — Copilot browser handoff separates normal sign-in from local session capture

**Decision:** The isolated Copilot profile now uses two distinct browser launches. **Step 1** opens
Edge/Chrome normally, with no DevTools or automation flags, so the user can complete Microsoft, Google,
or federated authentication using the browser's standard security posture. **Step 2**, triggered only by
the explicit **Connect signed-in session** action, closes that window and briefly relaunches the same
isolated profile with a random localhost-only DevTools port. The bridge reads only storage and cookies
applicable to `copilot.microsoft.com`, transfers them to the in-memory credential store, and immediately
closes the debugging browser. The default browser profile is still never attached.

**Why:** Google blocks account sign-in in remote-debugging browser sessions with “This browser or app
may not be secure.” Keeping debugging enabled throughout manual sign-in made the original flow unusable
for Google-backed accounts and unnecessarily extended the sensitive handoff window. Edge's visible
Copilot can provide basic anonymous chat, but the reverse-engineered WebSocket used by this server has
been observed to reject anonymous sessions; the plugin therefore still requires an authenticated
Copilot session even though the standalone Edge experience may not.

**Status:** active

**Refs:** `copilot/CopilotBrowserBridge.java`; `copilot/CopilotBrowserController.java`;
`webui/src/components/PluginsPage.tsx`; `DEPLOYMENT.md`

### 2026-07-27 — “Connected” requires a completed Copilot protocol turn

**Decision:** Capturing browser storage is no longer enough to mark the Copilot plugin active. The
Connect action now validates the captured values through a short throwaway Copilot turn—including
session warm-up, conversation creation, WebSocket handshake, challenge response, streamed answer, and
the terminal `done` event—before publishing them to the shared chat client. Validation has a 30-second
budget and reports the upstream failure inline. The WebSocket listener also publishes its opened socket
from `onOpen` before requesting the first server frame, preventing an immediate challenge from racing
the `buildAsync(...).get()` assignment and dereferencing a null socket.

**Why:** A browser session could previously show **Connected** because it contained Copilot cookies even
when the real chat exchange failed. Live diagnosis also exposed a concrete race: Copilot can send its
challenge as soon as the socket opens, before the JDK completes the builder future on the caller thread.
That failure produced no answer and left the UI waiting for the 180-second generation timeout.

**Status:** active

**Refs:** `copilot/CopilotWebSocket.java`; `copilot/CopilotClient.java`;
`services/CopilotChatService.java`; `copilot/CopilotBrowserController.java`

### 2026-07-27 — Remove the consumer Copilot answer connector

**Decision:** Remove the reverse-engineered consumer-Copilot client, browser-profile handoff,
session credentials, plugin, and `POST /api/chat` endpoint. The Chat page remains a persistent,
client-side conversation interface, but plain text now uses the existing RAG search endpoint and
renders cited sources directly. Imported `#`/`@` tool invocations are unchanged.

**Why:** The consumer Copilot browser experience is not a supported automation surface. Browser-session
handoff could report a captured session while the upstream chat socket rejected it, making the feature
unreliable. Keeping retrieval local avoids sending prompts or files to that third-party service and
leaves a clean seam for a future supported answer provider.

**Status:** active; supersedes the consumer-Copilot decisions above.

**Refs:** `webui/src/components/ChatPage.tsx`; `webui/src/components/PluginsPage.tsx`;
`controllers/SearchController.java`; `DEPLOYMENT.md`

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
Exports include source/path/system/URL provenance, are capped at 25 MB, and do not contact Copilot or
another answer provider.

**Why:** A single “export everything” action would make accidental disclosure easy and would obscure
which source produced each passage. Explicit scope, a local artifact, and a size boundary create an
auditable preparation step while preserving the decision to remove the unsupported consumer-Copilot
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
