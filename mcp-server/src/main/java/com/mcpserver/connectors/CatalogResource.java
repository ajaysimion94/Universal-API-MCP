package com.mcpserver.connectors;

import java.time.Instant;

/**
 * Lightweight pointer to a remote page/issue. Bodies deliberately do not live in the catalogue;
 * {@code apiPath} is the credentialed content location used for lazy hydration and {@code webUrl}
 * is the human-facing citation location.
 */
public record CatalogResource(
        String id,
        String connectionId,
        String sourceSystem,
        String externalId,
        String containerExternalId,
        String containerName,
        String title,
        String apiPath,
        String webUrl,
        Instant sourceUpdatedAt,
        CatalogContentState contentState,
        Instant contentIndexedAt,
        Instant catalogedAt
) {
}
