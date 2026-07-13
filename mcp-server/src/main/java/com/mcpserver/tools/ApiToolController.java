package com.mcpserver.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.connectors.Connection;
import com.mcpserver.connectors.ConnectionService;
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
 */
@RestController
@RequestMapping("/api/tools")
public class ApiToolController {

    private final ApiToolService apiToolService;
    private final ApiToolExecutor apiToolExecutor;
    private final ConnectionService connectionService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiToolController(ApiToolService apiToolService,
                             ApiToolExecutor apiToolExecutor,
                             ConnectionService connectionService) {
        this.apiToolService = apiToolService;
        this.apiToolExecutor = apiToolExecutor;
        this.connectionService = connectionService;
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

    /**
     * Executes the tool. 200 with the invocation result; 422 with structured violations when the
     * args break the generated schema (§8 self-correction — nothing was executed); 409 when the
     * tool is disabled/pending or rate-limited.
     */
    @PostMapping("/{id}/invoke")
    public Map<String, Object> invoke(@PathVariable String id,
                                      @RequestBody(required = false) InvokeRequest req) throws Exception {
        ApiTool tool = apiToolService.findById(id);
        Connection connection = connectionService.findById(tool.connectionId());
        Map<String, Object> args = req != null && req.args != null ? req.args : Map.of();
        ToolInvocationResult result = apiToolExecutor.execute(tool, connection, args);
        Map<String, Object> body = new HashMap<>();
        body.put("tool", tool.name());
        body.put("status", result.status());
        body.put("latencyMs", result.latencyMs());
        body.put("contentType", result.contentType());
        body.put("body", result.body());
        body.put("truncated", result.truncated());
        body.put("request", result.requestSummary());
        return body;
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
    }
}
