-- Chunks base table: metadata + lexical leg (FTS5 built into SQLite, no extension needed).
-- The vector leg (chunks_vec, vec0 virtual table) is created by SqliteVecStorePlugin
-- after the sqlite-vec extension is downloaded and loaded.

CREATE TABLE IF NOT EXISTS chunks (
    id              TEXT PRIMARY KEY,
    source_file_id  TEXT NOT NULL,
    source_name     TEXT NOT NULL,
    source_path     TEXT,
    content         TEXT NOT NULL,
    embedding       TEXT,
    acl_tags        TEXT NOT NULL DEFAULT '[]',
    position        INTEGER NOT NULL DEFAULT 0,
    token_count     INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE INDEX IF NOT EXISTS idx_chunks_source_file_id ON chunks(source_file_id);

-- Lexical full-text leg via FTS5 (built into xerial sqlite-jdbc, no extension needed).
CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
    content,
    chunk_id UNINDEXED,
    tokenize = 'porter unicode61'
);

-- Connector columns, added via guarded ALTER TABLE so existing databases pick them up.
-- `continue-on-error: true` (application.yml) lets this script tolerate "duplicate column"
-- failures on databases that already have these columns, so it's safe to re-run on every boot.
ALTER TABLE chunks ADD COLUMN source_system TEXT NOT NULL DEFAULT 'upload';
ALTER TABLE chunks ADD COLUMN external_id TEXT;
ALTER TABLE chunks ADD COLUMN url TEXT;
ALTER TABLE chunks ADD COLUMN updated_at TEXT;

