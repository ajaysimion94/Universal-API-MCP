package com.mcpserver.learning;

import com.mcpserver.learning.LearningModel.Feedback;
import com.mcpserver.learning.LearningModel.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The user-signal log. Append-only in spirit, but written with {@code INSERT OR REPLACE} against the
 * {@code (impression_id, chunk_id, signal)} unique key so the feedback POST is idempotent: clicking
 * the same thumb twice upserts rather than double-counting, and flipping up to down replaces.
 */
@Repository
public class FeedbackRepository {

    private static final Logger log = LoggerFactory.getLogger(FeedbackRepository.class);

    private static final RowMapper<Feedback> MAPPER = (rs, rowNum) -> new Feedback(
            rs.getLong("id"),
            rs.getString("impression_id"),
            rs.getString("chunk_id"),
            rs.getInt("rank"),
            Signal.valueOf(rs.getString("signal")),
            rs.getFloat("value"),
            rs.getString("actor"),
            Instant.parse(rs.getString("created_at")));

    private final JdbcTemplate jdbc;

    public FeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Called only from the writer thread. Batched: one POST usually carries several signals. */
    public void saveAll(List<Feedback> events) {
        if (events.isEmpty()) return;
        jdbc.batchUpdate("""
                        INSERT OR REPLACE INTO search_feedback
                            (impression_id, chunk_id, rank, signal, value, actor, created_at)
                        VALUES (?,?,?,?,?,?,?)""",
                events.stream()
                        .map(event -> new Object[]{
                                event.impressionId(),
                                event.chunkId(),
                                event.rank(),
                                event.signal().name(),
                                event.value(),
                                event.actor(),
                                event.createdAt().toString()})
                        .toList());
    }

    public List<Feedback> findByImpression(String impressionId) {
        return query("SELECT * FROM search_feedback WHERE impression_id = ? ORDER BY id", impressionId);
    }

    public List<Feedback> findAll() {
        return query("SELECT * FROM search_feedback ORDER BY created_at, id");
    }

    /** Whether the impression has an explicit thumb — explicit feedback settles it immediately. */
    public boolean hasRating(String impressionId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM search_feedback WHERE impression_id = ? AND signal = 'RATING'",
                Long.class, impressionId);
        return count != null && count > 0;
    }

    public Map<String, Long> countsBySignal() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Signal signal : Signal.values()) counts.put(signal.name(), 0L);
        try {
            jdbc.queryForList("SELECT signal, count(*) AS n FROM search_feedback GROUP BY signal")
                    .forEach(row -> counts.put(
                            String.valueOf(row.get("signal")), ((Number) row.get("n")).longValue()));
        } catch (Exception exception) {
            log.warn("Feedback counts failed: {}", exception.getMessage());
        }
        return counts;
    }

    private List<Feedback> query(String sql, Object... params) {
        try {
            return jdbc.query(sql, MAPPER, params);
        } catch (Exception exception) {
            log.warn("Feedback query failed: {}", exception.getMessage());
            return List.of();
        }
    }
}
