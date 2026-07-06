package com.mcpserver.services;

import org.springframework.stereotype.Component;

/**
 * In-memory progress state for the background ingestion queue, polled by the web UI.
 * Uploads enqueue files and return immediately; the ingestion worker drains the queue
 * and reports per-file phases here. Single global tracker for this single-user app:
 * totals accumulate while the queue is non-empty and reset once it drains.
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
    private int filesFinished;
    private int chunksDone;
    private int chunksTotal;
    private long updatedAt = System.currentTimeMillis();

    /** Called on the upload request thread, before the enqueue returns to the client. */
    public synchronized void filesEnqueued(int count) {
        if (!active) {
            this.phase = "starting";
            this.fileName = null;
            this.fileIndex = 0;
            this.totalFiles = 0;
            this.filesFinished = 0;
            this.chunksDone = 0;
            this.chunksTotal = 0;
        }
        this.active = true;
        this.totalFiles += count;
        touch();
    }

    public synchronized void startFile(String fileName) {
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

    /** Like {@link #startEmbedding} but for lexical-only ingestion (no embedding model). */
    public synchronized void startIndexing(int chunksTotal) {
        this.phase = "indexing";
        this.chunksTotal = chunksTotal;
        this.chunksDone = 0;
        touch();
    }

    public synchronized void chunkEmbedded() {
        this.chunksDone++;
        touch();
    }

    /** Called by the worker after each file (including skipped/failed ones). */
    public synchronized void fileFinished() {
        this.filesFinished++;
        if (filesFinished >= totalFiles) {
            this.active = false;
            this.phase = "idle";
            this.fileName = null;
        }
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
