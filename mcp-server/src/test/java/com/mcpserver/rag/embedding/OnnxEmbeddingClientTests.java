package com.mcpserver.rag.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class OnnxEmbeddingClientTests {

    @TempDir
    Path modelDir;

    @Test
    void nativeRuntimeFailureDegradesToKeywordOnlySearch() throws Exception {
        Files.createFile(modelDir.resolve("tokenizer.json"));
        Files.createFile(modelDir.resolve("model.onnx"));
        OnnxEmbeddingClient client = new OnnxEmbeddingClient(
                modelDir.toString(), "model.onnx", 768, 512) {
            @Override
            protected void loadModel(Path tokenizerPath, Path modelPath) {
                throw new ExceptionInInitializerError(
                        new UnsatisfiedLinkError("onnxruntime.dll initialization failed"));
            }
        };

        assertThatCode(client::ensureLoaded).doesNotThrowAnyException();
        assertThat(client.isReady()).isFalse();
        assertThat(client.getLoadError())
                .contains("Embedding model unavailable")
                .contains("UnsatisfiedLinkError")
                .contains("onnxruntime.dll initialization failed");
    }
}
