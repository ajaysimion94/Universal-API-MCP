package com.mcpserver.controllers;

import com.mcpserver.guides.GuideCatalog;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** REST projection of the shared, runtime guide catalogue used by the web UI. */
@RestController
@RequestMapping("/api/guides")
public class GuideController {

    private final GuideCatalog guideCatalog;

    public GuideController(GuideCatalog guideCatalog) {
        this.guideCatalog = guideCatalog;
    }

    @GetMapping
    public List<GuideCatalog.GuideSummary> list() {
        return guideCatalog.summaries();
    }

    @GetMapping("/{id}")
    public GuideCatalog.GuideArticle get(@PathVariable String id) {
        return guideCatalog.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Guide article not found: " + id));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
