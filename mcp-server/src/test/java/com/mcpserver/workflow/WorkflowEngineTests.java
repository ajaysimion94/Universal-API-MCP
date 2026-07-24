package com.mcpserver.workflow;

import com.mcpserver.audit.AuditRepository;
import com.mcpserver.audit.AuditService;
import com.mcpserver.connectors.*;
import com.mcpserver.tools.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
public class WorkflowEngineTests {

    @Autowired
    private WorkflowEngine workflowEngine;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditRepository auditRepository;

    @MockBean
    private ApiToolExecutor apiToolExecutor;

    @MockBean
    private ApiToolService apiToolService;

    @MockBean
    private ConnectionService connectionService;

    private ApiTool mockTool;
    private Connection mockConnection;

    @BeforeEach
    void setUp() {
        mockTool = new ApiTool(
                "test-tool-id",
                "test-connection-id",
                "testapp",
                "testapp_create_item",
                "create_item",
                "Create Item",
                "Creates an item",
                "WRITE",
                "POST",
                "/items",
                "{\"type\":\"object\"}",
                "{}",
                "{}",
                "{}",
                null,
                true,
                false,
                false,
                Instant.now(),
                Instant.now()
        );

        mockConnection = Connection.create(
                ConnectionType.API_COLLECTION,
                "Test App",
                "https://api.example.com",
                AuthMode.NONE,
                null,
                null,
                java.util.List.of()
        );

        when(apiToolService.findById("test-tool-id")).thenReturn(mockTool);
        when(connectionService.findById("test-connection-id")).thenReturn(mockConnection);
    }

    @Test
    void testWriteToolWorkflowInitiation() throws Exception {
        Map<String, Object> previewMap = Map.of("method", "POST", "url", "https://api.example.com/items");
        when(apiToolExecutor.renderPreview(eq(mockTool), eq(mockConnection), any(), any())).thenReturn(previewMap);

        Map<String, Object> args = Map.of("name", "Item 1");
        WorkflowExecution execution = workflowEngine.initiateWriteTool(mockTool, mockConnection, args, "user-1");

        assertThat(execution).isNotNull();
        assertThat(execution.toolId()).isEqualTo(mockTool.id());
        assertThat(execution.state()).isEqualTo(WorkflowState.AWAITING_CONFIRMATION);
        assertThat(execution.confirmationToken()).isNotNull();
        assertThat(execution.tokenExpiresAt()).isAfter(Instant.now());
        assertThat(execution.actor()).isEqualTo("user-1");

        // Verify it was persisted
        Optional<WorkflowExecution> retrieved = workflowRepository.findByToken(execution.confirmationToken());
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().id()).isEqualTo(execution.id());
    }

    @Test
    void testWorkflowConfirmation() throws Exception {
        Map<String, Object> previewMap = Map.of("method", "POST", "url", "https://api.example.com/items");
        when(apiToolExecutor.renderPreview(eq(mockTool), eq(mockConnection), any(), any())).thenReturn(previewMap);

        ToolInvocationResult mockResult = new ToolInvocationResult(201, 150, "application/json", "{\"id\": 1}", false, "POST https://api.example.com/items", Map.of());
        when(apiToolExecutor.execute(eq(mockTool), eq(mockConnection), any(), any())).thenReturn(mockResult);

        Map<String, Object> args = Map.of("name", "Item 1");
        WorkflowExecution execution = workflowEngine.initiateWriteTool(mockTool, mockConnection, args, "user-1");

        WorkflowExecution confirmed = workflowEngine.confirm(execution.confirmationToken(), "user-1");

        assertThat(confirmed.state()).isEqualTo(WorkflowState.CONFIRMED);
        assertThat(confirmed.result()).contains("201");
        assertThat(confirmed.result()).contains("150");

        // Verify other actor mismatch throws SecurityException on a new workflow execution
        WorkflowExecution execution2 = workflowEngine.initiateWriteTool(mockTool, mockConnection, args, "user-1");
        assertThatThrownBy(() -> workflowEngine.confirm(execution2.confirmationToken(), "other-user"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void testWorkflowRejection() throws Exception {
        Map<String, Object> previewMap = Map.of("method", "POST", "url", "https://api.example.com/items");
        when(apiToolExecutor.renderPreview(eq(mockTool), eq(mockConnection), any(), any())).thenReturn(previewMap);

        Map<String, Object> args = Map.of("name", "Item 1");
        WorkflowExecution execution = workflowEngine.initiateWriteTool(mockTool, mockConnection, args, "user-1");

        WorkflowExecution rejected = workflowEngine.reject(execution.confirmationToken(), "user-1");

        assertThat(rejected.state()).isEqualTo(WorkflowState.REJECTED);

        // Verify confirmation tokens are single-use
        assertThatThrownBy(() -> workflowEngine.confirm(execution.confirmationToken(), "user-1"))
                .isInstanceOf(IllegalStateException.class);
    }
}
