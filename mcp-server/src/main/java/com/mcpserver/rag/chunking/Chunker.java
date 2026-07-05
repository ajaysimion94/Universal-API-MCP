package com.mcpserver.rag.chunking;

import java.util.List;

/**
 * Splits extracted document text into structure-aware chunks (plan.md §5.7).
 * Implementations target 500–1000 token chunks with overlap.
 */
public interface Chunker {

    /**
     * @param text      full extracted document text
     * @param targetTokens target chunk size in tokens
     * @param overlapTokens overlap between adjacent chunks
     * @return ordered chunks (position = index in the list)
     */
    List<ChunkText> chunk(String text, int targetTokens, int overlapTokens);

    record ChunkText(String content, int position, int approxTokenCount) {}
}
