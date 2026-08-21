package com.mcpserver.plugins;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private final PluginRegistry registry;
    private final OnnxModelUploadService modelUploads;

    public PluginController(PluginRegistry registry, OnnxModelUploadService modelUploads) {
        this.registry = registry;
        this.modelUploads = modelUploads;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return registry.getAll().stream().map(this::toMap).toList();
    }

    @GetMapping("/models")
    public List<OnnxModelUploadService.ModelStatus> models() {
        return modelUploads.statuses();
    }

    @PostMapping(value = "/models/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public OnnxModelUploadService.ModelStatus uploadModel(
            @PathVariable String kind,
            @RequestPart("model") MultipartFile model,
            @RequestPart("tokenizer") MultipartFile tokenizer) {
        return modelUploads.upload(kind, model, tokenizer);
    }

    @PostMapping("/{id}/install")
    public Map<String, String> install(@PathVariable String id) {
        String jobId = registry.startInstall(id);
        return Map.of("jobId", jobId, "status", "running");
    }

    @PostMapping("/{id}/enable")
    public Map<String, Object> enable(@PathVariable String id) {
        Plugin plugin = registry.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Plugin not found: " + id));
        plugin.enable();
        return toMap(plugin);
    }

    @PostMapping("/{id}/disable")
    public Map<String, Object> disable(@PathVariable String id) {
        Plugin plugin = registry.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Plugin not found: " + id));
        plugin.disable();
        return toMap(plugin);
    }

    @PostMapping("/{id}/start")
    public Map<String, Object> start(@PathVariable String id) {
        Plugin plugin = registry.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Plugin not found: " + id));
        try {
            plugin.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start plugin: " + e.getMessage(), e);
        }
        return toMap(plugin);
    }

    @PostMapping("/{id}/stop")
    public Map<String, Object> stop(@PathVariable String id) {
        Plugin plugin = registry.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Plugin not found: " + id));
        try {
            plugin.stop();
        } catch (Exception e) {
            throw new RuntimeException("Failed to stop plugin: " + e.getMessage(), e);
        }
        return toMap(plugin);
    }

    @GetMapping("/jobs/{jobId}")
    public Map<String, Object> getJob(@PathVariable String jobId) {
        PluginRegistry.InstallJob job = registry.getJob(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Job not found: " + jobId);
        }
        Map<String, Object> map = new HashMap<>();
        map.put("jobId", jobId);
        map.put("pluginId", job.pluginId);
        map.put("status", job.status);
        if (job.error != null) map.put("error", job.error);
        return map;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }

    private Map<String, Object> toMap(Plugin p) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", p.id());
        map.put("name", p.name());
        map.put("description", p.description());
        map.put("category", p.category().name());
        map.put("builtin", p.builtIn());
        map.put("status", p.status().name());
        map.put("enabled", p.isEnabled());
        map.put("running", p.isRunning());
        map.put("ready", p.isReady());
        map.put("health", p.health());
        return map;
    }
}
