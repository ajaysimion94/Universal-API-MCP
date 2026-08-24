package com.mcpserver.plugins;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BundledResourceExtractorTests {

    @TempDir
    Path tempDir;

    @Test
    void downloadsAndInstallsOnlyAValidPinnedFile() throws Exception {
        byte[] content = "verified ONNX bytes".getBytes();
        HttpClient client = clientReturning(content);
        Path target = tempDir.resolve("models/model.onnx");

        BundledResourceExtractor.downloadVerified(
                URI.create("https://models.example/model.onnx"), target, sha256(content),
                1024, Duration.ofSeconds(5), client);

        assertThat(Files.readAllBytes(target)).isEqualTo(content);
        assertThat(stagedFiles()).isEmpty();
    }

    @Test
    void rejectsAChecksumMismatchWithoutInstallingOrLeavingTemporaryFiles() throws Exception {
        HttpClient client = clientReturning("unexpected".getBytes());
        Path target = tempDir.resolve("models/model.onnx");

        assertThatThrownBy(() -> BundledResourceExtractor.downloadVerified(
                URI.create("https://models.example/model.onnx"), target, sha256("expected".getBytes()),
                1024, Duration.ofSeconds(5), client))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("checksum does not match");

        assertThat(target).doesNotExist();
        assertThat(stagedFiles()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static HttpClient clientReturning(byte[] content) throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(
                Map.of("content-length", List.of(String.valueOf(content.length))), (name, value) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(content));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        return client;
    }

    private List<Path> stagedFiles() throws Exception {
        Path parent = tempDir.resolve("models");
        if (!Files.isDirectory(parent)) return List.of();
        try (var files = Files.list(parent)) {
            return files.filter(path -> path.getFileName().toString().startsWith(".mcp-model-download-"))
                    .toList();
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
