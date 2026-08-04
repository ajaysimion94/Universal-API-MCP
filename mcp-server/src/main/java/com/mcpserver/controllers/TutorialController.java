package com.mcpserver.controllers;

import com.mcpserver.help.TutorialCatalog;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** REST projection of the hands-on tutorial catalogue used by the web UI. */
@RestController
@RequestMapping("/api/tutorials")
public class TutorialController {

    private final TutorialCatalog tutorialCatalog;

    public TutorialController(TutorialCatalog tutorialCatalog) {
        this.tutorialCatalog = tutorialCatalog;
    }

    @GetMapping
    public List<TutorialCatalog.TutorialSummary> list() {
        return tutorialCatalog.summaries();
    }

    @GetMapping("/{id}")
    public TutorialCatalog.Tutorial get(@PathVariable String id) {
        return tutorialCatalog.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Tutorial not found: " + id));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
