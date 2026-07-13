package com.mcpserver.connectors;

import java.time.Instant;

/**
 * A durable queue entry for webhook intake or a delta-poll result, replacing the Postgres-outbox
 * design in the original plan (superseded — see DECISIONS.md). {@code id} is null until
 * persisted; {@link IngestionEventRepository#insert} returns the row with its generated id.
 */
public record IngestionEvent(
        Long id,
        String connectionId,
        EventType eventType,
        String externalId,
        String payload,
        EventStatus status,
        int attempts,
        String error,
        Instant receivedAt,
        Instant processedAt
) {

    public static IngestionEvent create(String connectionId, EventType eventType, String externalId, String payload) {
        return new IngestionEvent(null, connectionId, eventType, externalId, payload,
                EventStatus.PENDING, 0, null, Instant.now(), null);
    }
}
