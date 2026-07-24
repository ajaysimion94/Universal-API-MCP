package com.mcpserver.audit;

import java.time.Instant;

public record AuditEvent(
    Long id,
    AuditEventType eventType,
    String toolId,
    String toolName,
    String workflowId,
    String actor,
    String arguments,
    String resultSummary,
    String error,
    String ipAddress,
    String userAgent,
    Instant createdAt
) {}
