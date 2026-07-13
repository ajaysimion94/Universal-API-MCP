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
