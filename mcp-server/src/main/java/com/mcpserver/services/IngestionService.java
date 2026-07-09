package com.mcpserver.services;

import com.mcpserver.models.Chunk;
import com.mcpserver.plugins.PluginRegistry;
import com.mcpserver.rag.chunking.Chunker;
import com.mcpserver.rag.embedding.EmbeddingClient;
import com.mcpserver.repositories.ChunkRepository;
import jakarta.annotation.PreDestroy;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final Chunker chunker;
    private final EmbeddingClient embeddingClient;
    private final ChunkRepository chunkRepository;
    private final PluginRegistry pluginRegistry;
    private final IngestionProgressTracker progressTracker;
    private final int targetChunkTokens;
    private final int chunkOverlapTokens;
    private final Tika tika;

    // Single worker so at most one file is extracted/embedded at a time — embedding is
    // CPU- and memory-bound, and uploads must not block on it (they enqueue and return).
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ingestion-worker");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> pendingSources = ConcurrentHashMap.newKeySet();
    private final Set<String> cancelledSources = ConcurrentHashMap.newKeySet();

    public IngestionService(Chunker chunker,
                            EmbeddingClient embeddingClient,
                            ChunkRepository chunkRepository,
                            PluginRegistry pluginRegistry,
                            IngestionProgressTracker progressTracker,
                            @Value("${rag.ingestion.target-chunk-tokens}") int targetChunkTokens,
                            @Value("${rag.ingestion.chunk-overlap-tokens}") int chunkOverlapTokens) {
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.chunkRepository = chunkRepository;
        this.pluginRegistry = pluginRegistry;
        this.progressTracker = progressTracker;
        this.targetChunkTokens = targetChunkTokens;
        this.chunkOverlapTokens = chunkOverlapTokens;
        this.tika = new Tika();
    }

    /**
     * Queue a file for background ingestion and return immediately. {@code payload} is a
     * temp file owned by this service from here on — the worker deletes it when done.
     * Progress is reported via {@link IngestionProgressTracker} for the UI poller.
     */
    public void enqueue(String sourceFileId, String sourceName, String sourcePath,
                        Path payload, String mimeType, List<String> aclTags) {
        pendingSources.add(sourceFileId);
        progressTracker.filesEnqueued(1);
        worker.submit(() -> {
            try {
                if (cancelledSources.remove(sourceFileId)) return;
                byte[] bytes = Files.readAllBytes(payload);
                ingest(sourceFileId, sourceName, sourcePath, bytes, mimeType, aclTags);
                // Deleted while we were ingesting: remove the chunks we just wrote.
                if (cancelledSources.remove(sourceFileId)) {
                    chunkRepository.deleteBySourceFileId(sourceFileId);
                }
            } catch (Exception e) {
                log.warn("Ingestion failed for {} (search will not cover this file): {}",
                        sourceName, e.getMessage());
            } finally {
                pendingSources.remove(sourceFileId);
                progressTracker.fileFinished();
                try {
                    Files.deleteIfExists(payload);
                } catch (IOException ignored) {
                }
            }
        });
    }

    public void ingest(String sourceFileId, String sourceName, String sourcePath,
                       byte[] contentBytes, String mimeType, List<String> aclTags) {
        progressTracker.startFile(sourceName);

        String text = extractText(contentBytes, mimeType, sourceName);
        if (text == null || text.isBlank()) {
            log.info("Skipping ingestion for {} — no extractable text (mimeType={})", sourceName, mimeType);
            return;
        }

        chunkRepository.deleteBySourceFileId(sourceFileId);

        // Prepend the filename and path so they're searchable in both FTS5 and vector space.
        String augmented = "Title: " + sourceName
                + (sourcePath != null && !sourcePath.isBlank() ? "\nPath: " + sourcePath : "")
                + "\n\n" + text;

        progressTracker.chunking();
        List<Chunker.ChunkText> chunkTexts = chunker.chunk(augmented, targetChunkTokens, chunkOverlapTokens);

        // Without the embedding model, still chunk and FTS-index so lexical search works;
        // embeddings stay null and the vector leg simply has nothing for this file.
        boolean embeddingReady = pluginRegistry.isReady("nomic-embedding");
        log.info("Ingesting {} → {} chunks (mimeType={}{})", sourceName, chunkTexts.size(), mimeType,
                embeddingReady ? "" : ", lexical only — embedding model not installed");
        if (embeddingReady) {
            progressTracker.startEmbedding(chunkTexts.size());
        } else {
            progressTracker.startIndexing(chunkTexts.size());
        }
        for (Chunker.ChunkText ct : chunkTexts) {
            float[] embedding = embeddingReady
                    ? embeddingClient.embed(ct.content(), EmbeddingClient.Mode.DOCUMENT)
                    : null;
            Chunk chunk = Chunk.create(sourceFileId, sourceName, sourcePath,
                    ct.content(), embedding, aclTags, ct.position(), ct.approxTokenCount());
            chunkRepository.save(chunk);
            progressTracker.chunkEmbedded();
        }
        log.info("Ingested {} chunks for {}", chunkTexts.size(), sourceName);
    }

    public void purgeSource(String sourceFileId) {
        // If the file is still queued or mid-ingestion, flag it so the worker skips it
        // (or cleans up after itself); then remove whatever chunks already exist.
        if (pendingSources.contains(sourceFileId)) {
            cancelledSources.add(sourceFileId);
        }
        chunkRepository.deleteBySourceFileId(sourceFileId);
        log.info("Purged chunks for source {}", sourceFileId);
    }

    @PreDestroy
    void shutdown() {
        worker.shutdown();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String extractText(byte[] bytes, String mimeType, String fileName) {
        if (mimeType == null) mimeType = "text/plain";

        if (isTextMime(mimeType)) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (mimeType.contains("html")) {
                text = text.replaceAll("(?is)<[^>]+>", " ").replaceAll("\\s+", " ").strip();
            }
            return text;
        }

        try {
            String extracted = tika.parseToString(new ByteArrayInputStream(bytes));
            return extracted == null ? null : extracted.strip();
        } catch (TikaException | IOException e) {
            log.warn("Tika extraction failed for {} (mimeType={}): {}", fileName, mimeType, e.getMessage());
            return null;
        }
    }

    private boolean isTextMime(String mimeType) {
        return mimeType.startsWith("text/")
                || mimeType.equals("application/json")
                || mimeType.equals("application/xml")
                || mimeType.equals("application/x-yaml");
    }
}
