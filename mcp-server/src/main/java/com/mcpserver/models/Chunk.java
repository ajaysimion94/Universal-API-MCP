package com.mcpserver.models;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A chunk of an ingested document, persisted in pgvector for hybrid search.
 * Carries ACL tags captured at ingestion (enforcement activates in Phase 6).
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
        Instant createdAt
) {

    public static Chunk create(String sourceFileId, String sourceName, String sourcePath,
                               String content, float[] embedding, List<String> aclTags,
                               int position, int tokenCount) {
        return new Chunk(
                UUID.randomUUID().toString(),
                sourceFileId, sourceName, sourcePath,
                content, embedding, aclTags,
                position, tokenCount, Instant.now()
        );
    }
}
