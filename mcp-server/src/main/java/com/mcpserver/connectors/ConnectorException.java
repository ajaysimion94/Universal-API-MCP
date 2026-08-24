package com.mcpserver.connectors;

/**
 * A connector error safe to show to an operator. Remote response bodies deliberately never cross
 * this boundary: they can contain tenant data or implementation details that do not help recovery.
 */
public class ConnectorException extends IllegalStateException {

    private final ConnectorFailureCategory category;

    public ConnectorException(ConnectorFailureCategory category, String message) {
        super(message);
        this.category = category;
    }

    public ConnectorException(ConnectorFailureCategory category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public ConnectorFailureCategory category() {
        return category;
    }

    public static ConnectorException forHttp(String source, String operation, int statusCode) {
        ConnectorFailureCategory category = switch (statusCode) {
            case 401 -> ConnectorFailureCategory.AUTHENTICATION;
            case 403 -> ConnectorFailureCategory.PERMISSION;
            case 429 -> ConnectorFailureCategory.RATE_LIMIT;
            default -> ConnectorFailureCategory.REMOTE;
        };
        return new ConnectorException(category,
                source + " " + operation + " failed (HTTP " + statusCode + ")");
    }
}
