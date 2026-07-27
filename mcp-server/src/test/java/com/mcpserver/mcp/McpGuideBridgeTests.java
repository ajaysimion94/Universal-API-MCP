package com.mcpserver.mcp;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class McpGuideBridgeTests {

    @Autowired
    private McpSyncServer mcpServer;

    @Test
    void publishesGuideResourcesAndPromptsForMcpClients() {
        assertThat(mcpServer.listResources()).extracting(McpSchema.Resource::uri)
                .contains(McpGuideBridge.OPERATING_GUIDE_URI, McpGuideBridge.LLM_PLAYBOOK_URI);
        assertThat(mcpServer.listPrompts()).extracting(McpSchema.Prompt::name)
                .contains("orient-to-enterprise-mcp", "execute-grounded-task");
    }
}
