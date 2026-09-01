package com.mcpserver.insights;

import org.springframework.http.HttpStatus;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** .rqd insight analysis and data endpoints. */
@RestController
@RequestMapping("/api/insights")
public class InsightController {

    private final InsightService insightService;
    private final InsightWorkbookExportService workbookExportService;

    public InsightController(InsightService insightService, InsightWorkbookExportService workbookExportService) {
        this.insightService = insightService;
        this.workbookExportService = workbookExportService;
    }

    @GetMapping
    public List<SavedInsight> list() {
        return insightService.findAll();
    }

    @GetMapping("/{id}")
    public SavedInsight get(@PathVariable String id) {
        return insightService.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavedInsight create(@RequestBody SaveRequest request) {
        return insightService.create(request.name, request.description, request.source, request.connectionId);
    }

    @PutMapping("/{id}")
    public SavedInsight update(@PathVariable String id, @RequestBody SaveRequest request) {
        return insightService.update(id, request.name, request.description, request.source, request.connectionId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        insightService.delete(id);
    }

    @PostMapping("/analyze")
    public InsightModel.Analysis analyze(@RequestBody AnalyzeRequest request) {
        return insightService.analyze(request.source, request.connectionId, request.cursorOffset);
    }

    @PostMapping("/data")
    public InsightModel.Data data(@RequestBody DataRequest request) {
        return insightService.data(request.source, request.connectionId, request.parameters);
    }

    /**
     * Runs a saved insight and keeps the result on it. Distinct from {@code /data}, which stays a
     * pure evaluation for unsaved drafts: putting the id in the path makes "the insight must exist"
     * unconditional, and means the only result that can be stored is one the server just computed.
     */
    @PostMapping("/{id}/run")
    public InsightModel.RunResult run(@PathVariable String id, @RequestBody DataRequest request) {
        return insightService.run(id, request.source, request.connectionId, request.parameters);
    }

    /** Executes the current report definition and downloads every materialized RQL dataset. */
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody DataRequest request) {
        InsightModel.Document document = insightService.parse(request.source);
        InsightModel.Data data = insightService.data(request.source, request.connectionId, request.parameters);
        String filename = document.title().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("(^-|-$)", "");
        if (filename.isBlank()) filename = "insight-report";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename + ".xlsx").build().toString())
                .body(workbookExportService.export(document.title(), data));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", exception.getMessage()));
    }

    public static class AnalyzeRequest {
        public String source;
        public String connectionId;
        public Integer cursorOffset;
    }

    public static class DataRequest extends AnalyzeRequest {
        public Map<String, Object> parameters;
    }

    /** Create/update payload for a saved insight. */
    public static class SaveRequest {
        public String name;
        public String description;
        public String source;
        public String connectionId;
    }
}
