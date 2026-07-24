package com.mcpserver.workflow;

import java.time.Instant;

public record WorkflowExecution(
        String id,
        String toolId,
        String toolName,
        WorkflowState state,
        String params,
        String resolvedParams,
        String confirmationToken,
        Instant tokenExpiresAt,
        String idempotencyKey,
        String actor,
        String previewPayload,
        String result,
        String error,
        Instant createdAt,
        Instant updatedAt
) {
    public WorkflowExecution withState(WorkflowState newState) {
        return new WorkflowExecution(
                id, toolId, toolName, newState, params, resolvedParams,
                confirmationToken, tokenExpiresAt, idempotencyKey, actor,
                previewPayload, result, error, createdAt, Instant.now()
        );
    }

    public boolean isTokenValid() {
        return tokenExpiresAt != null && Instant.now().isBefore(tokenExpiresAt);
    }
}
