package com.mcpserver.plugins;

import com.mcpserver.rag.reranker.OnnxCrossEncoderReranker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnnxModelUploadServiceTests {

    @TempDir
    Path tempDir;

    private final NomicEmbeddingPlugin embeddingPlugin = mock(NomicEmbeddingPlugin.class);
    private final OnnxCrossEncoderReranker reranker = mock(OnnxCrossEncoderReranker.class);

    @Test
    void installsVerifiedEmbeddingFilesAndRefreshesThePlugin() throws Exception {
        byte[] modelBytes = "verified embedding model".getBytes(StandardCharsets.UTF_8);
        byte[] tokenizerBytes = "{\"version\":1}".getBytes(StandardCharsets.UTF_8);
        Path modelPath = tempDir.resolve("embedding/model.onnx");
        Path tokenizerPath = tempDir.resolve("embedding/tokenizer.json");
        when(embeddingPlugin.health()).thenReturn("model loaded");
        when(embeddingPlugin.isReady()).thenReturn(true);

        OnnxModelUploadService service = service(OnnxModelUploadService.ModelKind.EMBEDDING,
                modelPath, tokenizerPath, modelBytes, tokenizerBytes);

        OnnxModelUploadService.ModelStatus status = service.upload("embedding",
                upload("model.onnx", modelBytes), upload("tokenizer.json", tokenizerBytes));

        assertThat(Files.readAllBytes(modelPath)).isEqualTo(modelBytes);
        assertThat(Files.readAllBytes(tokenizerPath)).isEqualTo(tokenizerBytes);
        assertThat(status.installed()).isTrue();
        assertThat(status.ready()).isTrue();
        verify(embeddingPlugin).prepareForModelReplacement();
        verify(embeddingPlugin).refreshAfterModelUpload();
        verify(reranker, never()).unloadModel();
    }

    @Test
    void installsVerifiedRerankerFilesAndReloadsItsNativeSession() throws Exception {
        byte[] modelBytes = "verified reranker model".getBytes(StandardCharsets.UTF_8);
        byte[] tokenizerBytes = "{\"version\":1}".getBytes(StandardCharsets.UTF_8);
        Path modelPath = tempDir.resolve("reranker/model.onnx");
        Path tokenizerPath = tempDir.resolve("reranker/tokenizer.json");
        when(reranker.isLoaded()).thenReturn(true);

        OnnxModelUploadService service = service(OnnxModelUploadService.ModelKind.RERANKER,
                modelPath, tokenizerPath, modelBytes, tokenizerBytes);

        OnnxModelUploadService.ModelStatus status = service.upload("reranker",
                upload("model.onnx", modelBytes), upload("tokenizer.json", tokenizerBytes));

        assertThat(status.installed()).isTrue();
        assertThat(status.ready()).isTrue();
        verify(reranker).unloadModel();
        verify(reranker).reloadModel();
        verify(embeddingPlugin, never()).prepareForModelReplacement();
    }

    @Test
    void checksumFailureLeavesTheExistingFilesAndLoadedModelUntouched() throws Exception {
        byte[] expectedModel = "expected model".getBytes(StandardCharsets.UTF_8);
        byte[] tokenizer = "expected tokenizer".getBytes(StandardCharsets.UTF_8);
        Path modelPath = tempDir.resolve("model.onnx");
        Path tokenizerPath = tempDir.resolve("tokenizer.json");
        Files.writeString(modelPath, "existing model");
        Files.writeString(tokenizerPath, "existing tokenizer");
        OnnxModelUploadService service = service(OnnxModelUploadService.ModelKind.EMBEDDING,
                modelPath, tokenizerPath, expectedModel, tokenizer);

        assertThatThrownBy(() -> service.upload("embedding",
                upload("model.onnx", "wrong model".getBytes(StandardCharsets.UTF_8)),
                upload("tokenizer.json", tokenizer)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");

        assertThat(Files.readString(modelPath)).isEqualTo("existing model");
        assertThat(Files.readString(tokenizerPath)).isEqualTo("existing tokenizer");
        verify(embeddingPlugin, never()).prepareForModelReplacement();
    }

    @Test
    void rejectsOversizedFilesBeforeReadingOrUnloadingAnything() {
        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.isEmpty()).thenReturn(false);
        when(oversized.getSize()).thenReturn(OnnxModelUploadService.MAX_MODEL_BYTES + 1);
        when(oversized.getOriginalFilename()).thenReturn("model.onnx");
        byte[] tokenizer = "tokenizer".getBytes(StandardCharsets.UTF_8);
        OnnxModelUploadService service = service(OnnxModelUploadService.ModelKind.EMBEDDING,
                tempDir.resolve("model.onnx"), tempDir.resolve("tokenizer.json"),
                "model".getBytes(StandardCharsets.UTF_8), tokenizer);

        assertThatThrownBy(() -> service.upload("embedding", oversized,
                upload("tokenizer.json", tokenizer)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256 MB");

        verify(embeddingPlugin, never()).prepareForModelReplacement();
    }

    private OnnxModelUploadService service(OnnxModelUploadService.ModelKind kind,
                                           Path modelPath, Path tokenizerPath,
                                           byte[] modelBytes, byte[] tokenizerBytes) {
        OnnxModelUploadService.ModelDefinition definition = new OnnxModelUploadService.ModelDefinition(
                kind, "Test model", modelPath, tokenizerPath, sha256(modelBytes), sha256(tokenizerBytes),
                "https://example.test/model", "https://example.test/tokenizer");
        return new OnnxModelUploadService(Map.of(kind, definition), embeddingPlugin, reranker);
    }

    private static MockMultipartFile upload(String filename, byte[] bytes) {
        return new MockMultipartFile("file", filename, "application/octet-stream", bytes);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
