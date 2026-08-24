package com.mcpserver.connectors;

/** Stable, non-sensitive categories surfaced by connection jobs and metrics. */
public enum ConnectorFailureCategory {
    UNREACHABLE,
    TLS,
    AUTHENTICATION,
    PERMISSION,
    RATE_LIMIT,
    REMOTE,
    UNKNOWN
}
