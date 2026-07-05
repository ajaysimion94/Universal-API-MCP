-- Chunks table: vector + lexical legs for hybrid search (plan.md §5.6, §5.7)
-- pgvector 0.8.4 required (CREATE EXTENSION vector — run once externally).

CREATE TABLE IF NOT EXISTS chunks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_file_id  TEXT NOT NULL,
    source_name     TEXT NOT NULL,
    source_path     TEXT,
    content         TEXT NOT NULL,
    embedding       vector(768),
    tsv             tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    acl_tags        TEXT[] NOT NULL DEFAULT '{}',
    position        INTEGER NOT NULL DEFAULT 0,
    token_count     INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- HNSW index for cosine ANN over the embedding column (plan.md §3).
CREATE INDEX IF NOT EXISTS chunks_embedding_hnsw
    ON chunks USING hnsw (embedding vector_cosine_ops);

-- GIN index for the lexical full-text leg (ts_rank).
CREATE INDEX IF NOT EXISTS chunks_tsv_gin
    ON chunks USING gin (tsv);

-- GIN index for ACL tag filtering (enforcement activates in Phase 6).
CREATE INDEX IF NOT EXISTS chunks_acl_tags_gin
    ON chunks USING gin (acl_tags);
