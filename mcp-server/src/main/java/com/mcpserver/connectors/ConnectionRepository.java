package com.mcpserver.connectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class ConnectionRepository {

    private final JdbcTemplate jdbc;

    public ConnectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Connection> MAPPER = (rs, rowNum) -> {
        String lastSyncedAt = rs.getString("last_synced_at");
        return new Connection(
                rs.getString("id"),
                ConnectionType.valueOf(rs.getString("type")),
                rs.getString("name"),
                rs.getString("base_url"),
                DeploymentType.valueOf(rs.getString("deployment_type")),
                AuthMode.valueOf(rs.getString("auth_mode")),
                rs.getString("auth_username"),
                rs.getString("auth_secret_encrypted"),
                ConnectionStatus.valueOf(rs.getString("status")),
                rs.getString("last_error"),
                rs.getString("sync_cursor"),
                rs.getInt("webhook_registered") != 0,
                parseJsonArray(rs.getString("acl_scope")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")),
                lastSyncedAt == null ? null : Instant.parse(lastSyncedAt),
                rs.getString("spec_source_url"),
                rs.getString("spec_format"),
                rs.getString("spec_document"),
                parseApiUrlMode(rs.getString("api_url_mode"))
        );
    };

    public void save(Connection c) {
        jdbc.update("""
                INSERT OR REPLACE INTO connections
                    (id, type, name, base_url, deployment_type, auth_mode, auth_username,
                     auth_secret_encrypted, status, last_error, sync_cursor, webhook_registered,
                     acl_scope, created_at, updated_at, last_synced_at,
                     spec_source_url, spec_format, spec_document, api_url_mode)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                c.id(), c.type().name(), c.name(), c.baseUrl(), c.deploymentType().name(),
                c.authMode().name(), c.authUsername(), c.authSecretEncrypted(), c.status().name(),
                c.lastError(), c.syncCursor(), c.webhookRegistered() ? 1 : 0,
                toJsonStringArray(c.aclScope()), c.createdAt().toString(), c.updatedAt().toString(),
                c.lastSyncedAt() == null ? null : c.lastSyncedAt().toString(),
                c.specSourceUrl(), c.specFormat(), c.specDocument(), c.apiUrlMode().name()
        );
    }

    public Optional<Connection> findById(String id) {
        List<Connection> rows = jdbc.query("SELECT * FROM connections WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Connection> findAll() {
        return jdbc.query("SELECT * FROM connections ORDER BY created_at ASC", MAPPER);
    }

    public List<Connection> findByStatus(ConnectionStatus status) {
        return jdbc.query("SELECT * FROM connections WHERE status = ?", MAPPER, status.name());
    }

    public void deleteById(String id) {
        jdbc.update("DELETE FROM connections WHERE id = ?", id);
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

    private static ApiUrlMode parseApiUrlMode(String raw) {
        if (raw == null || raw.isBlank()) return ApiUrlMode.CONNECTION_BASE;
        try {
            return ApiUrlMode.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return ApiUrlMode.CONNECTION_BASE;
        }
    }
}
