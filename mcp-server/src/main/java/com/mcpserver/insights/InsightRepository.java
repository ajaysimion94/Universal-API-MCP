package com.mcpserver.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class InsightRepository {

    /**
     * Everything except the run snapshot. The library sidebar loads every insight, so the blob is
     * deliberately excluded there — {@code last_run_at} is cheap and still lets a list show when a
     * document last ran.
     */
    private static final String LIST_COLUMNS =
            "id, name, description, source, connection_id, created_at, updated_at, last_run_at";

    private static final String FULL_COLUMNS = LIST_COLUMNS + ", last_run";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final RowMapper<SavedInsight> LIST_MAPPER = (rs, rowNum) -> base(rs, null);

    private static final RowMapper<SavedInsight> FULL_MAPPER =
            (rs, rowNum) -> base(rs, parseJson(rs.getString("last_run")));

    private static SavedInsight base(ResultSet rs, JsonNode lastRun) throws SQLException {
        return new SavedInsight(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("source"),
                rs.getString("connection_id"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")),
                lastRun,
                instantOrNull(rs, "last_run_at"));
    }

    /** {@code Instant.parse(null)} throws, and last_run_at is null on every never-run row. */
    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    /** A corrupt snapshot must never stop an insight from opening — it just reads back as absent. */
    private static JsonNode parseJson(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return JSON.readTree(value);
        } catch (Exception exception) {
            return null;
        }
    }

    private final JdbcTemplate jdbc;

    public InsightRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A workspace is expected to hold many insights, so the library query is bounded rather than
     * unbounded — the panel it feeds shows a recency-ordered list, not a full archive.
     */
    public static final int DEFAULT_LIMIT = 200;

    /** Newest first — the list doubles as "what was I just working on". */
    public List<SavedInsight> findAll() {
        return findAll(DEFAULT_LIMIT);
    }

    /** Never selects {@code last_run}: this feeds the library, and the blobs would dwarf the list. */
    public List<SavedInsight> findAll(int limit) {
        int bounded = Math.max(1, Math.min(limit, 1000));
        return jdbc.query("SELECT " + LIST_COLUMNS + " FROM insights ORDER BY updated_at DESC LIMIT ?",
                LIST_MAPPER, bounded);
    }

    /** The only read that carries the run snapshot, which is why opening an insight goes through it. */
    public Optional<SavedInsight> findById(String id) {
        return jdbc.query("SELECT " + FULL_COLUMNS + " FROM insights WHERE id = ?", FULL_MAPPER, id)
                .stream().findFirst();
    }

    public void insert(SavedInsight insight) {
        jdbc.update("""
                INSERT INTO insights (id, name, description, source, connection_id, created_at, updated_at,
                                      last_run, last_run_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                insight.id(), insight.name(), insight.description(), insight.source(),
                insight.connectionId(), insight.createdAt().toString(), insight.updatedAt().toString(),
                insight.lastRun() == null ? null : insight.lastRun().toString(),
                insight.lastRunAt() == null ? null : insight.lastRunAt().toString());
    }

    /**
     * Edits the document only. Like {@code created_at}, the run snapshot is not part of an edit —
     * writing it here would wipe the stored result on every save.
     */
    public void update(SavedInsight insight) {
        jdbc.update("""
                UPDATE insights SET name = ?, description = ?, source = ?, connection_id = ?, updated_at = ?
                WHERE id = ?
                """,
                insight.name(), insight.description(), insight.source(), insight.connectionId(),
                insight.updatedAt().toString(), insight.id());
    }

    /**
     * Snapshot write only: never touches the document or {@code updated_at}. A run is not an edit,
     * so it must not reorder the recency-sorted library.
     */
    public void updateLastRun(String id, String lastRunJson, Instant lastRunAt) {
        jdbc.update("UPDATE insights SET last_run = ?, last_run_at = ? WHERE id = ?",
                lastRunJson, lastRunAt == null ? null : lastRunAt.toString(), id);
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM insights WHERE id = ?", id);
    }
}
