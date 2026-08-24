package com.mcpserver.plugins;

import com.mcpserver.config.TlsHttpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Materializes bundled resources onto disk before their consumers initialize. ONNX files omitted
 * from a small build are provisioned automatically from their pinned, checksum-verified sources.
 * Existing local files are never replaced. Provisioning failures are logged and leave the server in
 * its normal degraded mode so file management and lexical search remain available.
 */
@Component
public class BundledResourceExtractor {

    private static final Logger log = LoggerFactory.getLogger(BundledResourceExtractor.class);

    public static final String SQLITE_VEC_DIR = "lib/sqlite-vec";
    private static final long MAX_MODEL_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_TOKENIZER_BYTES = 16L * 1024L * 1024L;
    private static final String TOKENIZER_SHA256 =
            "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66";
    private static final String EMBEDDING_MODEL_SHA256 =
            "b4342336debaea79de872370664b0aaeb67dea4605513d00ee236ea871a81f27";
    private static final String RERANKER_MODEL_SHA256 =
            "5d3e70fd0c9ff14b9b5169a51e957b7a9c74897afd0a35ce4bd318150c1d4d4a";
    private static final URI EMBEDDING_MODEL_URI = URI.create(
            "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/resolve/main/onnx/model_quantized.onnx");
    private static final URI EMBEDDING_TOKENIZER_URI = URI.create(
            "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/resolve/main/tokenizer.json");
    private static final URI RERANKER_MODEL_URI = URI.create(
            "https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/main/onnx/model.onnx");
    private static final URI RERANKER_TOKENIZER_URI = URI.create(
            "https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/main/tokenizer.json");

    public BundledResourceExtractor(@Value("${rag.embedding.model-dir}") String modelDir,
                                    @Value("${rag.embedding.model-file}") String modelFile,
                                    @Value("${rag.reranker.model-dir:${user.dir}/models/ms-marco-MiniLM-L6-v2}") String rerankerDir,
                                    @Value("${rag.reranker.model-file:model.onnx}") String rerankerFile,
                                    @Value("${rag.models.auto-download:true}") boolean autoDownload,
                                    @Value("${rag.models.download-timeout:PT10M}") Duration downloadTimeout,
                                    TlsHttpClientFactory tlsHttpClientFactory) {
        HttpClient client = tlsHttpClientFactory.builder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        provisionModelFile("bundled/model/" + modelFile, Path.of(modelDir, modelFile),
                EMBEDDING_MODEL_URI, EMBEDDING_MODEL_SHA256, MAX_MODEL_BYTES,
                autoDownload, downloadTimeout, client);
        provisionModelFile("bundled/model/tokenizer.json", Path.of(modelDir, "tokenizer.json"),
                EMBEDDING_TOKENIZER_URI, TOKENIZER_SHA256, MAX_TOKENIZER_BYTES,
                autoDownload, downloadTimeout, client);
        provisionModelFile("bundled/reranker/" + rerankerFile, Path.of(rerankerDir, rerankerFile),
                RERANKER_MODEL_URI, RERANKER_MODEL_SHA256, MAX_MODEL_BYTES,
                autoDownload, downloadTimeout, client);
        provisionModelFile("bundled/reranker/tokenizer.json", Path.of(rerankerDir, "tokenizer.json"),
                RERANKER_TOKENIZER_URI, TOKENIZER_SHA256, MAX_TOKENIZER_BYTES,
                autoDownload, downloadTimeout, client);
        String lib = "vec0" + libExtension();
        extractIfMissing("bundled/sqlite-vec/" + detectOs() + "-" + detectArch() + "/" + lib,
                Path.of(SQLITE_VEC_DIR, lib));
    }

    private void provisionModelFile(String resource, Path target, URI source, String sha256,
                                    long maxBytes, boolean autoDownload, Duration timeout,
                                    HttpClient client) {
        if (Files.isRegularFile(target)) return;
        if (extractIfMissing(resource, target)) return;
        if (!autoDownload) {
            log.info("Automatic ONNX provisioning is disabled; {} is still missing", target);
            return;
        }
        try {
            downloadVerified(source, target, sha256, maxBytes, timeout, client);
            log.info("Provisioned pinned ONNX resource {} → {}", source, target);
        } catch (Exception exception) {
            log.warn("Could not provision {} from {}: {}", target, source, exception.getMessage());
        }
    }

    private boolean extractIfMissing(String resource, Path target) {
        try {
            ClassPathResource cpr = new ClassPathResource(resource);
            if (!cpr.exists()) {
                log.info("Bundled resource {} not in this build — expecting {} to already exist", resource, target);
                return false;
            }
            long size = cpr.contentLength();
            if (Files.exists(target) && Files.size(target) == size) {
                return true;
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            try (InputStream in = cpr.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Extracted bundled {} → {} ({} bytes)", resource, target, size);
            return true;
        } catch (IOException e) {
            log.warn("Failed to extract bundled resource {} to {}: {}", resource, target, e.getMessage());
            return false;
        }
    }

    static void downloadVerified(URI source, Path target, String expectedSha256, long maxBytes,
                                 Duration timeout, HttpClient client) throws IOException, InterruptedException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Model target has no parent directory");
        Files.createDirectories(parent);
        Path staged = Files.createTempFile(parent, ".mcp-model-download-", ".tmp");
        try {
            HttpRequest request = HttpRequest.newBuilder(source).timeout(timeout).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new IOException("download returned HTTP " + response.statusCode());
            }
            long declaredLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
            if (declaredLength > maxBytes) {
                response.body().close();
                throw new IOException("download is larger than " + (maxBytes / 1024 / 1024) + " MB");
            }
            MessageDigest digest = sha256();
            long copied;
            try (InputStream body = response.body();
                 DigestInputStream verified = new DigestInputStream(body, digest)) {
                copied = copyBounded(verified, staged, maxBytes);
            }
            if (copied == 0) throw new IOException("download was empty");
            String actualSha256 = HexFormat.of().formatHex(digest.digest());
            if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
                throw new IOException("checksum does not match the pinned resource");
            }
            move(staged, target);
            staged = null;
        } finally {
            if (staged != null) Files.deleteIfExists(staged);
        }
    }

    private static long copyBounded(InputStream input, Path target, long maxBytes) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (var output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("download is larger than " + (maxBytes / 1024 / 1024) + " MB");
                }
                output.write(buffer, 0, read);
            }
        }
        return total;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String detectOs() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("linux")) return "linux";
        if (os.contains("win")) return "windows";
        return "unknown";
    }

    public static String detectArch() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) return "aarch64";
        return "x86_64";
    }

    public static String libExtension() {
        return switch (detectOs()) {
            case "macos" -> ".dylib";
            case "windows" -> ".dll";
            default -> ".so";
        };
    }
}
