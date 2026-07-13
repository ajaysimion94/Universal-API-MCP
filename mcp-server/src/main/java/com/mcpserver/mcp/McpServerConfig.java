package com.mcpserver.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The real MCP protocol endpoint (docs §3/§5.3): Streamable HTTP transport served by a plain
 * servlet mounted on the app's own Tomcat at {@code /mcp} — no second server, no Spring AI, and
 * it inherits the {@code server.address=127.0.0.1} guardrail automatically. This class and
 * {@link McpToolBridge} are the ONLY code touching the MCP SDK, so SDK version drift stays
 * contained here.
 */
@Configuration
public class McpServerConfig {

    public static final String MCP_ENDPOINT = "/mcp";

    @Bean
    public McpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper);
    }

    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransport(McpJsonMapper jsonMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint(MCP_ENDPOINT)
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, MCP_ENDPOINT + "/*", MCP_ENDPOINT);
        registration.setName("mcpStreamableHttp");
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(destroyMethod = "closeGracefully")
    public McpSyncServer mcpSyncServer(HttpServletStreamableServerTransportProvider transport) {
        return McpServer.sync(transport)
                .serverInfo("enterprise-mcp-server", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true) // listChanged notifications — tools mutate at runtime (§5.3)
                        .build())
                .build();
    }
}
