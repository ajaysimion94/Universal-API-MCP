package com.mcpserver.tools;

/**
 * Published whenever the set of enabled tools changes (import, enable/disable, connection delete).
 * The MCP bridge listens and mutates the live tool list (addTool/removeTool +
 * notifyToolsListChanged) — keeping the registry free of any MCP SDK dependency.
 */
public record ToolsChangedEvent(String connectionId) {
}
