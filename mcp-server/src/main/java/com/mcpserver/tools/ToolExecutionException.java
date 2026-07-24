package com.mcpserver.tools;

/**
 * The target API couldn't be reached or timed out — distinct from {@link ToolValidationException}
 * (bad args) and {@link IllegalStateException} (tool disabled/pending/rate-limited). Carries a
 * message specific enough to act on (which host, connect vs. timeout) instead of the generic
 * "Internal Server Error" a raw {@code IOException} would surface as.
 */
public class ToolExecutionException extends RuntimeException {

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
