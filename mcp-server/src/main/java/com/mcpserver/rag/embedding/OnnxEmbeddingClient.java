package com.mcpserver.rag.embedding;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * In-process ONNX embedding client for nomic-embed-text-v1.5 (plan.md §3, §5.7).
 * <p>
 * Runs entirely inside the JVM via ONNX Runtime + DJL HuggingFace tokenizer — no sidecars.
 * nomic-embed-text-v1.5 is a BERT-style encoder: mean-pool token embeddings with the attention
 * mask, then L2-normalize. Queries are prefixed "query: ", documents "passage: ".
 */
@Component
public class OnnxEmbeddingClient implements EmbeddingClient {

    private final String modelDir;
    private final String modelFile;
    private final int dimensions;
    private final int maxLength;

    private HuggingFaceTokenizer tokenizer;
    private ai.onnxruntime.OrtSession session;
    private ai.onnxruntime.OrtEnvironment env;

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

    @PostConstruct
    void init() throws Exception {
        Path tokenizerPath = Path.of(modelDir, "tokenizer.json");
        this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath,
                java.util.Map.of("padding", "longest", "truncation", "true", "maxLength", String.valueOf(maxLength)));

        this.env = ai.onnxruntime.OrtEnvironment.getEnvironment();
        Path modelPath = Path.of(modelDir, modelFile);
        ai.onnxruntime.OrtSession.SessionOptions opts = new ai.onnxruntime.OrtSession.SessionOptions();
        opts.setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.ALL_OPT);
        this.session = env.createSession(modelPath.toString(), opts);
    }

    @Override
    public float[] embed(String text, Mode mode) {
        String prefixed = (mode == Mode.QUERY ? "query: " : "passage: ") + text;
        try {
            // Tokenize via DJL → long[] token ids + attention mask + token type ids.
            var encoding = tokenizer.encode(prefixed);
            long[] inputIds = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();
            long[] typeIds = encoding.getTypeIds();

            // Pad/truncate to a fixed shape the model accepts.
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

                // Output: last_hidden_state [1, seq, 768] as float[][][]
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
        // Mean + L2 normalize.
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
