package com.mcpserver.mcp;

import com.mcpserver.connectors.Connection;
import com.mcpserver.connectors.ConnectionService;
import com.mcpserver.rag.retrieval.SearchPipeline;
import com.mcpserver.tools.ApiTool;
import com.mcpserver.tools.ApiToolExecutor;
import com.mcpserver.tools.ApiToolService;
import com.mcpserver.tools.ToolInvocationResult;
import com.mcpserver.tools.ToolValidationException;
import com.mcpserver.tools.ToolsChangedEvent;
import com.mcpserver.workflow.WorkflowEngine;
import com.mcpserver.workflow.WorkflowExecution;
import com.mcpserver.workflow.WorkflowState;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Syncs the {@code api_tools} registry onto the live MCP server: every enabled imported tool is
 * an MCP tool named {@code {app}_{request-name}} (§5.3), registered at startup and mutated at
 * runtime via addTool/removeTool + notifyToolsListChanged whenever an import/enable/disable
 * happens — no restarts (§8 step 5). Also registers the built-in {@code search-knowledge-base}
 * tool wrapping the RAG pipeline (the Phase-1 catalogue stub, finally real), and the
 * {@code confirm-action} tool for Phase-3 governance approvals.
 *
 * <p>Pure event listener: nothing else depends on this class, so an SDK break degrades MCP
 * exposure only — REST and search invocation keep working.
 */
@Component
public class McpToolBridge {

    private static final Logger log = LoggerFactory.getLogger(McpToolBridge.class);
    private static final String SEARCH_TOOL = "search-knowledge-base";
    private static final String SEARCH_SCHEMA = """
            {"type":"object","properties":{
              "query":{"type":"string","description":"What to search for"},
              "topK":{"type":"integer","description":"Maximum results","default":10}},
             "required":["query"]}""";

    private static final String CONFIRM_TOOL = "confirm-action";
    private static final String CONFIRM_SCHEMA = """
            {"type":"object","properties":{
              "token":{"type":"string","description":"The confirmation token to approve and execute the action"}},
             "required":["token"]}""";

    private final McpSyncServer mcpServer;
    private final McpJsonMapper jsonMapper;
    private final ApiToolService apiToolService;
    private final ApiToolExecutor apiToolExecutor;
    private final ConnectionService connectionService;
    private final SearchPipeline searchPipeline;
    private final WorkflowEngine workflowEngine;

    /** Names currently registered on the MCP server (imported tools only, not built-ins). */
    private final Set<String> registered = new HashSet<>();

