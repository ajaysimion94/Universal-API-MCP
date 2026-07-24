package com.mcpserver.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class AuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<AuditEvent> rowMapper = (rs, rowNum) -> new AuditEvent(
            rs.getLong("id"),
            AuditEventType.valueOf(rs.getString("event_type")),
            rs.getString("tool_id"),
            rs.getString("tool_name"),
            rs.getString("workflow_id"),
            rs.getString("actor"),
            rs.getString("arguments"),
            rs.getString("result_summary"),
            rs.getString("error"),
            rs.getString("ip_address"),
            rs.getString("user_agent"),
            rs.getString("created_at") != null ? Instant.parse(rs.getString("created_at")) : null
    );

    public void log(AuditEvent event) {
        String sql = """
            INSERT INTO audit_log (
                event_type, tool_id, tool_name, workflow_id, actor, arguments, 
                result_summary, error, ip_address, user_agent, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        jdbcTemplate.update(sql,
                event.eventType().name(),
                event.toolId(),
                event.toolName(),
                event.workflowId(),
                event.actor(),
                event.arguments(),
                event.resultSummary(),
                event.error(),
                event.ipAddress(),
                event.userAgent(),
                event.createdAt() != null ? event.createdAt().toString() : Instant.now().toString()
        );
    }

    public List<AuditEvent> query(String actor, String toolName, AuditEventType eventType, Instant from, Instant to, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_log WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (actor != null && !actor.isBlank()) {
            sql.append("AND actor = ? ");
            params.add(actor);
        }
        if (toolName != null && !toolName.isBlank()) {
            sql.append("AND tool_name = ? ");
            params.add(toolName);
        }
        if (eventType != null) {
            sql.append("AND event_type = ? ");
            params.add(eventType.name());
        }
        if (from != null) {
            sql.append("AND created_at >= ? ");
            params.add(from.toString());
        }
        if (to != null) {
            sql.append("AND created_at <= ? ");
            params.add(to.toString());
        }

        sql.append("ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(size);
        params.add(page * size);

        return jdbcTemplate.query(sql.toString(), rowMapper, params.toArray());
    }

    public long count(String actor, String toolName, AuditEventType eventType, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM audit_log WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (actor != null && !actor.isBlank()) {
            sql.append("AND actor = ? ");
            params.add(actor);
        }
        if (toolName != null && !toolName.isBlank()) {
            sql.append("AND tool_name = ? ");
            params.add(toolName);
        }
        if (eventType != null) {
            sql.append("AND event_type = ? ");
            params.add(eventType.name());
        }
        if (from != null) {
            sql.append("AND created_at >= ? ");
            params.add(from.toString());
        }
        if (to != null) {
            sql.append("AND created_at <= ? ");
            params.add(to.toString());
        }

        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return count != null ? count : 0L;
    }
}