-- Saved insight documents (.rqd source: Markdown + RQL + components). One row per insight; a
-- workspace is expected to accumulate many of them. `connection_id` is only the preferred app for
-- unqualified request names — a document may read from several collections.
CREATE TABLE IF NOT EXISTS insights (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    description   TEXT NOT NULL DEFAULT '',
    source        TEXT NOT NULL,
    connection_id TEXT,
    created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE INDEX IF NOT EXISTS idx_insights_name ON insights(name);

-- A credentialed, schedulable connection to a remote knowledge source (Confluence, Jira; more
-- types reserved). Distinct from the plugins/ subsystem, which manages singleton local
-- infrastructure (embedding model, vector store, SearXNG) rather than N remote connections.
CREATE TABLE IF NOT EXISTS connections (
    id                     TEXT PRIMARY KEY,
    type                   TEXT NOT NULL,
    name                   TEXT NOT NULL,
    base_url               TEXT NOT NULL,
    deployment_type        TEXT NOT NULL DEFAULT 'UNKNOWN',
    auth_mode              TEXT NOT NULL DEFAULT 'BASIC',
    auth_username           TEXT,
    auth_secret_encrypted  TEXT,
    status                 TEXT NOT NULL DEFAULT 'PENDING',
    last_error             TEXT,
    sync_cursor            TEXT,
    webhook_registered     INTEGER NOT NULL DEFAULT 0,
    acl_scope              TEXT NOT NULL DEFAULT '[]',
    created_at             TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at             TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    last_synced_at         TEXT
);

CREATE INDEX IF NOT EXISTS idx_connections_type ON connections(type);

-- Opaque callback credentials for externally reachable Server/DC webhooks. The token is encrypted
-- with the same local AES key as connector credentials and is never returned by the REST API.
CREATE TABLE IF NOT EXISTS connection_webhook_tokens (
    connection_id     TEXT PRIMARY KEY,
    token_encrypted   TEXT NOT NULL,
    created_at        TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

-- Durable event queue for webhook intake and delta-poll results, replacing the Postgres-outbox
-- design in docs/plan.md (superseded — see DECISIONS.md). A single background worker
-- (EventQueueWorker) claims PENDING rows; PENDING/PROCESSING rows are replayed on startup so a
-- restart mid-burst loses nothing.
CREATE TABLE IF NOT EXISTS ingestion_events (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    connection_id   TEXT NOT NULL,
    event_type      TEXT NOT NULL,
    external_id     TEXT,
    payload         TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER NOT NULL DEFAULT 0,
    error           TEXT,
    received_at     TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    processed_at    TEXT
);

CREATE INDEX IF NOT EXISTS idx_ingestion_events_status ON ingestion_events(status);
CREATE INDEX IF NOT EXISTS idx_ingestion_events_connection_id ON ingestion_events(connection_id);

-- Spec source for API_COLLECTION connections (Postman collection / OpenAPI spec import).
-- spec_document keeps the raw spec text so re-import/diff needs no file storage; specs are
-- small text documents, fine for SQLite.
ALTER TABLE connections ADD COLUMN spec_source_url TEXT;
ALTER TABLE connections ADD COLUMN spec_format TEXT;
ALTER TABLE connections ADD COLUMN spec_document TEXT;
ALTER TABLE connections ADD COLUMN api_url_mode TEXT NOT NULL DEFAULT 'CONNECTION_BASE';

-- Tools generated from an imported Postman collection / OpenAPI spec (product-idea.md §8).
-- One row per request/operation; enabled tools are callable from search (# grammar) and MCP.
-- GET tools are enabled on import; state-changing tools start pending until approved.
CREATE TABLE IF NOT EXISTS api_tools (
    id               TEXT PRIMARY KEY,
    connection_id    TEXT NOT NULL,
    app_slug         TEXT NOT NULL,
    name             TEXT NOT NULL,
    request_slug     TEXT NOT NULL,
    display_name     TEXT NOT NULL,
    description      TEXT,
    category         TEXT NOT NULL DEFAULT 'general',
    http_method      TEXT NOT NULL,
    url_template     TEXT NOT NULL,
    params_schema    TEXT NOT NULL DEFAULT '{}',
    param_locations  TEXT NOT NULL DEFAULT '{}',
    headers          TEXT NOT NULL DEFAULT '{}',
    body_template    TEXT,
    primary_param    TEXT,
    enabled          INTEGER NOT NULL DEFAULT 0,
    pending          INTEGER NOT NULL DEFAULT 0,
    knowledge_source INTEGER NOT NULL DEFAULT 0,
    created_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    UNIQUE (connection_id, name)
);

CREATE INDEX IF NOT EXISTS idx_api_tools_connection_id ON api_tools(connection_id);
CREATE INDEX IF NOT EXISTS idx_api_tools_name ON api_tools(name);

-- User-defined groups of whole apps (connections) and/or individual endpoints (api_tools).
-- The slug doubles as the @ handle in the search grammar (@group #action); it stays stable
-- across renames. No DB-level FKs — membership cleanup happens in the services, consistent
-- with the rest of the schema.
CREATE TABLE IF NOT EXISTS tool_groups (
    id          TEXT PRIMARY KEY,
    slug        TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL,
    description TEXT,
    created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE IF NOT EXISTS tool_group_members (
    group_id    TEXT NOT NULL,
    member_type TEXT NOT NULL,           -- 'APP' | 'TOOL'
    member_id   TEXT NOT NULL,           -- APP → connections.id, TOOL → api_tools.id
    created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    PRIMARY KEY (group_id, member_type, member_id)
);

CREATE INDEX IF NOT EXISTS idx_tool_group_members_member ON tool_group_members(member_type, member_id);

-- ─── Phase 3: Enterprise Actions (§7, §9, §10) ───────────────────────────────

-- Workflow execution state machine (§7.2). Each row tracks one tool invocation
-- through EXTRACT → VALIDATE → GUARD → PREVIEW → (confirm) → EXECUTE → CONFIRM.
-- Confirmation tokens are single-use and expiring; idempotency keys prevent
-- duplicate creates. State persists across backend restarts.
CREATE TABLE IF NOT EXISTS workflow_executions (
    id                  TEXT PRIMARY KEY,
    tool_id             TEXT NOT NULL,
    tool_name           TEXT NOT NULL,
    state               TEXT NOT NULL DEFAULT 'EXTRACTING',
    params              TEXT NOT NULL DEFAULT '{}',
    resolved_params     TEXT,
    confirmation_token  TEXT UNIQUE,
    token_expires_at    TEXT,
    idempotency_key     TEXT UNIQUE,
    actor               TEXT,
    preview_payload     TEXT,
    result              TEXT,
    error               TEXT,
    created_at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE INDEX IF NOT EXISTS idx_workflow_state ON workflow_executions(state);
CREATE INDEX IF NOT EXISTS idx_workflow_token ON workflow_executions(confirmation_token);

-- Audit log (§9). Every tool invocation, approval, rejection, execution, and
-- failure is recorded. Queryable by actor, tool, date range, event type.
-- Actor is client-declared until Phase 6 binds verified JWT identity.
CREATE TABLE IF NOT EXISTS audit_log (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    event_type      TEXT NOT NULL,
    tool_id         TEXT,
    tool_name       TEXT,
    workflow_id     TEXT,
    actor           TEXT,
    arguments       TEXT,
    result_summary  TEXT,
    error           TEXT,
    ip_address      TEXT,
    user_agent      TEXT,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE INDEX IF NOT EXISTS idx_audit_log_tool ON audit_log(tool_name);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor ON audit_log(actor);
CREATE INDEX IF NOT EXISTS idx_audit_log_created ON audit_log(created_at);
CREATE INDEX IF NOT EXISTS idx_audit_log_event_type ON audit_log(event_type);

-- Extraction template stubs: regex/JSONPath patterns per tool, auto-generated
-- from the params schema and admin-editable (§7.1).
ALTER TABLE api_tools ADD COLUMN extraction_template TEXT;

-- 'IMPORTED' (spec-derived, refreshed/pruned on re-import) vs 'MANUAL' (built from scratch in
-- the request builder — re-import must never touch these). See ApiToolService.importTools().
ALTER TABLE api_tools ADD COLUMN origin TEXT NOT NULL DEFAULT 'IMPORTED';

-- Per-tool auth override (nullable auth_mode = inherit the connection's stored auth — see
-- ApiToolExecutor.applyAuth). Same four modes/columns as connections.auth_mode, applies to both
-- read and write tools since it's a persisted, admin-set value rather than an ephemeral override.
ALTER TABLE api_tools ADD COLUMN auth_mode TEXT;
ALTER TABLE api_tools ADD COLUMN auth_username TEXT;
ALTER TABLE api_tools ADD COLUMN auth_secret_encrypted TEXT;

-- Last rendered result for a saved insight, so reopening it — on any browser — shows the previous
-- run instead of an empty panel. JSON text, same precedent as connections.spec_document. Written
-- only when the serialized payload is under InsightService.MAX_LAST_RUN_BYTES, so the column stays
-- bounded; a run is a snapshot, never authoritative. Both nullable: an insight is saved long before
-- it is ever run, so every pre-existing row reads back null here.
ALTER TABLE insights ADD COLUMN last_run TEXT;
ALTER TABLE insights ADD COLUMN last_run_at TEXT;

-- ─── Adaptive ranking: feedback capture and learned state (com.mcpserver.learning) ───────────

-- One row per served search. The ordered result list is JSON rather than a child table on purpose:
-- it is never joined on, it holds at most 100 entries, and with a single shared SQLite connection
-- "one row per search" versus "eleven rows per search" is a real difference. `context` and
-- `propensity` are what make offline replay possible at all — without the logged feature vector and
-- the exact probability the serving policy assigned to the arm it picked, no unbiased counterfactual
-- estimate of an alternative policy can be computed after the fact, and neither can be backfilled.
CREATE TABLE IF NOT EXISTS search_impressions (
    id             TEXT PRIMARY KEY,             -- UUID, echoed to the client as impressionId
    query          TEXT NOT NULL,
    query_norm     TEXT NOT NULL,                -- sorted, stop-worded, lowercased term key
    surface        TEXT NOT NULL DEFAULT 'web',  -- 'web' | 'mcp' (MCP has no feedback UI)
    top_k          INTEGER NOT NULL DEFAULT 0,
    web            INTEGER NOT NULL DEFAULT 0,
    lexical_only   INTEGER NOT NULL DEFAULT 0,
    from_cache     INTEGER NOT NULL DEFAULT 0,   -- Caffeine hit: logged, but never a bandit pull
    arm_id         TEXT NOT NULL DEFAULT 'baseline',
    propensity     REAL NOT NULL DEFAULT 1.0,    -- P(arm_id | context) under the serving policy
    shadow_arm     TEXT,                         -- arm the policy WOULD have picked, in shadow mode
    context        TEXT NOT NULL DEFAULT '[]',   -- JSON floats: 4 live features + 2 diagnostics
    results        TEXT NOT NULL DEFAULT '[]',   -- JSON [{"c":chunkId,"r":rank,"s":score}]
    memory_hits    INTEGER NOT NULL DEFAULT 0,   -- how many results the feedback memory adjusted
    latency_ms     INTEGER NOT NULL DEFAULT 0,
    served_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    rewarded_at    TEXT,                         -- NULL = still collecting signals
    reward         REAL                          -- [0,1]; NULL = settled with no signal at all
);

CREATE INDEX IF NOT EXISTS idx_search_impressions_served ON search_impressions(served_at);
CREATE INDEX IF NOT EXISTS idx_search_impressions_query_norm ON search_impressions(query_norm);
CREATE INDEX IF NOT EXISTS idx_search_impressions_unsettled ON search_impressions(rewarded_at);

-- One row per user signal. `chunk_id` is NOT NULL DEFAULT '' rather than nullable because SQLite
-- treats NULLs as distinct in a UNIQUE index, so a nullable column would silently admit duplicate
-- query-level signals. The UNIQUE key is what makes the feedback POST idempotent: re-clicking a
-- thumb upserts instead of double-counting, and flipping up->down replaces the RATING row.
CREATE TABLE IF NOT EXISTS search_feedback (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    impression_id TEXT NOT NULL,
    chunk_id      TEXT NOT NULL DEFAULT '',
    rank          INTEGER NOT NULL DEFAULT 0,   -- 1-based rank at serve time; 0 = query-level
    signal        TEXT NOT NULL,                -- RATING | EXPAND | OPEN | COPY
    value         REAL NOT NULL DEFAULT 0,      -- RATING: +1/-1/0 (cleared); implicit: fixed weight
    actor         TEXT NOT NULL DEFAULT 'web-user',
    created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    UNIQUE (impression_id, chunk_id, signal)
);

CREATE INDEX IF NOT EXISTS idx_search_feedback_impression ON search_feedback(impression_id);
CREATE INDEX IF NOT EXISTS idx_search_feedback_chunk ON search_feedback(chunk_id);

-- Learned LinUCB state, one row per discrete (w_vector, w_lexical) arm. Arms are seeded from code
-- (RankingPolicy.ensureSeeded), not from this DDL, so adding an arm later is a code change rather
-- than a migration. A and b are tiny (4x4 + 4), so persisting them on every update costs nothing.
CREATE TABLE IF NOT EXISTS ranking_policy_arms (
    arm_id      TEXT PRIMARY KEY,
    w_vector    REAL NOT NULL,
    w_lexical   REAL NOT NULL,
    a_matrix    TEXT NOT NULL DEFAULT '[]',   -- JSON 4x4 ridge design matrix (init = identity)
    b_vector    TEXT NOT NULL DEFAULT '[]',   -- JSON 4-vector response (init = zero)
    pulls       INTEGER NOT NULL DEFAULT 0,
    reward_sum  REAL NOT NULL DEFAULT 0,
    enabled     INTEGER NOT NULL DEFAULT 1,   -- auto-disabled by the underperformance guardrail
    updated_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

-- Per-query-family chunk preferences: the learner that is actually visible at single-user traffic.
-- `strength` is clamped to [-1,+1] so no single vote is decisive. `embedding` is the running
-- centroid of the query embeddings that produced this entry, stored so a paraphrase can match
-- without recomputing history; it is nullable because the vector leg can be down, in which case
-- term-Jaccard matching still works.
CREATE TABLE IF NOT EXISTS feedback_memory (
    id           TEXT PRIMARY KEY,
    query_norm   TEXT NOT NULL,
    query_sample TEXT NOT NULL,                -- one human-readable query, for the Learning panel
    embedding    TEXT,                         -- JSON float[768] centroid, or NULL
    chunk_id     TEXT NOT NULL,
    source_name  TEXT NOT NULL DEFAULT '',     -- denormalized for the panel; chunks can be deleted
    strength     REAL NOT NULL DEFAULT 0,
    observations INTEGER NOT NULL DEFAULT 0,
    last_seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    UNIQUE (query_norm, chunk_id)
);

CREATE INDEX IF NOT EXISTS idx_feedback_memory_query_norm ON feedback_memory(query_norm);
