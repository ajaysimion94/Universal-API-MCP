package com.mcpserver.controllers;

import com.mcpserver.models.BulkUploadResult;
import com.mcpserver.models.FileNode;
import com.mcpserver.services.FileService;
import com.mcpserver.services.IngestionProgressTracker;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService fileService;
    private final IngestionProgressTracker progressTracker;

    public FileController(FileService fileService, IngestionProgressTracker progressTracker) {
        this.fileService = fileService;
        this.progressTracker = progressTracker;
    }

    /** Polled by the UI while an upload is in flight to render ingestion progress. */
    @GetMapping("/ingestion-progress")
    public IngestionProgressTracker.Snapshot ingestionProgress() {
        return progressTracker.snapshot();
    }

    @GetMapping
    public FileNode root() {
        return fileService.getRoot();
    }

    /** Flat snapshot used by source pickers; parentId preserves the folder hierarchy. */
    @GetMapping("/tree")
    public List<FileNode> tree() {
        return fileService.listAll();
    }

    @GetMapping("/{id}/children")
    public List<FileNode> children(@PathVariable String id) {
        return fileService.listChildren(id);
    }

    @GetMapping("/{id}/path")
    public List<FileNode> path(@PathVariable String id) {
        return fileService.getPath(id);
    }

    @PostMapping("/{parentId}/folders")
    public FileNode createFolder(@PathVariable String parentId,
                                 @RequestBody Map<String, String> body) {
        String name = body.get("name");
        String owner = body.getOrDefault("owner", "you");
        String visibility = body.getOrDefault("visibility", "everyone");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Folder name is required");
        }
        return fileService.createFolder(parentId, name, owner, visibility);
    }

    @PostMapping("/{parentId}/upload")
    public FileNode upload(@PathVariable String parentId,
                           @RequestParam("file") MultipartFile file,
                           @RequestParam(value = "owner", defaultValue = "you") String owner,
                           @RequestParam(value = "visibility", defaultValue = "everyone") String visibility)
            throws IOException {
        return fileService.uploadFile(parentId, file, owner, visibility);
    }

    /**
     * Bulk folder upload. Each file is sent with a parallel {@code paths} entry giving its
     * relative path (e.g. {@code "MyFolder/sub/file.txt"}); the server recreates the hierarchy
     * under {@code parentId}, reusing existing folders and skipping existing files.
     */
    @PostMapping("/{parentId}/upload-folder")
    public BulkUploadResult uploadFolder(@PathVariable String parentId,
                                         @RequestParam("files") MultipartFile[] files,
                                         @RequestParam("paths") String[] paths,
                                         @RequestParam(value = "owner", defaultValue = "you") String owner,
                                         @RequestParam(value = "visibility", defaultValue = "everyone") String visibility)
            throws IOException {
        if (files.length != paths.length) {
            throw new IllegalArgumentException("files and paths arrays must have the same length");
        }
        return fileService.uploadFolder(
                parentId,
                Arrays.asList(paths),
                Arrays.asList(files),
                owner,
                visibility
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        fileService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException ex) {
        return ResponseEntity.status(409).body(Map.of("error", ex.getMessage()));
    }
}
