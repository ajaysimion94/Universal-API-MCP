package com.mcpserver.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.audit.AuditService;
import com.mcpserver.connectors.Connection;
import com.mcpserver.connectors.ConnectionService;
import com.mcpserver.workflow.WorkflowEngine;
import com.mcpserver.workflow.WorkflowExecution;
import com.mcpserver.workflow.WorkflowState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST surface for imported API tools: listing/autocomplete, the enable/disable approval
 * lifecycle, knowledge-source flagging, and direct invocation (also the confirm step for
 * state-changing tools invoked from search).
 *
 * <p>Phase 3: write-tool invocations go through the {@link WorkflowEngine} —
 * preview → single-use confirmation token → approve/reject → execute → audit.
 * Read tools execute directly as before.
 */
@RestController
@RequestMapping("/api/tools")
public class ApiToolController {

    private final ApiToolService apiToolService;
    private final ApiToolExecutor apiToolExecutor;
    private final ConnectionService connectionService;
    private final WorkflowEngine workflowEngine;
    private final AuditService auditService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiToolController(ApiToolService apiToolService,
                             ApiToolExecutor apiToolExecutor,
                             ConnectionService connectionService,
                             WorkflowEngine workflowEngine,
                             AuditService auditService) {
        this.apiToolService = apiToolService;
        this.apiToolExecutor = apiToolExecutor;
        this.connectionService = connectionService;
        this.workflowEngine = workflowEngine;
        this.auditService = auditService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(value = "query", required = false) String query,
                                          @RequestParam(value = "connectionId", required = false) String connectionId) {
        return apiToolService.search(query, connectionId).stream().map(this::toMap).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return toMap(apiToolService.findById(id));
    }

    @PostMapping("/{id}/enable")
    public Map<String, Object> enable(@PathVariable String id) {
        return toMap(apiToolService.setEnabled(id, true));
    }

    @PostMapping("/{id}/disable")
    public Map<String, Object> disable(@PathVariable String id) {
        return toMap(apiToolService.setEnabled(id, false));
    }

    @PostMapping("/{id}/knowledge-source")
    public Map<String, Object> knowledgeSource(@PathVariable String id,
                                               @RequestBody KnowledgeSourceRequest req) {
        return toMap(apiToolService.setKnowledgeSource(id, req.enabled));
    }

