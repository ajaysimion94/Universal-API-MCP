package com.mcpserver.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.guides.GuideCatalog;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Publishes the same guide used by the web UI as first-class MCP resources and prompts. This keeps
 * client orientation discoverable through the protocol instead of requiring a model/client vendor
 * to hard-code private setup instructions.
 */
@Component
public class McpGuideBridge {

    public static final String OPERATING_GUIDE_URI = "mcp://enterprise-mcp/guides/operating-guide";
    public static final String LLM_PLAYBOOK_URI = "mcp://enterprise-mcp/guides/llm-playbook.json";

    private static final Logger log = LoggerFactory.getLogger(McpGuideBridge.class);

    private final McpSyncServer mcpServer;
    private final GuideCatalog guideCatalog;
    private final ObjectMapper objectMapper;
    private boolean registered;

    public McpGuideBridge(McpSyncServer mcpServer, GuideCatalog guideCatalog, ObjectMapper objectMapper) {
        this.mcpServer = mcpServer;
        this.guideCatalog = guideCatalog;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void registerOnStartup() {
        if (registered) {
            return;
        }
        mcpServer.addResource(resource(
                OPERATING_GUIDE_URI,
                "enterprise-mcp-operating-guide",
                "Enterprise MCP operating guide",
                "Required workflow and safety rules for an MCP client using this server.",
                "text/markdown",
                guideCatalog.llmGuideMarkdown()));
        mcpServer.addResource(resource(
                LLM_PLAYBOOK_URI,
                "enterprise-mcp-llm-playbook",
                "Enterprise MCP LLM playbook",
                "Machine-readable session, grounding, and action-safety checklist.",
                "application/json",
                serializePlaybook()));
        mcpServer.addPrompt(orientationPrompt());
        mcpServer.addPrompt(groundedTaskPrompt());
        registered = true;
        log.info("MCP guide resources and prompts registered");
    }

    private McpServerFeatures.SyncResourceSpecification resource(String uri, String name, String title,
                                                                   String description, String mimeType, String text) {
        McpSchema.Resource resource = McpSchema.Resource.builder()
                .uri(uri)
                .name(name)
                .title(title)
                .description(description)
                .mimeType(mimeType)
                .size((long) text.length())
                .meta(Map.of("audience", "mcp-client", "guideVersion", "1"))
                .build();
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) ->
                new McpSchema.ReadResourceResult(List.of(
                        McpSchema.TextResourceContents.builder(uri, text).mimeType(mimeType).build())));
    }

    private McpServerFeatures.SyncPromptSpecification orientationPrompt() {
        McpSchema.Prompt prompt = McpSchema.Prompt.builder("orient-to-enterprise-mcp")
                .title("Orient to Enterprise MCP")
                .description("Load the operating guide and establish a grounded, approval-aware workflow.")
                .arguments(List.of())
                .build();
        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, request) ->
                promptResult("Read mcp://enterprise-mcp/guides/operating-guide and "
                        + "mcp://enterprise-mcp/guides/llm-playbook.json before acting. Then call tools/list. "
                        + "Use search-knowledge-base for evidence, and never call confirm-action without explicit "
                        + "current human approval for the exact previewed action."));
    }

    private McpServerFeatures.SyncPromptSpecification groundedTaskPrompt() {
        McpSchema.Prompt prompt = McpSchema.Prompt.builder("execute-grounded-task")
                .title("Execute a grounded task")
                .description("Frame a task around evidence, current tool schemas, and confirmation safety.")
                .arguments(List.of(McpSchema.PromptArgument.builder("task")
                        .title("Task")
                        .description("The user's requested outcome.")
                        .required(true)
                        .build()))
                .build();
        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, request) -> {
            Object task = request.arguments().get("task");
            String requestedTask = task == null ? "Complete the requested task." : String.valueOf(task).strip();
            return promptResult("Task: " + requestedTask + "\n\n"
                    + "First read mcp://enterprise-mcp/guides/operating-guide and inspect tools/list. "
                    + "Search for evidence before answering. If an action is needed, validate the live schema; "
                    + "for a write, present its preview and wait for explicit human approval before confirmation.");
        });
    }

    private McpSchema.GetPromptResult promptResult(String text) {
        return new McpSchema.GetPromptResult(
                "Enterprise MCP workflow prompt",
                List.of(new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text))));
    }

    private String serializePlaybook() {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(guideCatalog.llmPlaybook());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize MCP LLM playbook", ex);
        }
    }
}
