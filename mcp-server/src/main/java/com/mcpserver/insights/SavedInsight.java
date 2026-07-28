package com.mcpserver.insights;

import java.time.Instant;

/**
 * A stored insight document. {@code connectionId} is the preferred app for unqualified request
 * names only — the document itself may read from several collections.
 */
public record SavedInsight(
        String id,
        String name,
        String description,
        String source,
        String connectionId,
        Instant createdAt,
        Instant updatedAt
) {
}
