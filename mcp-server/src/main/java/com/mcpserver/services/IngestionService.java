package com.mcpserver.services;

import com.mcpserver.models.Chunk;
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

/**
 * Ingestion pipeline (plan.md §5.7, §2.2):
 * extract text → structure-aware chunk → embed (in-process ONNX) → store in pgvector.
 * <p>
 * Text extraction handles text-based formats directly and uses Apache Tika for binary
 * formats (PDF, Word .docx/.doc, Excel, PowerPoint, OpenOffice, RTF, etc.) per plan §5.7.
 * ACL tags are captured here on every chunk (enforcement activates in Phase 6).
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final Chunker chunker;
    private final EmbeddingClient embeddingClient;
    private final ChunkRepository chunkRepository;
    private final int targetChunkTokens;
    private final int chunkOverlapTokens;
    private final Tika tika;

    public IngestionService(Chunker chunker,
                            EmbeddingClient embeddingClient,
                            ChunkRepository chunkRepository,
                            @Value("${rag.ingestion.target-chunk-tokens}") int targetChunkTokens,
                            @Value("${rag.ingestion.chunk-overlap-tokens}") int chunkOverlapTokens) {
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.chunkRepository = chunkRepository;
        this.targetChunkTokens = targetChunkTokens;
        this.chunkOverlapTokens = chunkOverlapTokens;
        this.tika = new Tika();
    }

    /**
     * Ingest a file: extract text → chunk → embed → store. ACL tags come from the parent
     * folder's visibility (captured now; enforced in Phase 6).
     *
     * @param sourceFileId  the file node id
     * @param sourceName    file name
     * @param sourcePath    breadcrumb path (for citations)
     * @param contentBytes  raw file bytes
     * @param mimeType      MIME type
     * @param aclTags       ACL tags from folder visibility
     */
    public void ingest(String sourceFileId, String sourceName, String sourcePath,
                       byte[] contentBytes, String mimeType, List<String> aclTags) {
        String text = extractText(contentBytes, mimeType, sourceName);
        if (text == null || text.isBlank()) {
            log.info("Skipping ingestion for {} — no extractable text (mimeType={})", sourceName, mimeType);
            return;
        }

        // Replace any existing chunks for this file (idempotent re-upload / versioning).
        chunkRepository.deleteBySourceFileId(sourceFileId);

        List<Chunker.ChunkText> chunkTexts = chunker.chunk(text, targetChunkTokens, chunkOverlapTokens);
        log.info("Ingesting {} → {} chunks (mimeType={})", sourceName, chunkTexts.size(), mimeType);
        for (Chunker.ChunkText ct : chunkTexts) {
            float[] embedding = embeddingClient.embed(ct.content(), EmbeddingClient.Mode.DOCUMENT);
            Chunk chunk = Chunk.create(sourceFileId, sourceName, sourcePath,
                    ct.content(), embedding, aclTags, ct.position(), ct.approxTokenCount());
            chunkRepository.save(chunk);
        }
        log.info("Ingested {} chunks for {}", chunkTexts.size(), sourceName);
    }

    /** Purge all chunks for a source file (called on file delete). */
    public void purgeSource(String sourceFileId) {
        chunkRepository.deleteBySourceFileId(sourceFileId);
        log.info("Purged chunks for source {}", sourceFileId);
    }

    /**
     * Extract text from file bytes. Text-based formats are read directly; binary formats
     * (PDF, Word, Excel, PowerPoint, etc.) are extracted via Apache Tika (plan.md §5.7).
     */
    private String extractText(byte[] bytes, String mimeType, String fileName) {
        if (mimeType == null) mimeType = "text/plain";

        // Fast path for text-based formats — no Tika overhead.
        if (isTextMime(mimeType)) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (mimeType.contains("html")) {
                text = text.replaceAll("(?is)<[^>]+>", " ").replaceAll("\\s+", " ").strip();
            }
            return text;
        }

        // Binary formats → Apache Tika (PDF, Word, Excel, PowerPoint, RTF, OpenOffice, etc.)
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
