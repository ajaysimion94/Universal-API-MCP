package com.mcpserver.rag.embedding;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class OnnxEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingClient.class);

    private final String modelDir;
    private final String modelFile;
    private final int dimensions;
    private final int maxLength;

    private volatile HuggingFaceTokenizer tokenizer;
    private volatile ai.onnxruntime.OrtSession session;
    private volatile ai.onnxruntime.OrtEnvironment env;
    private volatile boolean loaded = false;
    private volatile String loadError;

    public OnnxEmbeddingClient(
            @Value("${rag.embedding.model-dir}") String modelDir,
            @Value("${rag.embedding.model-file}") String modelFile,
            @Value("${rag.embedding.dimensions}") int dimensions,
            @Value("${rag.embedding.max-sequence-length}") int maxLength) {
        this.modelDir = modelDir;
        this.modelFile = modelFile;
        this.dimensions = dimensions;
        this.maxLength = maxLength;
    }

    @Override
    public boolean isReady() {
        return loaded;
    }

    public String getLoadError() {
        return loadError;
    }

    public synchronized void ensureLoaded() {
        if (loaded) return;
        Path tokenizerPath = Path.of(modelDir, "tokenizer.json");
        Path modelPath = Path.of(modelDir, modelFile);
        if (!Files.exists(tokenizerPath) || !Files.exists(modelPath)) {
            loadError = "Model files not found at " + modelDir + " (install via Plugins page)";
            log.info("Embedding model not installed: {}", loadError);
            return;
        }
        try {
            this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath,
                    java.util.Map.of("padding", "longest", "truncation", "true", "maxLength", String.valueOf(maxLength)));
            this.env = ai.onnxruntime.OrtEnvironment.getEnvironment();
            ai.onnxruntime.OrtSession.SessionOptions opts = new ai.onnxruntime.OrtSession.SessionOptions();
            opts.setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.ALL_OPT);
            this.session = env.createSession(modelPath.toString(), opts);
            this.loaded = true;
            this.loadError = null;
            log.info("Embedding model loaded from {}", modelDir);
        } catch (Exception e) {
            loadError = "Failed to load model: " + e.getMessage();
            log.error("Embedding model load failed: {}", e.getMessage());
        }
    }

    /**
     * Release the ONNX session and tokenizer (frees several hundred MB of native
     * memory). Called when the embedding plugin is disabled; {@link #ensureLoaded()}
     * reloads on re-enable. Synchronized against embed() so an in-flight inference
     * finishes before the session closes.
     */
    public synchronized void unload() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (tokenizer != null) tokenizer.close(); } catch (Exception ignored) {}
        session = null;
        tokenizer = null;
        loaded = false;
        loadError = null;
    }

    @Override
    public synchronized float[] embed(String text, Mode mode) {
        ensureLoaded();
        if (!loaded) {
            throw new IllegalStateException("Embedding model not ready: " + loadError);
        }
        String prefixed = (mode == Mode.QUERY ? "query: " : "passage: ") + text;
        try {
            // The tokenizer truncates to maxLength; run at the actual sequence length —
            // padding everything to maxLength would make short chunks pay the full
            // transformer cost (the model has dynamic sequence-length axes).
            var encoding = tokenizer.encode(prefixed);
            long[] inputIds = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();
            long[] typeIds = encoding.getTypeIds();

            java.util.Set<String> inputNames = session.getInputNames();
            java.util.Map<String, ai.onnxruntime.OnnxTensor> inputs = new java.util.HashMap<>();
            ai.onnxruntime.OnnxTensor idsTensor = null;
            ai.onnxruntime.OnnxTensor maskTensor = null;
            ai.onnxruntime.OnnxTensor typeTensor = null;

            try {
                if (inputNames.contains("input_ids")) {
                    idsTensor = ai.onnxruntime.OnnxTensor.createTensor(env, new long[][]{inputIds});
                    inputs.put("input_ids", idsTensor);
                }
                if (inputNames.contains("attention_mask")) {
                    maskTensor = ai.onnxruntime.OnnxTensor.createTensor(env, new long[][]{attentionMask});
                    inputs.put("attention_mask", maskTensor);
                }
                if (inputNames.contains("token_type_ids")) {
                    typeTensor = ai.onnxruntime.OnnxTensor.createTensor(env, new long[][]{typeIds});
                    inputs.put("token_type_ids", typeTensor);
                }

                try (ai.onnxruntime.OrtSession.Result result = session.run(inputs)) {
                    float[][][] hidden = (float[][][]) result.get(0).getValue();
                    return meanPoolNormalize(hidden[0], attentionMask);
                }
            } finally {
                if (idsTensor != null) idsTensor.close();
                if (maskTensor != null) maskTensor.close();
                if (typeTensor != null) typeTensor.close();
            }
        } catch (Exception e) {
            throw new RuntimeException("Embedding inference failed", e);
        }
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private float[] meanPoolNormalize(float[][] tokenEmbeddings, long[] mask) {
        float[] summed = new float[dimensions];
        float count = 0f;
        for (int i = 0; i < tokenEmbeddings.length; i++) {
            if (mask[i] == 1L) {
                for (int d = 0; d < dimensions; d++) summed[d] += tokenEmbeddings[i][d];
                count += 1f;
            }
        }
        if (count == 0f) count = 1f;
        float norm = 0f;
        float[] mean = new float[dimensions];
        for (int d = 0; d < dimensions; d++) {
            mean[d] = summed[d] / count;
            norm += mean[d] * mean[d];
        }
        norm = (float) Math.sqrt(norm);
        if (norm == 0f) norm = 1f;
        for (int d = 0; d < dimensions; d++) mean[d] /= norm;
        return mean;
    }

    @PreDestroy
    void close() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (env != null) env.close(); } catch (Exception ignored) {}
        try { if (tokenizer != null) tokenizer.close(); } catch (Exception ignored) {}
    }
}
