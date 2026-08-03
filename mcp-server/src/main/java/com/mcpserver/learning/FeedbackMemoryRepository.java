package com.mcpserver.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.learning.LearningModel.MemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class FeedbackMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(FeedbackMemoryRepository.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final RowMapper<MemoryEntry> MAPPER = (rs, rowNum) -> new MemoryEntry(
            rs.getString("id"),
            rs.getString("query_norm"),
            rs.getString("query_sample"),
            parseEmbedding(rs.getString("embedding")),
            rs.getString("chunk_id"),
            rs.getString("source_name"),
            rs.getFloat("strength"),
            rs.getInt("observations"),
            Instant.parse(rs.getString("last_seen_at")));

    private final JdbcTemplate jdbc;

    public FeedbackMemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MemoryEntry> findAll() {
        try {
            return jdbc.query("SELECT * FROM feedback_memory", MAPPER);
        } catch (Exception exception) {
            // A memory that cannot be read degrades ranking to the unlearned baseline, which is a
            // perfectly good answer — it must never fail a search.
            log.warn("Feedback memory read failed: {}", exception.getMessage());
            return List.of();
        }
    }

    public void save(MemoryEntry entry) {
        jdbc.update("""
                        INSERT OR REPLACE INTO feedback_memory (
                            id, query_norm, query_sample, embedding, chunk_id, source_name,
                            strength, observations, last_seen_at, updated_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?)""",
                entry.id(),
                entry.queryNorm(),
                entry.querySample(),
                toJson(entry.embedding()),
                entry.chunkId(),
                entry.sourceName(),
                entry.strength(),
                entry.observations(),
                entry.lastSeenAt().toString(),
                Instant.now().toString());
    }

    public void deleteAll() {
        jdbc.update("DELETE FROM feedback_memory");
    }

    public void deleteById(String id) {
        jdbc.update("DELETE FROM feedback_memory WHERE id = ?", id);
    }

    public long count() {
        Long value = jdbc.queryForObject("SELECT count(*) FROM feedback_memory", Long.class);
        return value == null ? 0 : value;
    }

    private static String toJson(float[] embedding) {
        if (embedding == null || embedding.length == 0) return null;
        try {
            return JSON.writeValueAsString(embedding);
        } catch (Exception exception) {
            return null;
        }
    }

    /** A corrupt embedding reads back as absent; term-Jaccard matching still works without it. */
    private static float[] parseEmbedding(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return JSON.readValue(value, float[].class);
        } catch (Exception exception) {
            return null;
        }
    }
}
