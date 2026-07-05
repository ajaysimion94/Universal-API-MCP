package com.mcpserver.models;

public record BulkUploadResult(
        int foldersCreated,
        int filesUploaded,
        int filesSkipped,
        int totalFiles
) {

    public static BulkUploadResult empty(int totalFiles) {
        return new BulkUploadResult(0, 0, 0, totalFiles);
    }
}
