package com.mcpserver.rag.embedding;

/**
 * Embeds text into a dense vector (plan.md §5.7).
 * <p>
 * Provider-swappable seam: the default {@code OnnxEmbeddingClient} runs nomic-embed-text-v1.5
 * in-process via ONNX Runtime. nomic uses different prefixes for queries vs documents.
 */
public interface EmbeddingClient {

    enum Mode { QUERY, DOCUMENT }

    /**
     * @param text  the input to embed
     * @param mode  QUERY for search queries (prefixes "query: "), DOCUMENT for ingestion ("passage: ")
     * @return a normalized float vector (768-dim for nomic-embed-text-v1.5)
     */
    float[] embed(String text, Mode mode);

    /** Dimensionality of the embedding vector. */
    int dimensions();

    /** Whether the embedding model is loaded and ready to produce vectors. */
    default boolean isReady() { return true; }
}
