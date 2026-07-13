package com.mcpserver.tools;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ApiToolRepository {

    private final JdbcTemplate jdbc;

    public ApiToolRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ApiTool> MAPPER = (rs, rowNum) -> new ApiTool(
            rs.getString("id"),
            rs.getString("connection_id"),
            rs.getString("app_slug"),
            rs.getString("name"),
            rs.getString("request_slug"),
            rs.getString("display_name"),
            rs.getString("description"),
            rs.getString("category"),
            rs.getString("http_method"),
            rs.getString("url_template"),
            rs.getString("params_schema"),
            rs.getString("param_locations"),
            rs.getString("headers"),
            rs.getString("body_template"),
            rs.getString("primary_param"),
            rs.getInt("enabled") != 0,
            rs.getInt("pending") != 0,
            rs.getInt("knowledge_source") != 0,
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );

    public void save(ApiTool t) {
        jdbc.update("""
                INSERT OR REPLACE INTO api_tools
                    (id, connection_id, app_slug, name, request_slug, display_name, description,
                     category, http_method, url_template, params_schema, param_locations, headers,
                     body_template, primary_param, enabled, pending, knowledge_source,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                t.id(), t.connectionId(), t.appSlug(), t.name(), t.requestSlug(), t.displayName(),
                t.description(), t.category(), t.httpMethod(), t.urlTemplate(), t.paramsSchema(),
                t.paramLocations(), t.headers(), t.bodyTemplate(), t.primaryParam(),
                t.enabled() ? 1 : 0, t.pending() ? 1 : 0, t.knowledgeSource() ? 1 : 0,
                t.createdAt().toString(), t.updatedAt().toString()
        );
    }

    public Optional<ApiTool> findById(String id) {
        List<ApiTool> rows = jdbc.query("SELECT * FROM api_tools WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Full tool id ({app_slug}_{request_slug}); unique per connection but not globally — first wins. */
    public List<ApiTool> findByName(String name) {
        return jdbc.query("SELECT * FROM api_tools WHERE name = ? ORDER BY created_at ASC", MAPPER, name);
    }

    public List<ApiTool> findByConnectionId(String connectionId) {
        return jdbc.query("SELECT * FROM api_tools WHERE connection_id = ? ORDER BY category, name",
                MAPPER, connectionId);
    }

    public List<ApiTool> findAllEnabled() {
        return jdbc.query("SELECT * FROM api_tools WHERE enabled = 1 ORDER BY name", MAPPER);
    }

    public List<ApiTool> findAll() {
        return jdbc.query("SELECT * FROM api_tools ORDER BY app_slug, category, name", MAPPER);
    }

    public List<ApiTool> findKnowledgeSources(String connectionId) {
        return jdbc.query("""
                SELECT * FROM api_tools
                WHERE connection_id = ? AND knowledge_source = 1 AND enabled = 1
                ORDER BY name
                """, MAPPER, connectionId);
    }

    public void deleteById(String id) {
        jdbc.update("DELETE FROM api_tools WHERE id = ?", id);
    }

    public void deleteByConnectionId(String connectionId) {
        jdbc.update("DELETE FROM api_tools WHERE connection_id = ?", connectionId);
    }
}
