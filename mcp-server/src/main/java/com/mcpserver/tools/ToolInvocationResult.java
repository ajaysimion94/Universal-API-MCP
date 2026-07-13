package com.mcpserver.tools;

/**
 * Outcome of one tool execution. {@code body} is the (pretty-printed when JSON) response text,
 * capped — {@code truncated} says whether the cap hit. {@code requestSummary} is
 * "METHOD resolved-url" for display; auth headers are never included.
 */
public record ToolInvocationResult(
        int status,
        long latencyMs,
        String contentType,
        String body,
        boolean truncated,
        String requestSummary
) {
}
