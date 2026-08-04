package com.mcpserver.controllers;

import com.mcpserver.help.HelpCatalog;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** REST projection of the shared, runtime help catalogue used by the web UI. */
@RestController
@RequestMapping("/api/help")
public class HelpController {

    private final HelpCatalog helpCatalog;

    public HelpController(HelpCatalog helpCatalog) {
        this.helpCatalog = helpCatalog;
    }

    @GetMapping
    public List<HelpCatalog.TopicSummary> list() {
        return helpCatalog.summaries();
    }

    @GetMapping("/{id}")
    public HelpCatalog.HelpTopic get(@PathVariable String id) {
        return helpCatalog.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Help topic not found: " + id));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
