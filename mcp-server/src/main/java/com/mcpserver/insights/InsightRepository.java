package com.mcpserver.insights;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class InsightRepository {

    private static final RowMapper<SavedInsight> MAPPER = (rs, rowNum) -> new SavedInsight(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("source"),
            rs.getString("connection_id"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at")));

    private final JdbcTemplate jdbc;

    public InsightRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest first — the list doubles as "what was I just working on". */
    public List<SavedInsight> findAll() {
        return jdbc.query("SELECT * FROM insights ORDER BY updated_at DESC", MAPPER);
    }

    public Optional<SavedInsight> findById(String id) {
        return jdbc.query("SELECT * FROM insights WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public void insert(SavedInsight insight) {
        jdbc.update("""
                INSERT INTO insights (id, name, description, source, connection_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                insight.id(), insight.name(), insight.description(), insight.source(),
                insight.connectionId(), insight.createdAt().toString(), insight.updatedAt().toString());
    }

    public void update(SavedInsight insight) {
        jdbc.update("""
                UPDATE insights SET name = ?, description = ?, source = ?, connection_id = ?, updated_at = ?
                WHERE id = ?
                """,
                insight.name(), insight.description(), insight.source(), insight.connectionId(),
                insight.updatedAt().toString(), insight.id());
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM insights WHERE id = ?", id);
    }
}
