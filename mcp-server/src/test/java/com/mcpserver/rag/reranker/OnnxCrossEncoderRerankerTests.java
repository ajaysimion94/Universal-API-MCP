package com.mcpserver.rag.reranker;

import com.mcpserver.models.Chunk;
import com.mcpserver.plugins.BundledResourceExtractor;
import com.mcpserver.rag.embedding.EmbeddingClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OnnxCrossEncoderRerankerTests {

    @TempDir
    Path temp;

    @Test
    void semanticFallbackReordersCandidatesAndProducesBoundedScores() {
        EmbeddingClient embeddings = new EmbeddingClient() {
            @Override
            public float[] embed(String text, Mode mode) {
                return text.toLowerCase().contains("mars")
                        ? new float[]{1f, 0f}
                        : new float[]{0f, 1f};
            }

            @Override
            public int dimensions() {
                return 2;
            }
        };
        OnnxCrossEncoderReranker reranker = new OnnxCrossEncoderReranker(
                embeddings, mock(BundledResourceExtractor.class),
                temp.toString(), "missing.onnx", 64);
        Chunk irrelevant = chunk("a", "Venus", "Clouds on Venus", new float[]{0f, 1f});
        Chunk relevant = chunk("b", "Mars", "Mars is called the red planet", new float[]{1f, 0f});

        List<Reranker.ScoredChunk> ranked =
                reranker.rerank("Which planet is Mars, the red planet?", List.of(irrelevant, relevant));

        assertThat(ranked).extracting(scored -> scored.chunk().sourceName())
                .containsExactly("Mars", "Venus");
        assertThat(ranked).allSatisfy(scored ->
                assertThat(scored.score()).isBetween(0f, 1f));
    }

    @Test
    void lexicalCoverageIgnoresQuestionBoilerplate() {
        assertThat(OnnxCrossEncoderReranker.lexicalCoverage(
                "How do I configure Spring Boot security?",
                "Spring Boot security configuration reference"))
                .isGreaterThan(0.5f);
    }

    @Test
    @Tag("onnx")
    void bundledCrossEncoderScoresRelevantPassageHigher() {
        Path bundled = Path.of("target/classes/bundled/reranker");
        Assumptions.assumeTrue(Files.exists(bundled.resolve("model.onnx")),
                "bundle downloads were explicitly skipped");
        EmbeddingClient unavailableFallback = mock(EmbeddingClient.class);
        OnnxCrossEncoderReranker reranker = new OnnxCrossEncoderReranker(
                unavailableFallback, mock(BundledResourceExtractor.class),
                bundled.toString(), "model.onnx", 128);

        List<Reranker.ScoredChunk> ranked = reranker.rerank(
                "Which planet is known as the red planet?",
                List.of(
                        chunk("venus", "Venus", "Venus has a dense cloudy atmosphere", null),
                        chunk("mars", "Mars", "Mars is known as the red planet", null)));

        assertThat(reranker.isModelReady())
                .as("cross-encoder ONNX model should load; %s", reranker.loadError())
                .isTrue();
        assertThat(ranked.get(0).chunk().sourceName()).isEqualTo("Mars");
        assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(1).score());
        reranker.close();
    }

    private static Chunk chunk(String fileId, String name, String content, float[] embedding) {
        return Chunk.create(fileId, name, "/" + name, content, embedding,
                List.of("public"), 0, 10);
    }
}