    public McpToolBridge(McpSyncServer mcpServer,
                         McpJsonMapper jsonMapper,
                         ApiToolService apiToolService,
                         ApiToolExecutor apiToolExecutor,
                         ConnectionService connectionService,
                         SearchPipeline searchPipeline,
                         WorkflowEngine workflowEngine) {
        this.mcpServer = mcpServer;
        this.jsonMapper = jsonMapper;
        this.apiToolService = apiToolService;
        this.apiToolExecutor = apiToolExecutor;
        this.connectionService = connectionService;
        this.searchPipeline = searchPipeline;
        this.workflowEngine = workflowEngine;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void registerAllOnStartup() {
        mcpServer.addTool(searchKnowledgeBaseSpec());
        mcpServer.addTool(confirmActionSpec());
        for (ApiTool tool : apiToolService.findAllEnabled()) {
            addTool(tool);
        }
        log.info("MCP server ready at {} — {} imported tools + {}, {}",
                McpServerConfig.MCP_ENDPOINT, registered.size(), SEARCH_TOOL, CONFIRM_TOOL);
    }

    /** Import/enable/disable/delete → reconcile the MCP tool list and notify clients. */
    @EventListener
    public synchronized void onToolsChanged(ToolsChangedEvent event) {
        Set<String> wanted = new HashSet<>();
        List<ApiTool> enabled = apiToolService.findAllEnabled();
        for (ApiTool tool : enabled) {
            wanted.add(tool.name());
            if (!registered.contains(tool.name())) {
                addTool(tool);
            }
        }
        for (String name : new HashSet<>(registered)) {
            if (!wanted.contains(name)) {
                try {
                    mcpServer.removeTool(name);
                } catch (Exception e) {
                    log.warn("Failed to remove MCP tool {}: {}", name, e.getMessage());
                }
                registered.remove(name);
            }
        }
        try {
            mcpServer.notifyToolsListChanged();
        } catch (Exception e) {
            log.debug("tools/list_changed notification failed (no clients yet?): {}", e.getMessage());
        }
        log.info("MCP tool list reconciled after change on connection {} — {} tools live",
                event.connectionId(), registered.size());
    }

    private void addTool(ApiTool tool) {
        try {
            McpSchema.Tool mcpTool = McpSchema.Tool.builder()
                    .name(tool.name())
                    .title(tool.displayName())
                    .description(description(tool))
                    .inputSchema(jsonMapper, tool.paramsSchema())
                    .build();
            McpServerFeatures.SyncToolSpecification spec = McpServerFeatures.SyncToolSpecification.builder()
                    .tool(mcpTool)
                    .callHandler((exchange, request) -> callApiTool(tool.id(), request))
                    .build();
            mcpServer.addTool(spec);
            registered.add(tool.name());
        } catch (Exception e) {
            log.warn("Failed to register MCP tool {}: {}", tool.name(), e.getMessage());
        }
    }

    /**
     * MCP call → the same executor REST and search use. The tool is re-read at call time so
     * disable/delete between registration and invocation can't execute a stale tool.
     */
    private McpSchema.CallToolResult callApiTool(String toolId, McpSchema.CallToolRequest request) {
        try {
            ApiTool tool = apiToolService.findById(toolId);
            Connection connection = connectionService.findById(tool.connectionId());
            Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

            if (tool.isRead()) {
                ToolInvocationResult result = apiToolExecutor.execute(tool, connection, args);
                return McpSchema.CallToolResult.builder()
                        .addTextContent("HTTP " + result.status() + " (" + result.latencyMs() + " ms) — "
                                + result.requestSummary() + "\n\n" + result.body()
                                + (result.truncated() ? "\n\n[response truncated]" : ""))
                        .isError(result.status() >= 400)
                        .build();
            } else {
                // Write tool: initiate workflow, return confirmation token and preview
                WorkflowExecution execution = workflowEngine.initiateWriteTool(tool, connection, args, "mcp-client");
                return McpSchema.CallToolResult.builder()
                        .addTextContent("State-changing tool requires approval.\n\n"
                                + "Workflow ID: " + execution.id() + "\n"
                                + "Confirmation Token: " + execution.confirmationToken() + "\n"
                                + "Expires At: " + execution.tokenExpiresAt() + "\n\n"
                                + "Preview:\n" + execution.previewPayload() + "\n\n"
                                + "To confirm and execute this action, call the 'confirm-action' tool with the confirmation token:\n"
                                + "confirm-action(token=\"" + execution.confirmationToken() + "\")")
                        .isError(false)
                        .build();
            }
        } catch (ToolValidationException e) {
            // §8 self-correction: schema violation halts execution, returns structured error
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Input validation failed — nothing was executed.\n"
                            + e.violations().stream()
                                .map(v -> "- " + v.param() + " (expected " + v.expected() + "): " + v.message())
                                .reduce("", (a, b) -> a + b + "\n"))
                    .isError(true)
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Tool execution failed: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    private McpServerFeatures.SyncToolSpecification confirmActionSpec() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(CONFIRM_TOOL)
                .title("Confirm a pending action")
                .description("Confirm and execute a state-changing action using its single-use confirmation token.")
                .inputSchema(jsonMapper, CONFIRM_SCHEMA)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
                        String token = String.valueOf(args.get("token"));
                        WorkflowExecution execution = workflowEngine.confirm(token, "mcp-client");
                        if (execution.state() == WorkflowState.CONFIRMED) {
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("Action executed successfully.\n\nResult Summary:\n" + execution.result())
                                    .isError(false)
                                    .build();
                        } else {
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("Action execution failed: " + execution.error())
                                    .isError(true)
                                    .build();
                        }
                    } catch (Exception e) {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("Confirmation failed: " + e.getMessage())
                                .isError(true)
                                .build();
                    }
                })
                .build();
    }

    private McpServerFeatures.SyncToolSpecification searchKnowledgeBaseSpec() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(SEARCH_TOOL)
                .title("Search the knowledge base")
                .description("Hybrid RAG search (vector + lexical, reranked) over everything "
                        + "ingested: uploaded files, Confluence, Jira, and API knowledge sources. "
                        + "Returns cited excerpts with provenance.")
                .inputSchema(jsonMapper, SEARCH_SCHEMA)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
                        String query = String.valueOf(args.getOrDefault("query", ""));
                        int topK = args.get("topK") instanceof Number n ? n.intValue() : 10;
                        List<SearchPipeline.SearchResult> results =
                                searchPipeline.search(query, topK, List.of());
                        StringBuilder sb = new StringBuilder();
                        int rank = 1;
                        for (SearchPipeline.SearchResult r : results) {
                            sb.append(rank++).append(". ").append(r.sourceName());
                            if (r.sourcePath() != null && !r.sourcePath().isBlank()) {
                                sb.append(" (").append(r.sourcePath()).append(')');
                            }
                            sb.append(" [score ").append(String.format("%.3f", r.score())).append("]\n")
                              .append(r.excerpt()).append("\n\n");
                        }
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(results.isEmpty()
                                        ? "No matches. Zero results means zero results — nothing is fabricated."
                                        : sb.toString().stripTrailing())
                                .isError(false)
                                .build();
                    } catch (Exception e) {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("Search failed: " + e.getMessage())
                                .isError(true)
                                .build();
                    }
                })
                .build();
    }

    private static String description(ApiTool tool) {
        String desc = tool.description() == null || tool.description().isBlank()
                ? tool.displayName()
                : tool.description();
        return "[" + tool.httpMethod() + " " + tool.urlTemplate() + "] " + desc
                + (tool.isRead() ? "" : " (state-changing)");
    }
}
