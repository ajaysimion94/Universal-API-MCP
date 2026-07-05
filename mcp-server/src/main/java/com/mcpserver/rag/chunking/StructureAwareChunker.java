package com.mcpserver.rag.chunking;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Structure-aware chunker (plan.md §5.7).
 * <p>
 * Splits on markdown headings first, then by paragraph boundaries, then by a hard token-count limit
 * so chunks stay within the 500–1000 token target with overlap. Token count is approximated at
 * ~4 chars/token (a standard heuristic for the nomic/BERT tokenizer).
 */
@Component
public class StructureAwareChunker implements Chunker {

    private static final int CHARS_PER_TOKEN = 4;

    @Override
    public List<ChunkText> chunk(String text, int targetTokens, int overlapTokens) {
        if (text == null || text.isBlank()) return List.of();
        int targetChars = targetTokens * CHARS_PER_TOKEN;
        int overlapChars = overlapTokens * CHARS_PER_TOKEN;

        List<String> sections = splitByHeadings(text);
        List<ChunkText> chunks = new ArrayList<>();
        StringBuilder carry = new StringBuilder();

        for (String section : sections) {
            if (section.length() <= targetChars) {
                if (carry.length() + section.length() + 1 <= targetChars) {
                    if (!carry.isEmpty()) carry.append("\n");
                    carry.append(section);
                } else {
                    flush(carry, chunks);
                    carry.append(section);
                }
            } else {
                flush(carry, chunks);
                chunkByParagraphs(section, targetChars, overlapChars, chunks);
            }
        }
        flush(carry, chunks);

        // Reindex positions.
        List<ChunkText> reindexed = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            reindexed.add(new ChunkText(chunks.get(i).content(), i, chunks.get(i).approxTokenCount()));
        }
        return reindexed;
    }

    private List<String> splitByHeadings(String text) {
        List<String> sections = new ArrayList<>();
        String[] lines = text.split("\n");
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("#") && current.length() > 0) {
                sections.add(current.toString().strip());
                current = new StringBuilder();
            }
            current.append(line).append("\n");
        }
        if (current.length() > 0) sections.add(current.toString().strip());
        return sections;
    }

    private void chunkByParagraphs(String section, int targetChars, int overlapChars, List<ChunkText> out) {
        String[] paragraphs = section.split("\n\n+");
        StringBuilder chunk = new StringBuilder();
        for (String para : paragraphs) {
            if (chunk.length() + para.length() + 2 <= targetChars) {
                if (!chunk.isEmpty()) chunk.append("\n\n");
                chunk.append(para);
            } else {
                if (chunk.length() > 0) out.add(toChunk(chunk.toString()));
                chunk = new StringBuilder();
                if (overlapChars > 0 && out.size() > 0) {
                    String prev = out.get(out.size() - 1).content();
                    int start = Math.max(0, prev.length() - overlapChars);
                    chunk.append(prev.substring(start)).append("\n\n");
                }
                if (chunk.length() + para.length() + 2 <= targetChars) {
                    chunk.append(para);
                } else {
                    // Hard split a single oversized paragraph.
                    hardSplit(para, targetChars, overlapChars, out);
                    chunk = new StringBuilder();
                }
            }
        }
        if (chunk.length() > 0) out.add(toChunk(chunk.toString()));
    }

    private void hardSplit(String text, int targetChars, int overlapChars, List<ChunkText> out) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + targetChars, text.length());
            out.add(toChunk(text.substring(start, end)));
            if (end >= text.length()) break;
            start = end - overlapChars;
            if (start < 0) start = 0;
        }
    }

    private void flush(StringBuilder carry, List<ChunkText> out) {
        if (carry.length() > 0) {
            out.add(toChunk(carry.toString().strip()));
            carry.setLength(0);
        }
    }

    private ChunkText toChunk(String content) {
        return new ChunkText(content, -1, content.length() / CHARS_PER_TOKEN);
    }
}
