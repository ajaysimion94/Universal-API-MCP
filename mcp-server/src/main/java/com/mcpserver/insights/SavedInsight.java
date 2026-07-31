package com.mcpserver.insights;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * A stored insight document. {@code connectionId} is the preferred app for unqualified request
 * names only — the document itself may read from several collections.
 *
 * <p>{@code lastRun} is the previous rendered result, kept so reopening an insight shows its last
 * numbers instead of an empty panel. It is a <em>snapshot and never authoritative</em>: it can be
 * arbitrarily old, opening an insight deliberately does not re-run it, and it is only written when
 * the serialized payload fits under {@link InsightService}'s cap. It is null on a row that has
 * never been run, and also on every row returned by
 * {@link InsightRepository#findAll()} — the library list omits the blob on purpose.
 */
public record SavedInsight(
        String id,
        String name,
        String description,
        String source,
        String connectionId,
        Instant createdAt,
        Instant updatedAt,
        JsonNode lastRun,
        Instant lastRunAt
) {

    /** An insight with no run snapshot yet — the shape every insight starts in. */
    public SavedInsight(String id, String name, String description, String source,
                        String connectionId, Instant createdAt, Instant updatedAt) {
        this(id, name, description, source, connectionId, createdAt, updatedAt, null, null);
    }
}
