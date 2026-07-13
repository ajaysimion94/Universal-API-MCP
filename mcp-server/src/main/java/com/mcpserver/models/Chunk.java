package com.mcpserver.models;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A chunk of an ingested document, persisted in SQLite (FTS5 lexical leg + sqlite-vec vector leg)
 * for hybrid search. Carries ACL tags captured at ingestion (enforcement activates in Phase 6).
 * {@code sourceSystem}/{@code externalId}/{@code url}/{@code sourceUpdatedAt} identify chunks
 * ingested by a connector (Confluence, Jira, ...) rather than manual upload — "upload" and nulls
 * for the upload path.
 */
public record Chunk(
        String id,
        String sourceFileId,
        String sourceName,
        String sourcePath,
        String content,
        float[] embedding,
        List<String> aclTags,
        int position,
        int tokenCount,
        Instant createdAt,
        String sourceSystem,
        String externalId,
        String url,
        Instant sourceUpdatedAt
) {

    public static Chunk create(String sourceFileId, String sourceName, String sourcePath,
                               String content, float[] embedding, List<String> aclTags,
                               int position, int tokenCount) {
        return create(sourceFileId, sourceName, sourcePath, content, embedding, aclTags,
                position, tokenCount, "upload", null, null, null);
    }

    public static Chunk create(String sourceFileId, String sourceName, String sourcePath,
                               String content, float[] embedding, List<String> aclTags,
                               int position, int tokenCount,
                               String sourceSystem, String externalId, String url, Instant sourceUpdatedAt) {
        return new Chunk(
                UUID.randomUUID().toString(),
                sourceFileId, sourceName, sourcePath,
                content, embedding, aclTags,
                position, tokenCount, Instant.now(),
                sourceSystem, externalId, url, sourceUpdatedAt
        );
    }
}