    /** Creates a manual/scratch tool — the request builder's "Save" action for a from-scratch request. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody ManualToolRequest req) {
        Connection connection = connectionService.findById(req.connectionId);
        ApiTool tool = apiToolService.createManual(req.connectionId, connection, req.toInput());
        return ResponseEntity.status(HttpStatus.CREATED).body(toMap(tool));
    }

    /** Updates a manual tool's shape. 409s for imported tools — those are spec-managed. */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody ManualToolRequest req) {
        return toMap(apiToolService.updateManual(id, req.toInput()));
    }

    /** Deletes a manual tool. 409s for imported tools — those are spec-managed. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        apiToolService.deleteManual(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Executes a tool. Read tools (GET) execute directly and return the result.
     * Write tools go through the workflow engine: validate → guard → preview →
     * return a confirmation token. The client must call {@code /confirm/{token}}
     * to execute.
     */
    @PostMapping("/{id}/invoke")
    public Map<String, Object> invoke(@PathVariable String id,
                                      @RequestBody(required = false) InvokeRequest req,
                                      @RequestHeader(value = "X-Actor", defaultValue = "web-user") String actor)
            throws Exception {
        ApiTool tool = apiToolService.findById(id);
        Connection connection = connectionService.findById(tool.connectionId());
        Map<String, Object> args = req != null && req.args != null ? req.args : Map.of();
        ApiToolExecutor.InvokeOverrides overrides = toOverrides(req);

        if (tool.isRead()) {
            // Read tools execute directly — no approval needed
            auditService.logToolInvoked(tool.id(), tool.name(), null, actor, args);
            ToolInvocationResult result = apiToolExecutor.execute(tool, connection, args, overrides);
            auditService.logToolExecuted(tool.id(), tool.name(), null, actor,
                    "HTTP " + result.status());
            Map<String, Object> body = new HashMap<>();
            body.put("tool", tool.name());
            body.put("status", result.status());
            body.put("latencyMs", result.latencyMs());
            body.put("contentType", result.contentType());
            body.put("body", result.body());
            body.put("truncated", result.truncated());
            body.put("request", result.requestSummary());
            body.put("headers", result.headers());
            return body;
        }

        // Write tools → workflow engine → preview + confirmation token
        auditService.logToolInvoked(tool.id(), tool.name(), null, actor, args);
        WorkflowExecution execution = workflowEngine.initiateWriteTool(tool, connection, args, overrides, actor);
        return workflowResponse(execution);
    }

    /**
     * Renders the request a tool would send — method, resolved URL, headers, body — without
     * executing it. Backs the request builder's live "resolved request" readout and code-snippet
     * generation; works for both read and write tools.
     */
    @PostMapping("/{id}/preview")
    public Map<String, Object> preview(@PathVariable String id,
                                       @RequestBody(required = false) InvokeRequest req) throws Exception {
        ApiTool tool = apiToolService.findById(id);
        Connection connection = connectionService.findById(tool.connectionId());
        Map<String, Object> args = req != null && req.args != null ? req.args : Map.of();
        return apiToolExecutor.renderPreview(tool, connection, args, toOverrides(req));
    }

    private ApiToolExecutor.InvokeOverrides toOverrides(InvokeRequest req) {
        if (req == null) return ApiToolExecutor.InvokeOverrides.empty();
        ApiToolExecutor.AuthOverride auth = req.auth == null ? null
                : new ApiToolExecutor.AuthOverride(req.auth.mode, req.auth.username, req.auth.secret,
                        req.auth.headerName);
        return new ApiToolExecutor.InvokeOverrides(
                req.extraHeaders, req.extraQueryParams, req.bodyMode, req.rawBody, req.rawContentType, auth);
    }

    /**
     * Confirms a write-tool execution using its single-use confirmation token.
     * Validates the token (not expired, not reused), executes the tool, and returns
     * the result. Audited.
     */
    @PostMapping("/confirm/{token}")
    public Map<String, Object> confirm(
            @PathVariable String token,
            @RequestHeader(value = "X-Actor", defaultValue = "web-user") String actor) {
        WorkflowExecution execution = workflowEngine.confirm(token, actor);
        if (execution.state() == WorkflowState.CONFIRMED) {
            auditService.logToolApproved(execution.toolId(), execution.toolName(),
                    execution.id(), actor);
            auditService.logToolExecuted(execution.toolId(), execution.toolName(),
                    execution.id(), actor, "Confirmed and executed");
        } else if (execution.state() == WorkflowState.FAILED) {
            auditService.logToolFailed(execution.toolId(), execution.toolName(),
                    execution.id(), actor, execution.error());
        }
        return workflowResponse(execution);
    }

    /**
     * Rejects a write-tool execution. No side effects occur; the rejection is audited.
     */
    @PostMapping("/reject/{token}")
    public Map<String, Object> reject(
            @PathVariable String token,
            @RequestHeader(value = "X-Actor", defaultValue = "web-user") String actor) {
        WorkflowExecution execution = workflowEngine.reject(token, actor);
        auditService.logToolRejected(execution.toolId(), execution.toolName(),
                execution.id(), actor);
        return workflowResponse(execution);
    }

    @ExceptionHandler(ToolValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(ToolValidationException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "error", "Arguments violate the tool's schema",
                "violations", e.violations().stream()
                        .map(v -> Map.of("param", v.param(), "expected", v.expected(), "message", v.message()))
                        .toList()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ToolExecutionException.class)
    public ResponseEntity<Map<String, String>> handleToolExecution(ToolExecutionException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    private Map<String, Object> workflowResponse(WorkflowExecution ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("workflowId", ex.id());
        body.put("toolId", ex.toolId());
        body.put("toolName", ex.toolName());
        body.put("state", ex.state().name());

        if (ex.confirmationToken() != null) {
            body.put("confirmationToken", ex.confirmationToken());
        }
        if (ex.tokenExpiresAt() != null) {
            body.put("tokenExpiresAt", ex.tokenExpiresAt().toString());
        }
        if (ex.previewPayload() != null) {
            try {
                body.put("preview", mapper.readValue(ex.previewPayload(), Map.class));
            } catch (Exception e) {
                body.put("preview", ex.previewPayload());
            }
        }
        if (ex.params() != null) {
            try {
                body.put("args", mapper.readValue(ex.params(), Map.class));
            } catch (Exception e) {
                body.put("args", Map.of());
            }
        }
        if (ex.result() != null) {
            try {
                body.put("result", mapper.readValue(ex.result(), Map.class));
            } catch (Exception e) {
                body.put("result", ex.result());
            }
        }
        if (ex.error() != null) {
            body.put("error", ex.error());
        }
        return body;
    }

    private Map<String, Object> toMap(ApiTool t) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", t.id());
        map.put("connectionId", t.connectionId());
        map.put("appSlug", t.appSlug());
        map.put("name", t.name());
        map.put("requestSlug", t.requestSlug());
        map.put("displayName", t.displayName());
        map.put("description", t.description() == null ? "" : t.description());
        map.put("category", t.category());
        map.put("method", t.httpMethod());
        map.put("urlTemplate", t.urlTemplate());
        map.put("enabled", t.enabled());
        map.put("pending", t.pending());
        map.put("knowledgeSource", t.knowledgeSource());
        map.put("primaryParam", t.primaryParam());
        map.put("origin", t.origin());
        try {
            map.put("paramsSchema", mapper.readTree(t.paramsSchema()));
        } catch (Exception e) {
            map.put("paramsSchema", Map.of("type", "object"));
        }
        return map;
    }

    public static class KnowledgeSourceRequest {
        public boolean enabled;
    }

    public static class InvokeRequest {
        public Map<String, Object> args;
        /** Ad-hoc headers/query params layered on top of the tool's schema-derived request. */
        public Map<String, String> extraHeaders;
        public Map<String, String> extraQueryParams;
        /** SCHEMA (default) | NONE | RAW — see {@link ApiToolExecutor.InvokeOverrides}. */
        public String bodyMode;
        public String rawBody;
        public String rawContentType;
        /** Read (GET) tools only — write tools always use the connection's stored auth. */
        public AuthOverrideRequest auth;
    }

    public static class AuthOverrideRequest {
        public String mode;
        public String username;
        public String secret;
        public String headerName;
    }

    public static class ManualToolRequest {
        public String connectionId;
        public String displayName;
        public String method;
        public String path;
        public String category;
        public String description;
        public List<ManualParamRequest> params;
        public String bodyTemplate;

        ApiToolService.ManualToolInput toInput() {
            List<ApiToolService.ManualParam> mapped = params == null ? List.of() : params.stream()
                    .map(p -> new ApiToolService.ManualParam(p.name, p.in, p.required, p.defaultValue, p.description))
                    .toList();
            return new ApiToolService.ManualToolInput(
                    displayName, method, path, category, description, mapped, bodyTemplate);
        }
    }

    public static class ManualParamRequest {
        public String name;
        public String in;
        public boolean required;
        public String defaultValue;
        public String description;
    }
}
