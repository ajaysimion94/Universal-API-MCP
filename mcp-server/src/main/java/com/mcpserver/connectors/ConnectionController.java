package com.mcpserver.connectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/connections")
public class ConnectionController {

    private final ConnectionService connectionService;

    public ConnectionController(ConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return connectionService.findAll().stream().map(this::toMap).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return toMap(connectionService.findById(id));
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRequest req) {
        if (req.type == null || req.name == null || req.baseUrl == null) {
            throw new IllegalArgumentException("type, name, and baseUrl are required");
        }
        ConnectionType type;
        try {
            type = ConnectionType.valueOf(req.type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown connection type: " + req.type);
        }
        List<String> aclScope = req.aclScope != null ? req.aclScope : List.of();
        ConnectionService.CreateResult result =
                connectionService.create(type, req.name, req.baseUrl, req.username, req.password, aclScope);
        return Map.of("id", result.connectionId(), "jobId", result.jobId(), "status", "running");
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody UpdateRequest req) {
        String jobId = connectionService.update(id, req.name, req.baseUrl, req.username, req.password, req.aclScope);
        return Map.of("jobId", jobId, "status", "running");
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        connectionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/backfill")
    public Map<String, String> backfill(@PathVariable String id) {
        String jobId = connectionService.startBackfillJob(id);
        return Map.of("jobId", jobId, "status", "running");
    }

    @PostMapping("/{id}/enable")
    public Map<String, Object> enable(@PathVariable String id) {
        connectionService.setDisabled(id, false);
        return toMap(connectionService.findById(id));
    }

    @PostMapping("/{id}/disable")
    public Map<String, Object> disable(@PathVariable String id) {
        connectionService.setDisabled(id, true);
        return toMap(connectionService.findById(id));
    }

    @GetMapping("/jobs/{jobId}")
    public Map<String, Object> getJob(@PathVariable String jobId) {
        ConnectionService.ConnectionJob job = connectionService.getJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        Map<String, Object> map = new HashMap<>();
        map.put("jobId", jobId);
        map.put("connectionId", job.connectionId);
        map.put("kind", job.kind.name());
        map.put("status", job.status);
        if (job.kind == ConnectionService.ConnectionJob.Kind.BACKFILL) {
            map.put("itemsProcessed", job.itemsProcessed);
            map.put("itemsTotal", job.itemsTotal);
        }
        if (job.error != null) map.put("error", job.error);
        return map;
    }

    /**
     * Inbound webhook intake. Returns 202 immediately after durably recording the payload — actual
     * processing happens on {@link EventQueueWorker}'s background thread, which is what keeps this
     * endpoint within the 3-second webhook-ack SLA regardless of how long ingestion takes.
     */
    @PostMapping("/{id}/webhook")
    public ResponseEntity<Void> webhook(@PathVariable String id, @RequestBody String rawPayload) {
        connectionService.receiveWebhook(id, rawPayload);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /** Never includes authSecretEncrypted or the plaintext password/token. */
    private Map<String, Object> toMap(Connection c) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", c.id());
        map.put("type", c.type().name());
        map.put("name", c.name());
        map.put("baseUrl", c.baseUrl());
        map.put("deploymentType", c.deploymentType().name());
        map.put("authMode", c.authMode().name());
        map.put("authUsername", c.authUsername());
        map.put("status", c.status().name());
        if (c.lastError() != null) map.put("lastError", c.lastError());
        map.put("webhookRegistered", c.webhookRegistered());
        map.put("aclScope", c.aclScope());
        map.put("createdAt", c.createdAt().toString());
        map.put("updatedAt", c.updatedAt().toString());
        if (c.lastSyncedAt() != null) map.put("lastSyncedAt", c.lastSyncedAt().toString());
        return map;
    }

    public static class CreateRequest {
        public String type;
        public String name;
        public String baseUrl;
        public String username;
        public String password;
        public List<String> aclScope = new ArrayList<>();
    }

    public static class UpdateRequest {
        public String name;
        public String baseUrl;
        public String username;
        public String password;
        public List<String> aclScope;
    }
}
