package com.mcpserver.rag.reranker;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.mcpserver.models.Chunk;
import com.mcpserver.plugins.BundledResourceExtractor;
import com.mcpserver.rag.embedding.EmbeddingClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-process cross-encoder reranker.
 *
 * <p>The bundled {@code ms-marco-MiniLM-L6-v2} model scores each query/passage
 * pair directly. A deterministic bi-encoder + lexical fallback keeps ranking
 * semantic and stable in development builds made with {@code -Dskip.bundle=true}
 * or on a platform where the optional reranker cannot be loaded.</p>
 */
@Component
public class OnnxCrossEncoderReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(OnnxCrossEncoderReranker.class);
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "how",
            "in", "is", "it", "of", "on", "or", "that", "the", "this", "to", "was",
            "what", "when", "where", "which", "who", "why", "with");

    private final EmbeddingClient embeddingClient;
    private final Path modelPath;
    private final Path tokenizerPath;
    private final int maxLength;

    private HuggingFaceTokenizer tokenizer;
    private OrtEnvironment environment;
    private OrtSession session;
    private boolean loadAttempted;
    private String loadError;

    public OnnxCrossEncoderReranker(
            EmbeddingClient embeddingClient,
            BundledResourceExtractor extractor,
            @Value("${rag.reranker.model-dir:${user.dir}/models/ms-marco-MiniLM-L6-v2}") String modelDir,
            @Value("${rag.reranker.model-file:model.onnx}") String modelFile,
            @Value("${rag.reranker.max-sequence-length:512}") int maxLength) {
        this.embeddingClient = embeddingClient;
        this.modelPath = Path.of(modelDir, modelFile);
        this.tokenizerPath = Path.of(modelDir, "tokenizer.json");
        this.maxLength = maxLength;
        // The dependency is intentional: bundled files must be materialized first.
        if (extractor == null) throw new IllegalArgumentException("Bundled resource extractor is required");
    }

    @Override
    public synchronized List<ScoredChunk> rerank(String query, List<Chunk> candidates) {
        if (candidates.isEmpty()) return List.of();

        if (ensureLoaded()) {
            try {
                return crossEncode(query, candidates);
            } catch (Exception e) {
                loadError = rootCauseMessage(e);
                closeModel();
                log.warn("Cross-encoder inference failed; using semantic fallback: {}", loadError);
            }
        }
        return semanticFallback(query, candidates);
    }

    public synchronized boolean isModelReady() {
        return ensureLoaded();
    }

    public synchronized String loadError() {
        return loadError;
    }

    /** Whether the native reranker session is currently loaded, without triggering a lazy load. */
    public synchronized boolean isLoaded() {
        return session != null;
    }

    /** Releases the current model and resets the lazy-load guard before files are replaced. */
    public synchronized void unloadModel() {
        closeModel();
        loadAttempted = false;
        loadError = null;
    }

    /** Validates and loads newly installed model files. */
    public synchronized boolean reloadModel() {
        unloadModel();
        return ensureLoaded();
    }

    private boolean ensureLoaded() {
        if (session != null) return true;
        if (loadAttempted) return false;
        loadAttempted = true;
        if (!Files.exists(modelPath) || !Files.exists(tokenizerPath)) {
            loadError = "reranker model files are missing from " + modelPath.getParent();
            log.info("{}; semantic fallback remains active", loadError);
            return false;
        }
        try {
            tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath,
                    Map.of("padding", "false", "truncation", "true",
                            "maxLength", String.valueOf(maxLength)));
            environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = environment.createSession(modelPath.toString(), options);
            loadError = null;
            log.info("Cross-encoder reranker loaded from {}", modelPath);
            return true;
        } catch (Exception | LinkageError e) {
            loadError = rootCauseMessage(e);
            closeModel();
            log.warn("Cross-encoder unavailable; semantic fallback remains active: {}", loadError);
            return false;
        }
    }

    private List<ScoredChunk> crossEncode(String query, List<Chunk> candidates) throws Exception {
        List<ScoredChunk> scored = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            Chunk chunk = candidates.get(index);
            String passage = passage(chunk);
            var encoding = tokenizer.encode(query, passage);

            Map<String, OnnxTensor> inputs = new HashMap<>();
            try {
                Set<String> names = session.getInputNames();
                if (names.contains("input_ids")) {
                    inputs.put("input_ids", OnnxTensor.createTensor(
                            environment, new long[][]{encoding.getIds()}));
                }
                if (names.contains("attention_mask")) {
                    inputs.put("attention_mask", OnnxTensor.createTensor(
                            environment, new long[][]{encoding.getAttentionMask()}));
                }
                if (names.contains("token_type_ids")) {
                    inputs.put("token_type_ids", OnnxTensor.createTensor(
                            environment, new long[][]{encoding.getTypeIds()}));
                }
                try (OrtSession.Result result = session.run(inputs)) {
                    float logit = firstFloat(result.get(0).getValue());
                    float relevance = sigmoid(logit);
                    float lexical = lexicalCoverage(query, passage);
                    // Retrieval rank helps choose the candidate set, but is not
                    // evidence that an arbitrary candidate answers the question.
                    // Keeping it out of the confidence score makes abstention
                    // possible for genuinely unrelated corpora.
                    float score = clamp01(0.90f * relevance + 0.10f * lexical);
                    scored.add(new ScoredChunk(chunk, score));
                }
            } finally {
                for (OnnxTensor tensor : inputs.values()) tensor.close();
            }
        }
        return stableSort(scored);
    }

    private List<ScoredChunk> semanticFallback(String query, List<Chunk> candidates) {
        float[] queryEmbedding = null;
        if (embeddingClient.isReady()) {
            try {
                queryEmbedding = embeddingClient.embed(query, EmbeddingClient.Mode.QUERY);
            } catch (RuntimeException e) {
                log.debug("Fallback query embedding unavailable: {}", e.getMessage());
            }
        }

        List<ScoredChunk> scored = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            Chunk chunk = candidates.get(index);
            float lexical = lexicalCoverage(query, passage(chunk));
            float rankPrior = rankPrior(index, candidates.size());
            float score;
            if (queryEmbedding != null && chunk.embedding() != null
                    && queryEmbedding.length == chunk.embedding().length) {
                float semantic = clamp01((cosine(queryEmbedding, chunk.embedding()) + 1f) / 2f);
                score = 0.65f * semantic + 0.25f * lexical + 0.10f * rankPrior;
            } else {
                score = 0.70f * lexical + 0.30f * rankPrior;
            }
            scored.add(new ScoredChunk(chunk, clamp01(score)));
        }
        return stableSort(scored);
    }

    private static List<ScoredChunk> stableSort(List<ScoredChunk> scored) {
        return scored.stream()
                .sorted(Comparator.comparing(ScoredChunk::score).reversed()
                        .thenComparing(sc -> sc.chunk().id()))
                .toList();
    }

    private static String passage(Chunk chunk) {
        String content = chunk.content() == null ? "" : chunk.content();
        if (content.length() > 12_000) content = content.substring(0, 12_000);
        return (chunk.sourceName() == null ? "" : chunk.sourceName() + "\n") + content;
    }

    static float lexicalCoverage(String query, String passage) {
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) return 0f;
        Set<String> passageTerms = terms(passage);
        long matched = queryTerms.stream().filter(passageTerms::contains).count();
        return (float) matched / queryTerms.size();
    }

    private static Set<String> terms(String value) {
        Set<String> terms = new HashSet<>();
        if (value == null) return terms;
        for (String term : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}._-]+")) {
            if (term.length() > 1 && !STOP_WORDS.contains(term)) terms.add(term);
        }
        return terms;
    }

    private static float cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) return 0f;
        return (float) (dot / Math.sqrt(leftNorm * rightNorm));
    }

    private static float rankPrior(int index, int size) {
        if (size <= 1) return 1f;
        return 1f - ((float) index / (size - 1));
    }

    private static float sigmoid(float value) {
        return (float) (1d / (1d + Math.exp(-value)));
    }

    private static float firstFloat(Object value) {
        if (value instanceof float[] values && values.length > 0) return values[0];
        if (value instanceof float[][] values && values.length > 0 && values[0].length > 0) {
            return values[0][0];
        }
        throw new IllegalStateException("Unexpected reranker output type: "
                + (value == null ? "null" : value.getClass().getName()));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static String rootCauseMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private void closeModel() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (tokenizer != null) tokenizer.close(); } catch (Exception ignored) {}
        session = null;
        tokenizer = null;
        environment = null;
    }

    @PreDestroy
    public synchronized void close() {
        closeModel();
    }
}
