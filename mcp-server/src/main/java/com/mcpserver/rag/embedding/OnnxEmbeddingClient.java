package com.mcpserver.rag.embedding;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

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

    @Override
    public float[] embed(String text, Mode mode) {
        ensureLoaded();
        if (!loaded) {
            throw new IllegalStateException("Embedding model not ready: " + loadError);
        }
        String prefixed = (mode == Mode.QUERY ? "query: " : "passage: ") + text;
        try {
            var encoding = tokenizer.encode(prefixed);
            long[] inputIds = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();
            long[] typeIds = encoding.getTypeIds();

            long[] paddedIds = pad(inputIds, maxLength, 0L);
            long[] paddedMask = pad(attentionMask, maxLength, 0L);
            long[] paddedTypes = pad(typeIds, maxLength, 0L);

            try (ai.onnxruntime.OnnxTensor idsTensor = ai.onnxruntime.OnnxTensor.createTensor(env, new long[][]{paddedIds});
                 ai.onnxruntime.OnnxTensor maskTensor = ai.onnxruntime.OnnxTensor.createTensor(env, new long[][]{paddedMask});
                 ai.onnxruntime.OnnxTensor typeTensor = ai.onnxruntime.OnnxTensor.createTensor(env, new long[][]{paddedTypes});
                 ai.onnxruntime.OrtSession.Result result = session.run(
                         java.util.Map.of("input_ids", idsTensor,
                                 "attention_mask", maskTensor,
                                 "token_type_ids", typeTensor))) {

                float[][][] hidden = (float[][][]) result.get(0).getValue();
                return meanPoolNormalize(hidden[0], paddedMask);
            }
        } catch (Exception e) {
            throw new RuntimeException("Embedding inference failed", e);
        }
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private long[] pad(long[] input, int length, long fill) {
        long[] out = new long[length];
        Arrays.fill(out, fill);
        System.arraycopy(input, 0, out, 0, Math.min(input.length, length));
        return out;
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
