package com.mcpserver.workflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
public class WorkflowRepository {
    private final JdbcTemplate jdbcTemplate;

    public WorkflowRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<WorkflowExecution> rowMapper = (rs, rowNum) -> {
        String tokenExpiresAtStr = rs.getString("token_expires_at");
        String createdAtStr = rs.getString("created_at");
        String updatedAtStr = rs.getString("updated_at");

        return new WorkflowExecution(
                rs.getString("id"),
                rs.getString("tool_id"),
                rs.getString("tool_name"),
                WorkflowState.valueOf(rs.getString("state")),
                rs.getString("params"),
                rs.getString("resolved_params"),
                rs.getString("confirmation_token"),
                tokenExpiresAtStr != null ? Instant.parse(tokenExpiresAtStr) : null,
                rs.getString("idempotency_key"),
                rs.getString("actor"),
                rs.getString("preview_payload"),
                rs.getString("result"),
                rs.getString("error"),
                createdAtStr != null ? Instant.parse(createdAtStr) : null,
                updatedAtStr != null ? Instant.parse(updatedAtStr) : null
        );
    };

    public void save(WorkflowExecution execution) {
        String sql = """
                INSERT OR REPLACE INTO workflow_executions (
                    id, tool_id, tool_name, state, params, resolved_params, confirmation_token,
                    token_expires_at, idempotency_key, actor, preview_payload, result, error, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        jdbcTemplate.update(sql,
                execution.id(),
                execution.toolId(),
                execution.toolName(),
                execution.state().name(),
                execution.params(),
                execution.resolvedParams(),
                execution.confirmationToken(),
                execution.tokenExpiresAt() != null ? execution.tokenExpiresAt().toString() : null,
                execution.idempotencyKey(),
                execution.actor(),
                execution.previewPayload(),
                execution.result(),
                execution.error(),
                execution.createdAt() != null ? execution.createdAt().toString() : null,
                execution.updatedAt() != null ? execution.updatedAt().toString() : null
        );
    }

    public Optional<WorkflowExecution> findById(String id) {
        String sql = "SELECT * FROM workflow_executions WHERE id = ?";
        return jdbcTemplate.query(sql, rowMapper, id).stream().findFirst();
    }

    public Optional<WorkflowExecution> findByToken(String token) {
        String sql = "SELECT * FROM workflow_executions WHERE confirmation_token = ?";
        return jdbcTemplate.query(sql, rowMapper, token).stream().findFirst();
    }

    public Optional<WorkflowExecution> findByIdempotencyKey(String key) {
        String sql = "SELECT * FROM workflow_executions WHERE idempotency_key = ?";
        return jdbcTemplate.query(sql, rowMapper, key).stream().findFirst();
    }

    public void updateState(String id, WorkflowState state, String result, String error) {
        String sql = "UPDATE workflow_executions SET state = ?, result = ?, error = ?, updated_at = ? WHERE id = ?";
        jdbcTemplate.update(sql, state.name(), result, error, Instant.now().toString(), id);
    }

    public void expireStaleTokens() {
        String sql = "UPDATE workflow_executions SET state = 'EXPIRED', updated_at = ? WHERE state = 'AWAITING_CONFIRMATION' AND token_expires_at < ?";
        String nowStr = Instant.now().toString();
        jdbcTemplate.update(sql, nowStr, nowStr);
    }
}
