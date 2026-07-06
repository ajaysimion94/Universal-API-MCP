package com.mcpserver.services;

import com.mcpserver.models.Chunk;
import com.mcpserver.plugins.PluginRegistry;
import com.mcpserver.rag.chunking.Chunker;
import com.mcpserver.rag.embedding.EmbeddingClient;
import com.mcpserver.repositories.ChunkRepository;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

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

    public void ingest(String sourceFileId, String sourceName, String sourcePath,
                       byte[] contentBytes, String mimeType, List<String> aclTags) {
        if (!pluginRegistry.isReady("nomic-embedding")) {
            log.info("Skipping ingestion for {} — embedding model not installed (install via Plugins page)", sourceName);
            return;
        }

        progressTracker.startFile(sourceName);

        String text = extractText(contentBytes, mimeType, sourceName);
        if (text == null || text.isBlank()) {
            log.info("Skipping ingestion for {} — no extractable text (mimeType={})", sourceName, mimeType);
            return;
        }

        chunkRepository.deleteBySourceFileId(sourceFileId);

        progressTracker.chunking();
        List<Chunker.ChunkText> chunkTexts = chunker.chunk(text, targetChunkTokens, chunkOverlapTokens);
        log.info("Ingesting {} → {} chunks (mimeType={})", sourceName, chunkTexts.size(), mimeType);
        progressTracker.startEmbedding(chunkTexts.size());
        for (Chunker.ChunkText ct : chunkTexts) {
            float[] embedding = embeddingClient.embed(ct.content(), EmbeddingClient.Mode.DOCUMENT);
            Chunk chunk = Chunk.create(sourceFileId, sourceName, sourcePath,
                    ct.content(), embedding, aclTags, ct.position(), ct.approxTokenCount());
            chunkRepository.save(chunk);
            progressTracker.chunkEmbedded();
        }
        log.info("Ingested {} chunks for {}", chunkTexts.size(), sourceName);
    }

    public void purgeSource(String sourceFileId) {
        chunkRepository.deleteBySourceFileId(sourceFileId);
        log.info("Purged chunks for source {}", sourceFileId);
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
