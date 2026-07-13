package com.mcpserver.connectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class IngestionEventRepository {

    private final JdbcTemplate jdbc;

    public IngestionEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IngestionEvent> MAPPER = (rs, rowNum) -> {
        String processedAt = rs.getString("processed_at");
        return new IngestionEvent(
                rs.getLong("id"),
                rs.getString("connection_id"),
                EventType.valueOf(rs.getString("event_type")),
                rs.getString("external_id"),
                rs.getString("payload"),
                EventStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getString("error"),
                Instant.parse(rs.getString("received_at")),
                processedAt == null ? null : Instant.parse(processedAt)
        );
    };

    public IngestionEvent insert(IngestionEvent e) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator psc = con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO ingestion_events
                        (connection_id, event_type, external_id, payload, status, attempts, received_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, e.connectionId());
            ps.setString(2, e.eventType().name());
            ps.setString(3, e.externalId());
            ps.setString(4, e.payload());
            ps.setString(5, e.status().name());
            ps.setInt(6, e.attempts());
            ps.setString(7, e.receivedAt().toString());
            return ps;
        };
        jdbc.update(psc, keyHolder);
        long id = keyHolder.getKey().longValue();
        return new IngestionEvent(id, e.connectionId(), e.eventType(), e.externalId(), e.payload(),
                e.status(), e.attempts(), e.error(), e.receivedAt(), e.processedAt());
    }

    /**
     * Claims the oldest PENDING row by flipping it to PROCESSING and returning it. Safe without
     * SKIP LOCKED-style contention handling because {@code EventQueueWorker} runs a single
     * background thread — there is never more than one claimant in this process.
     */
    public Optional<IngestionEvent> claimNextPending() {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM ingestion_events WHERE status = 'PENDING' ORDER BY id ASC LIMIT 1", Long.class);
        if (ids.isEmpty()) return Optional.empty();
        long id = ids.get(0);
        jdbc.update("UPDATE ingestion_events SET status = 'PROCESSING' WHERE id = ?", id);
        return findById(id);
    }

    public Optional<IngestionEvent> findById(long id) {
        List<IngestionEvent> rows = jdbc.query("SELECT * FROM ingestion_events WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<IngestionEvent> findPendingOrProcessing() {
        return jdbc.query("SELECT * FROM ingestion_events WHERE status IN ('PENDING','PROCESSING') ORDER BY id ASC", MAPPER);
    }

    /** Resets any PROCESSING rows back to PENDING — used on startup so a crash mid-processing gets retried. */
    public void resetProcessingToPending() {
        jdbc.update("UPDATE ingestion_events SET status = 'PENDING' WHERE status = 'PROCESSING'");
    }

    public void markDone(long id) {
        jdbc.update("UPDATE ingestion_events SET status = 'DONE', processed_at = ? WHERE id = ?",
                Instant.now().toString(), id);
    }

    public void markFailed(long id, String error, int attempts, int maxAttempts) {
        String newStatus = attempts >= maxAttempts ? "DEAD_LETTER" : "PENDING";
        jdbc.update("UPDATE ingestion_events SET status = ?, error = ?, attempts = ? WHERE id = ?",
                newStatus, error, attempts, id);
    }
}
