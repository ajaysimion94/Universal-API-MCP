package com.mcpserver.dashboards;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** .rqd dashboard analysis and data endpoints. */
@RestController
@RequestMapping("/api/dashboards")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @PostMapping("/analyze")
    public DashboardModel.Analysis analyze(@RequestBody AnalyzeRequest request) {
        return dashboardService.analyze(request.source, request.connectionId, request.cursorOffset);
    }

    @PostMapping("/data")
    public DashboardModel.Data data(@RequestBody DataRequest request) {
        return dashboardService.data(request.source, request.connectionId, request.parameters);
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
}
