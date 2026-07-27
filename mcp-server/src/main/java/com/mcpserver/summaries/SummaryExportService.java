package com.mcpserver.summaries;

import com.mcpserver.models.Chunk;
import com.mcpserver.repositories.ChunkRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SummaryExportService {

    private static final int MAX_SELECTIONS = 500;
    private static final int MAX_EXPORT_CHARS = 25_000_000;

    private final ChunkRepository chunkRepository;

    public SummaryExportService(ChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    public ExportResult export(List<String> fileIds, List<String> connectionIds) {
        Set<String> files = normalizedIds(fileIds);
        Set<String> connections = normalizedIds(connectionIds);
        if (files.isEmpty() && connections.isEmpty()) {
            throw new IllegalArgumentException("Select at least one file or connected app");
        }
        if (files.size() + connections.size() > MAX_SELECTIONS) {
            throw new IllegalArgumentException("Select no more than " + MAX_SELECTIONS + " sources");
        }

        List<Chunk> chunks = chunkRepository.findForExport(files, connections);
        if (chunks.isEmpty()) {
            throw new IllegalStateException(
                    "The selected sources have no indexed content yet; wait for ingestion or run a connector backfill");
        }

        Map<String, List<Chunk>> bySource = new LinkedHashMap<>();
        for (Chunk chunk : chunks) {
            bySource.computeIfAbsent(chunk.sourceFileId(), ignored -> new java.util.ArrayList<>())
                    .add(chunk);
        }

        StringBuilder text = new StringBuilder(Math.min(MAX_EXPORT_CHARS, chunks.size() * 1000));
        text.append("MCP KNOWLEDGE EXPORT\n")
                .append("Generated: ").append(Instant.now()).append('\n')
                .append("Indexed sources: ").append(bySource.size()).append('\n')
                .append("Chunks: ").append(chunks.size()).append("\n\n");

        for (List<Chunk> sourceChunks : bySource.values()) {
            Chunk first = sourceChunks.get(0);
            text.append("================================================================================\n")
                    .append("SOURCE: ").append(clean(first.sourceName())).append('\n')
                    .append("PATH: ").append(clean(first.sourcePath())).append('\n')
                    .append("SYSTEM: ").append(clean(first.sourceSystem())).append('\n');
            if (first.url() != null && !first.url().isBlank()) {
                text.append("URL: ").append(first.url()).append('\n');
            }
            if (first.sourceUpdatedAt() != null) {
                text.append("SOURCE UPDATED: ").append(first.sourceUpdatedAt()).append('\n');
            }
            text.append("================================================================================\n\n");

            for (Chunk chunk : sourceChunks) {
                appendWithinLimit(text, chunk.content());
                text.append("\n\n");
            }
        }

        return new ExportResult(text.toString(), bySource.size(), chunks.size());
    }

    private static Set<String> normalizedIds(List<String> ids) {
        Set<String> normalized = new LinkedHashSet<>();
        if (ids == null) return normalized;
        for (String id : ids) {
            if (id != null && !id.isBlank()) normalized.add(id.trim());
        }
        return normalized;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? "-" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private static void appendWithinLimit(StringBuilder output, String content) {
        String value = content == null ? "" : content;
        if ((long) output.length() + value.length() > MAX_EXPORT_CHARS) {
            throw new IllegalStateException(
                    "The selected content exceeds the 25 MB TXT export limit; narrow the selection");
        }
        output.append(value);
    }

    public record ExportResult(String text, int sourceCount, int chunkCount) {}
}
