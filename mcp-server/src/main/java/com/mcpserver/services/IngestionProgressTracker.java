package com.mcpserver.services;

import org.springframework.stereotype.Component;

/**
 * In-memory progress state for the synchronous ingestion pipeline, polled by the
 * web UI while an upload request is in flight. Single global tracker: uploads run
 * one request at a time in this single-user app, so concurrent batches are not tracked
 * separately — the last writer wins.
 */
@Component
public class IngestionProgressTracker {

    public record Snapshot(boolean active, String phase, String fileName,
                           int fileIndex, int totalFiles,
                           int chunksDone, int chunksTotal, long updatedAt) {}

    private boolean active;
    private String phase = "idle";
    private String fileName;
    private int fileIndex;
    private int totalFiles;
    private int chunksDone;
    private int chunksTotal;
    private long updatedAt = System.currentTimeMillis();

    public synchronized void startBatch(int totalFiles) {
        this.active = true;
        this.phase = "starting";
        this.fileName = null;
        this.fileIndex = 0;
        this.totalFiles = totalFiles;
        this.chunksDone = 0;
        this.chunksTotal = 0;
        touch();
    }

    public synchronized void startFile(String fileName) {
        this.active = true;
        this.phase = "extracting";
        this.fileName = fileName;
        this.fileIndex++;
        this.chunksDone = 0;
        this.chunksTotal = 0;
        touch();
    }

    public synchronized void chunking() {
        this.phase = "chunking";
        touch();
    }

    public synchronized void startEmbedding(int chunksTotal) {
        this.phase = "embedding";
        this.chunksTotal = chunksTotal;
        this.chunksDone = 0;
        touch();
    }

    public synchronized void chunkEmbedded() {
        this.chunksDone++;
        touch();
    }

    public synchronized void finishBatch() {
        this.active = false;
        this.phase = "idle";
        this.fileName = null;
        touch();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(active, phase, fileName, fileIndex, totalFiles,
                chunksDone, chunksTotal, updatedAt);
    }

    private void touch() {
        this.updatedAt = System.currentTimeMillis();
    }
}
