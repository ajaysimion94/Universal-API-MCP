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
