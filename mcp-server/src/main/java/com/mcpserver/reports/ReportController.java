package com.mcpserver.reports;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Editor analysis entry point for standalone .rql documents. */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportQueryService reportQueryService;

    public ReportController(ReportQueryService reportQueryService) {
        this.reportQueryService = reportQueryService;
    }

    @PostMapping("/analyze")
    public RqlModel.Analysis analyze(@RequestBody AnalyzeRequest request) {
        return reportQueryService.analyze(request.source, request.connectionId, request.cursorOffset);
    }

    /** Useful to API clients while workbook jobs are added in the next report slice. */
    @PostMapping("/execute")
    public RqlModel.Execution execute(@RequestBody ExecuteRequest request) {
        return reportQueryService.execute(request.source, request.connectionId, request.parameters);
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

    public static class ExecuteRequest extends AnalyzeRequest {
        public Map<String, Object> parameters;
    }
}
