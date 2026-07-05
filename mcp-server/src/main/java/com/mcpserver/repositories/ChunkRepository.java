package com.mcpserver.repositories;

import com.mcpserver.models.Chunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JDBC repository over the chunks table (plan.md §3 — direct JDBC to pgvector).
 * <p>
 * Stores embeddings as pgvector {@code vector(768)} and content as a generated tsvector lexical leg.
 */
@Repository
public class ChunkRepository {

    private final JdbcTemplate jdbc;

    public ChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Chunk> MAPPER = (rs, rowNum) -> {
        java.sql.Array aclArr = rs.getArray("acl_tags");
        String[] acl = aclArr == null ? new String[0] : (String[]) aclArr.getArray();
        float[] embedding = null;
        try {
            String embStr = rs.getString("embedding");
            embedding = embStr != null ? parseVector(embStr) : null;
        } catch (Exception ignored) {}
        return new Chunk(
                rs.getString("id"),
                rs.getString("source_file_id"),
                rs.getString("source_name"),
                rs.getString("source_path"),
                rs.getString("content"),
                embedding,
                Arrays.asList(acl),
                rs.getInt("position"),
                rs.getInt("token_count"),
                rs.getTimestamp("created_at").toInstant()
        );
    };

    public void save(Chunk chunk) {
        String vectorLit = toVectorLiteral(chunk.embedding());
        String sql = """
                INSERT INTO chunks (id, source_file_id, source_name, source_path, content, embedding, acl_tags, position, token_count)
                VALUES (?::uuid, ?, ?, ?, ?, ?::vector, ?::text[], ?, ?)
                """;
        jdbc.execute((java.sql.Connection con) -> {
            Array aclArray = con.createArrayOf("text", chunk.aclTags().toArray());
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, chunk.id());
                ps.setString(2, chunk.sourceFileId());
                ps.setString(3, chunk.sourceName());
                ps.setString(4, chunk.sourcePath());
                ps.setString(5, chunk.content());
                ps.setString(6, vectorLit);
                ps.setArray(7, aclArray);
                ps.setInt(8, chunk.position());
                ps.setInt(9, chunk.tokenCount());
                return ps.executeUpdate();
            }
        });
    }

    public void deleteBySourceFileId(String sourceFileId) {
        jdbc.update("DELETE FROM chunks WHERE source_file_id = ?", sourceFileId);
    }

    /** Vector cosine ANN over the HNSW index — returns chunks best-first. */
    public List<Chunk> vectorSearch(float[] queryEmbedding, int topK) {
        String vectorLit = toVectorLiteral(queryEmbedding);
        String sql = """
                SELECT id, source_file_id, source_name, source_path, content, embedding, acl_tags, position, token_count, created_at
                FROM chunks
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """;
        return jdbc.query(sql, MAPPER, vectorLit, topK);
    }

    /** Lexical full-text search via ts_rank — returns chunks best-first. */
    public List<Chunk> lexicalSearch(String query, int topK) {
        String tsQuery = toTsQuery(query);
        if (tsQuery.isBlank()) return List.of();
        String sql = """
                SELECT id, source_file_id, source_name, source_path, content, embedding, acl_tags, position, token_count, created_at
                FROM chunks
                WHERE tsv @@ to_tsquery('english', ?)
                ORDER BY ts_rank(tsv, to_tsquery('english', ?)) DESC
                LIMIT ?
                """;
        return jdbc.query(sql, MAPPER, tsQuery, tsQuery, topK);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM chunks", Long.class);
        return n == null ? 0 : n;
    }

    private static String toVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private static float[] parseVector(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.replaceAll("[\\[\\]\\s]", "");
        String[] parts = s.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Float.parseFloat(parts[i]);
        return out;
    }

    private static String toTsQuery(String query) {
        String[] terms = query.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").trim().split("\\s+");
        List<String> parts = new ArrayList<>();
        for (String t : terms) if (!t.isBlank()) parts.add(t);
        return String.join(" & ", parts);
    }
}
