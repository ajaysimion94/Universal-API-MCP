package com.mcpserver.learning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.learning.LearningModel.Impression;
import com.mcpserver.learning.LearningModel.ServedResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The served-search log. This is ground truth for both learners — everything they learn is a
 * derived cache that {@code POST /api/search/learning/rebuild} can reconstruct from these rows.
 */
@Repository
public class ImpressionRepository {

    private static final Logger log = LoggerFactory.getLogger(ImpressionRepository.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String COLUMNS = """
            id, query, query_norm, surface, top_k, web, lexical_only, from_cache, arm_id,
            propensity, shadow_arm, context, results, memory_hits, latency_ms, served_at,
            rewarded_at, reward""";

    private static final RowMapper<Impression> MAPPER = (rs, rowNum) -> new Impression(
            rs.getString("id"),
            rs.getString("query"),
            rs.getString("query_norm"),
            rs.getString("surface"),
            rs.getInt("top_k"),
            rs.getInt("web") != 0,
            rs.getInt("lexical_only") != 0,
            rs.getInt("from_cache") != 0,
            rs.getString("arm_id"),
            rs.getDouble("propensity"),
            rs.getString("shadow_arm"),
            parseContext(rs.getString("context")),
            parseResults(rs.getString("results")),
            rs.getInt("memory_hits"),
            rs.getLong("latency_ms"),
            Instant.parse(rs.getString("served_at")),
            instantOrNull(rs, "rewarded_at"),
            doubleOrNull(rs, "reward"));

    private final JdbcTemplate jdbc;

    public ImpressionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Called only from the writer thread. */
    public void save(Impression impression) {
        jdbc.update("""
                        INSERT OR REPLACE INTO search_impressions (
                            id, query, query_norm, surface, top_k, web, lexical_only, from_cache,
                            arm_id, propensity, shadow_arm, context, results, memory_hits,
                            latency_ms, served_at, rewarded_at, reward)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                impression.id(),
                impression.query(),
                impression.queryNorm(),
                impression.surface(),
                impression.topK(),
                impression.web() ? 1 : 0,
                impression.lexicalOnly() ? 1 : 0,
                impression.fromCache() ? 1 : 0,
                impression.armId(),
                impression.propensity(),
                impression.shadowArm(),
                toJson(impression.context()),
                toJson(compact(impression.results())),
                impression.memoryHits(),
                impression.latencyMs(),
                impression.servedAt().toString(),
                impression.rewardedAt() == null ? null : impression.rewardedAt().toString(),
                impression.reward());
    }

    public Optional<Impression> findById(String id) {
        List<Impression> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM search_impressions WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Impressions whose reward window has closed and that were never settled. Cache hits are
     * included — they feed the memory and the replay log even though they are not bandit pulls.
     */
    public List<Impression> findUnsettledBefore(Instant cutoff, int limit) {
        return query("""
                SELECT %s FROM search_impressions
                WHERE rewarded_at IS NULL AND served_at <= ?
                ORDER BY served_at LIMIT ?""".formatted(COLUMNS), cutoff.toString(), limit);
    }

    /** Every impression that has at least one signal — the replay and rebuild corpus. */
    public List<Impression> findWithFeedback() {
        return query("""
                SELECT %s FROM search_impressions i
                WHERE EXISTS (SELECT 1 FROM search_feedback f WHERE f.impression_id = i.id)
                ORDER BY served_at""".formatted(COLUMNS));
    }

    public void markSettled(String id, Instant rewardedAt, Double reward) {
        jdbc.update("UPDATE search_impressions SET rewarded_at = ?, reward = ? WHERE id = ?",
                rewardedAt.toString(), reward, id);
    }

    public long count() {
        Long value = jdbc.queryForObject("SELECT count(*) FROM search_impressions", Long.class);
        return value == null ? 0 : value;
    }

    public Map<String, Long> summaryCounts(Instant since) {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        counts.put("total", count());
        counts.put("last24h", scalar(
                "SELECT count(*) FROM search_impressions WHERE served_at >= ?", since.toString()));
        counts.put("fromCache", scalar(
                "SELECT count(*) FROM search_impressions WHERE from_cache = 1"));
        counts.put("withFeedback", scalar("""
                SELECT count(*) FROM search_impressions i
                WHERE EXISTS (SELECT 1 FROM search_feedback f WHERE f.impression_id = i.id)"""));
        counts.put("settled", scalar(
                "SELECT count(*) FROM search_impressions WHERE rewarded_at IS NOT NULL"));
        return counts;
    }

    /**
     * Latency percentiles per arm and cache state, so "impression logging didn't slow search" and
     * "arm X isn't slower" are both measured rather than asserted.
     */
    public List<Map<String, Object>> latencyByArm() {
        try {
            return jdbc.queryForList("""
                    SELECT arm_id, from_cache, count(*) AS samples,
                           avg(latency_ms) AS mean_ms, max(latency_ms) AS max_ms
                    FROM search_impressions GROUP BY arm_id, from_cache ORDER BY arm_id""");
        } catch (Exception exception) {
            log.warn("Latency summary failed: {}", exception.getMessage());
            return List.of();
        }
    }

    /** Deletes only impressions that never received a signal — rewarded ones are the replay corpus. */
    public int pruneUnrewardedBefore(Instant cutoff) {
        return jdbc.update("""
                DELETE FROM search_impressions
                WHERE served_at < ?
                  AND NOT EXISTS (SELECT 1 FROM search_feedback f WHERE f.impression_id = search_impressions.id)""",
                cutoff.toString());
    }

    private List<Impression> query(String sql, Object... params) {
        try {
            return jdbc.query(sql, MAPPER, params);
        } catch (Exception exception) {
            // Consistent with ChunkRepository's search methods: a learning read must never be able
            // to fail a request or a scheduled sweep.
            log.warn("Impression query failed: {}", exception.getMessage());
            return List.of();
        }
    }

    private long scalar(String sql, Object... params) {
        try {
            Long value = jdbc.queryForObject(sql, Long.class, params);
            return value == null ? 0 : value;
        } catch (Exception exception) {
            return 0;
        }
    }

    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static Double doubleOrNull(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception exception) {
            return "[]";
        }
    }

    /** A corrupt row must read back as empty, never break the sweep that touches it. */
    private static List<Float> parseContext(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return JSON.readValue(value, new TypeReference<List<Float>>() {});
        } catch (Exception exception) {
            return List.of();
        }
    }

    private static List<ServedResult> parseResults(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            List<Map<String, Object>> raw = JSON.readValue(value, new TypeReference<>() {});
            return raw.stream()
                    .map(entry -> new ServedResult(
                            String.valueOf(entry.getOrDefault("c", "")),
                            ((Number) entry.getOrDefault("r", 0)).intValue(),
                            ((Number) entry.getOrDefault("s", 0)).floatValue()))
                    .toList();
        } catch (Exception exception) {
            return List.of();
        }
    }

    /** Compact wire form for the {@code results} column: chunk id, rank, score. */
    static List<Map<String, Object>> compact(List<ServedResult> results) {
        return results.stream()
                .map(result -> Map.<String, Object>of(
                        "c", result.chunkId(), "r", result.rank(), "s", result.score()))
                .toList();
    }
}
