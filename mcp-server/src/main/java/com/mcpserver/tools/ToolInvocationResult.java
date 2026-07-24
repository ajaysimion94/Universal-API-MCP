package com.mcpserver.tools;

import java.util.Map;

/**
 * Outcome of one tool execution. {@code body} is the (pretty-printed when JSON) response text,
 * capped — {@code truncated} says whether the cap hit. {@code requestSummary} is
 * "METHOD resolved-url" for display; request auth headers are never included. {@code headers}
 * are the response's headers (multi-valued ones joined with ", ").
 */
public record ToolInvocationResult(
        int status,
        long latencyMs,
        String contentType,
        String body,
        boolean truncated,
        String requestSummary,
        Map<String, String> headers
) {
}
