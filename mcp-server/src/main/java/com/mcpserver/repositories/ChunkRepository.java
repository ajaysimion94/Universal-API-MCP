package com.mcpserver.repositories;

import com.mcpserver.models.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.jdbc.core.ConnectionCallback;

@Repository
public class ChunkRepository {

    private static final Logger log = LoggerFactory.getLogger(ChunkRepository.class);

    private final JdbcTemplate jdbc;
    private volatile boolean vec0Available = false;

    public ChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enableVec0() {
        this.vec0Available = true;
    }

    public boolean isVec0Available() {
        return vec0Available;
    }

    public void createVec0Table() {
        try {
            jdbc.execute("CREATE VIRTUAL TABLE IF NOT EXISTS chunks_vec USING vec0(chunk_id TEXT PRIMARY KEY, embedding float[768])");
            this.vec0Available = true;
            log.info("chunks_vec (vec0) virtual table created/verified");
        } catch (Exception e) {
            log.warn("Failed to create chunks_vec table (sqlite-vec extension may not be loaded): {}", e.getMessage());
            this.vec0Available = false;
        }
    }

    public void loadVecExtensionAndInit(String extensionPath) {
        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (Statement st = con.createStatement()) {
                st.execute("SELECT load_extension('" + extensionPath.replace("'", "''") + "')");
                log.info("Loaded sqlite-vec extension from {}", extensionPath);
                st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS chunks_vec USING vec0(chunk_id TEXT PRIMARY KEY, embedding float[768])");
                log.info("chunks_vec (vec0) virtual table created/verified");
                vec0Available = true;
            }
            return null;
        });
    }

    private static final RowMapper<Chunk> MAPPER = (rs, rowNum) -> {
        String aclJson = rs.getString("acl_tags");
        List<String> acl = parseJsonArray(aclJson);
        float[] embedding = null;
        String embStr = rs.getString("embedding");
        if (embStr != null && !embStr.isBlank()) {
            embedding = parseJsonFloatArray(embStr);
        }
        String sourceUpdatedAtStr = rs.getString("updated_at");
        return new Chunk(
                rs.getString("id"),
                rs.getString("source_file_id"),
                rs.getString("source_name"),
                rs.getString("source_path"),
                rs.getString("content"),
                embedding,
                acl,
                rs.getInt("position"),
                rs.getInt("token_count"),
                java.time.Instant.parse(rs.getString("created_at")),
                rs.getString("source_system"),
                rs.getString("external_id"),
                rs.getString("url"),
                sourceUpdatedAtStr == null ? null : java.time.Instant.parse(sourceUpdatedAtStr)
        );
    };

    public void save(Chunk chunk) {
        String embeddingJson = chunk.embedding() != null ? toJsonFloatArray(chunk.embedding()) : null;
        String aclJson = toJsonStringArray(chunk.aclTags());
        String sourceSystem = chunk.sourceSystem() != null ? chunk.sourceSystem() : "upload";
        String sourceUpdatedAt = chunk.sourceUpdatedAt() != null ? chunk.sourceUpdatedAt().toString() : null;
        jdbc.update(
                "INSERT OR REPLACE INTO chunks (id, source_file_id, source_name, source_path, content, embedding, acl_tags, position, token_count, source_system, external_id, url, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                chunk.id(), chunk.sourceFileId(), chunk.sourceName(), chunk.sourcePath(),
                chunk.content(), embeddingJson, aclJson, chunk.position(), chunk.tokenCount(),
                sourceSystem, chunk.externalId(), chunk.url(), sourceUpdatedAt
        );
        jdbc.update(
                "INSERT OR REPLACE INTO chunks_fts (content, chunk_id) VALUES (?, ?)",
                chunk.content(), chunk.id()
        );
        if (vec0Available && embeddingJson != null) {
            try {
                jdbc.update(
                        "INSERT OR REPLACE INTO chunks_vec (chunk_id, embedding) VALUES (?, ?)",
                        chunk.id(), embeddingJson
                );
            } catch (Exception e) {
                log.warn("Failed to insert into chunks_vec (vec0 may not be ready): {}", e.getMessage());
            }
        }
    }

    public void deleteBySourceFileId(String sourceFileId) {
        List<String> chunkIds = jdbc.queryForList(
                "SELECT id FROM chunks WHERE source_file_id = ?", String.class, sourceFileId);
        jdbc.update("DELETE FROM chunks WHERE source_file_id = ?", sourceFileId);
        deleteFromSecondaryLegs(chunkIds);
    }

    /**
     * Purges every chunk whose {@code source_file_id} starts with {@code prefix} — used when a
     * connection is deleted, since connector chunks use {@code "{connectionId}:{externalId}"} as
     * their source_file_id and there is no single source_file_id to delete by.
     */
    public void deleteBySourceFileIdPrefix(String prefix) {
        List<String> chunkIds = jdbc.queryForList(
                "SELECT id FROM chunks WHERE source_file_id LIKE ?", String.class, prefix + "%");
        jdbc.update("DELETE FROM chunks WHERE source_file_id LIKE ?", prefix + "%");
        deleteFromSecondaryLegs(chunkIds);
    }

    private void deleteFromSecondaryLegs(List<String> chunkIds) {
        for (String id : chunkIds) {
            try {
                jdbc.update("DELETE FROM chunks_fts WHERE chunk_id = ?", id);
            } catch (Exception ignored) {}
            if (vec0Available) {
                try {
                    jdbc.update("DELETE FROM chunks_vec WHERE chunk_id = ?", id);
                } catch (Exception ignored) {}
            }
        }
    }

    public List<Chunk> vectorSearch(float[] queryEmbedding, int topK) {
        if (!vec0Available) return List.of();
        String queryJson = toJsonFloatArray(queryEmbedding);
        String sql = """
                SELECT c.id, c.source_file_id, c.source_name, c.source_path, c.content, c.embedding, c.acl_tags, c.position, c.token_count, c.created_at, c.source_system, c.external_id, c.url, c.updated_at
                FROM chunks_vec v
                JOIN chunks c ON c.id = v.chunk_id
                WHERE v.embedding MATCH ? AND k = ?
                ORDER BY v.distance
                """;
        try {
            return jdbc.query(sql, MAPPER, queryJson, topK);
        } catch (Exception e) {
            log.warn("Vector search failed: {}", e.getMessage());
            return List.of();
        }
    }

    public List<Chunk> lexicalSearch(String query, int topK) {
        String ftsQuery = toFtsQuery(query);
        if (ftsQuery.isBlank()) return List.of();
        String sql = """
                SELECT c.id, c.source_file_id, c.source_name, c.source_path, c.content, c.embedding, c.acl_tags, c.position, c.token_count, c.created_at, c.source_system, c.external_id, c.url, c.updated_at
                FROM chunks_fts f
                JOIN chunks c ON c.id = f.chunk_id
                WHERE f.content MATCH ?
                ORDER BY bm25(chunks_fts)
                LIMIT ?
                """;
        try {
            return jdbc.query(sql, MAPPER, ftsQuery, topK);
        } catch (Exception e) {
            log.warn("Lexical search failed: {}", e.getMessage());
            return List.of();
        }
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM chunks", Long.class);
        return n == null ? 0 : n;
    }

    private static String toJsonFloatArray(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private static String toJsonStringArray(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(list.get(i).replace("\"", "\\\"")).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank() || json.equals("[]")) return List.of();
        json = json.trim();
        if (json.startsWith("[")) json = json.substring(1);
        if (json.endsWith("]")) json = json.substring(0, json.length() - 1);
        if (json.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : json.split(",")) {
            String s = part.trim();
            if (s.startsWith("\"")) s = s.substring(1);
            if (s.endsWith("\"")) s = s.substring(0, s.length() - 1);
            s = s.replace("\\\"", "\"");
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static float[] parseJsonFloatArray(String json) {
        if (json == null || json.isBlank()) return null;
        json = json.trim();
        if (json.startsWith("[")) json = json.substring(1);
        if (json.endsWith("]")) json = json.substring(0, json.length() - 1);
        if (json.isBlank()) return null;
        String[] parts = json.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Float.parseFloat(parts[i].trim());
        }
        return out;
    }

    private static String toFtsQuery(String query) {
        String[] terms = query.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").trim().split("\\s+");
        List<String> parts = new ArrayList<>();
        for (String t : terms) if (!t.isBlank()) parts.add(t);
        return String.join(" ", parts);
    }
}
