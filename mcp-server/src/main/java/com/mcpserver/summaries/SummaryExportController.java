package com.mcpserver.summaries;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/summary-exports")
public class SummaryExportController {

    private final SummaryExportService exportService;

    public SummaryExportController(SummaryExportService exportService) {
        this.exportService = exportService;
    }

    @PostMapping
    public ResponseEntity<byte[]> create(@RequestBody ExportRequest request) {
        SummaryExportService.ExportResult result =
                exportService.export(request.fileIds(), request.connectionIds());
        String filename = "mcp-knowledge-export-" + LocalDate.now() + ".txt";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .header("X-Export-Source-Count", String.valueOf(result.sourceCount()))
                .header("X-Export-Chunk-Count", String.valueOf(result.chunkCount()))
                .body(result.text().getBytes(StandardCharsets.UTF_8));
    }

    public record ExportRequest(List<String> fileIds, List<String> connectionIds) {}

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException ex) {
        return ResponseEntity.status(409).body(Map.of("error", ex.getMessage()));
    }
}
