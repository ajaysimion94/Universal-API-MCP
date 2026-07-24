package com.mcpserver.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.connectors.Connection;
import com.mcpserver.connectors.ConnectionService;
import com.mcpserver.tools.ApiTool;
import com.mcpserver.tools.ApiToolExecutor;
import com.mcpserver.tools.ApiToolService;
import com.mcpserver.tools.ToolInvocationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the §7.2 workflow state machine for state-changing tool invocations:
 * EXTRACT → VALIDATE → GUARD → PREVIEW → (await confirmation) → EXECUTE → CONFIRM.
 *
 * <p>Read tools bypass this entirely; only write tools are routed through the engine.
 * The GUARD step delegates to the pluggable {@link EntitlementChecker} seam
 * (pass-through until Phase 6). Confirmation tokens are single-use and expiring.
 */
@Component
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final WorkflowRepository repository;
    private final EntitlementChecker entitlementChecker;
    private final RateLimiter rateLimiter;
    private final ApiToolExecutor apiToolExecutor;
    private final ConnectionService connectionService;
    private final ApiToolService apiToolService;
    private final ObjectMapper objectMapper;
    private final long tokenTtlMinutes;

    public WorkflowEngine(
            WorkflowRepository repository,
            EntitlementChecker entitlementChecker,
            RateLimiter rateLimiter,
            ApiToolExecutor apiToolExecutor,
            ConnectionService connectionService,
            ApiToolService apiToolService,
            ObjectMapper objectMapper,
            @Value("${workflow.token-ttl-minutes:5}") long tokenTtlMinutes) {
        this.repository = repository;
        this.entitlementChecker = entitlementChecker;
        this.rateLimiter = rateLimiter;
        this.apiToolExecutor = apiToolExecutor;
        this.connectionService = connectionService;
        this.apiToolService = apiToolService;
        this.objectMapper = objectMapper;
        this.tokenTtlMinutes = tokenTtlMinutes;
    }

    /**
     * Initiates the workflow for a state-changing tool: validates, guards, renders
     * preview, generates a single-use confirmation token, and saves the execution
     * in AWAITING_CONFIRMATION state.
     */
    public WorkflowExecution initiateWriteTool(ApiTool tool, Connection connection,
                                                Map<String, Object> args, String actor) {
        return initiateWriteTool(tool, connection, args, ApiToolExecutor.InvokeOverrides.empty(), actor);
    }

    /**
     * Same as above, plus per-invocation overrides (extra headers/query params/raw body — never
     * an auth override, which {@link ApiToolExecutor} rejects for write tools). Overrides are
     * stashed in the {@code resolved_params} column so {@link #confirm} can replay the exact
     * same request the user approved.
     */
    public WorkflowExecution initiateWriteTool(ApiTool tool, Connection connection,
                                                Map<String, Object> args,
                                                ApiToolExecutor.InvokeOverrides overrides, String actor) {
        log.info("Initiating write-tool workflow for {} (actor: {})", tool.name(), actor);

        // Rate limit checks
        if (actor != null) rateLimiter.checkClientLimit(actor);
        rateLimiter.checkToolLimit(tool.id());

        // GUARD seam — pass-through until Phase 6
        entitlementChecker.check(actor, tool.id(), args);

        String paramsJson;
        String overridesJson;
        try {
            paramsJson = objectMapper.writeValueAsString(args);
            overridesJson = objectMapper.writeValueAsString(overrides);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize args", e);
        }

        // Render preview (method, URL, headers, body) without executing
        Map<String, Object> previewMap;
        try {
            previewMap = apiToolExecutor.renderPreview(tool, connection, args, overrides);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render preview: " + e.getMessage(), e);
        }

        String previewJson;
        try {
            previewJson = objectMapper.writeValueAsString(previewMap);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize preview", e);
        }

        String idempotencyKey = UUID.randomUUID().toString();
        String token = ConfirmationToken.generate();
        Instant expiresAt = ConfirmationToken.expiresAt(Duration.ofMinutes(tokenTtlMinutes));
        Instant now = Instant.now();

        WorkflowExecution execution = new WorkflowExecution(
                UUID.randomUUID().toString(),
                tool.id(), tool.name(),
                WorkflowState.AWAITING_CONFIRMATION,
                paramsJson, overridesJson,
                token, expiresAt, idempotencyKey,
                actor, previewJson,
                null, null,
                now, now
        );

        repository.save(execution);
        log.info("Workflow {} → AWAITING_CONFIRMATION (token expires {})", execution.id(), expiresAt);
        return execution;
    }

    /**
     * Confirms a workflow execution. Validates the token, checks idempotency,
     * executes the tool against the downstream API, and transitions to CONFIRMED.
     * On failure, transitions to FAILED.
     */
    public WorkflowExecution confirm(String token, String actor) {
        log.info("Confirming workflow with token (actor: {})", actor);

        WorkflowExecution execution = repository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or unknown confirmation token"));

        if (actor != null && execution.actor() != null && !actor.equals(execution.actor())) {
            throw new SecurityException("Actor mismatch");
        }

        if (execution.state() != WorkflowState.AWAITING_CONFIRMATION) {
            throw new IllegalStateException("Workflow is in state " + execution.state()
                    + " — confirmation tokens are single-use");
        }

        if (!execution.isTokenValid()) {
            repository.updateState(execution.id(), WorkflowState.EXPIRED, null, "Token expired");
            throw new IllegalStateException("Confirmation token has expired");
        }

        // Check idempotency: if already executed with same key, return the cached result
        Optional<WorkflowExecution> existing = repository.findByIdempotencyKey(execution.idempotencyKey());
        if (existing.isPresent() && existing.get().state() == WorkflowState.CONFIRMED) {
            log.info("Idempotent hit: workflow {} already confirmed", existing.get().id());
            return existing.get();
        }

        repository.updateState(execution.id(), WorkflowState.EXECUTING, null, null);

        try {
            ApiTool tool = apiToolService.findById(execution.toolId());
            Connection connection = connectionService.findById(tool.connectionId());

            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(execution.params(), Map.class);
            ApiToolExecutor.InvokeOverrides overrides = execution.resolvedParams() == null
                    ? ApiToolExecutor.InvokeOverrides.empty()
                    : objectMapper.readValue(execution.resolvedParams(), ApiToolExecutor.InvokeOverrides.class);

            ToolInvocationResult result = apiToolExecutor.execute(tool, connection, args, overrides);

            Map<String, Object> resultMap = new java.util.HashMap<>();
            resultMap.put("status", result.status());
            resultMap.put("latencyMs", result.latencyMs());
            resultMap.put("contentType", result.contentType() == null ? "" : result.contentType());
            resultMap.put("body", result.body());
            resultMap.put("truncated", result.truncated());
            resultMap.put("request", result.requestSummary());
            resultMap.put("headers", result.headers());
            String resultJson = objectMapper.writeValueAsString(resultMap);
            repository.updateState(execution.id(), WorkflowState.CONFIRMED, resultJson, null);
            log.info("Workflow {} → CONFIRMED (tool: {}, HTTP {})", execution.id(), tool.name(), result.status());
        } catch (Exception e) {
            log.error("Workflow {} → FAILED: {}", execution.id(), e.getMessage());
            repository.updateState(execution.id(), WorkflowState.FAILED, null, e.getMessage());
        }

        return repository.findById(execution.id()).orElseThrow();
    }

    /**
     * Rejects a workflow execution. No side effects occur; the rejection is audited.
     */
    public WorkflowExecution reject(String token, String actor) {
        log.info("Rejecting workflow with token (actor: {})", actor);

        WorkflowExecution execution = repository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or unknown confirmation token"));

        if (actor != null && execution.actor() != null && !actor.equals(execution.actor())) {
            throw new SecurityException("Actor mismatch");
        }

        if (execution.state() != WorkflowState.AWAITING_CONFIRMATION) {
            throw new IllegalStateException("Workflow is in state " + execution.state()
                    + " — only AWAITING_CONFIRMATION can be rejected");
        }

        repository.updateState(execution.id(), WorkflowState.REJECTED, null, null);
        log.info("Workflow {} → REJECTED", execution.id());
        return repository.findById(execution.id()).orElseThrow();
    }

    public Optional<WorkflowExecution> findById(String id) {
        return repository.findById(id);
    }
}
