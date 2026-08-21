package com.mcpserver.plugins;

import com.mcpserver.rag.reranker.OnnxCrossEncoderReranker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Installs the two pinned ONNX model bundles from browser uploads. Files are streamed to temporary
 * siblings, SHA-256 verified before the active model is unloaded, and then moved into place. The
 * uploaded filenames are never used as paths.
 */
@Service
public class OnnxModelUploadService {

    static final long MAX_MODEL_BYTES = 256L * 1024L * 1024L;
    static final long MAX_TOKENIZER_BYTES = 16L * 1024L * 1024L;

    private static final String TOKENIZER_SHA256 =
            "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66";

    private final Map<ModelKind, ModelDefinition> definitions;
    private final NomicEmbeddingPlugin embeddingPlugin;
    private final OnnxCrossEncoderReranker reranker;

    @Autowired
    public OnnxModelUploadService(
            NomicEmbeddingPlugin embeddingPlugin,
            OnnxCrossEncoderReranker reranker,
            @Value("${rag.embedding.model-dir}") String embeddingDir,
            @Value("${rag.embedding.model-file}") String embeddingFile,
            @Value("${rag.reranker.model-dir:${user.dir}/models/ms-marco-MiniLM-L6-v2}") String rerankerDir,
            @Value("${rag.reranker.model-file:model.onnx}") String rerankerFile) {
        this(Map.of(
                ModelKind.EMBEDDING, new ModelDefinition(
                        ModelKind.EMBEDDING,
                        "Nomic embedding",
                        Path.of(embeddingDir, embeddingFile),
                        Path.of(embeddingDir, "tokenizer.json"),
                        "b4342336debaea79de872370664b0aaeb67dea4605513d00ee236ea871a81f27",
                        TOKENIZER_SHA256,
                        "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/resolve/main/onnx/model_quantized.onnx",
                        "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/resolve/main/tokenizer.json"),
                ModelKind.RERANKER, new ModelDefinition(
                        ModelKind.RERANKER,
                        "MiniLM reranker",
                        Path.of(rerankerDir, rerankerFile),
                        Path.of(rerankerDir, "tokenizer.json"),
                        "5d3e70fd0c9ff14b9b5169a51e957b7a9c74897afd0a35ce4bd318150c1d4d4a",
                        TOKENIZER_SHA256,
                        "https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/main/onnx/model.onnx",
                        "https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/main/tokenizer.json")),
                embeddingPlugin, reranker);
    }

    OnnxModelUploadService(Map<ModelKind, ModelDefinition> definitions,
                           NomicEmbeddingPlugin embeddingPlugin,
                           OnnxCrossEncoderReranker reranker) {
        this.definitions = Map.copyOf(definitions);
        this.embeddingPlugin = embeddingPlugin;
        this.reranker = reranker;
    }

    public synchronized List<ModelStatus> statuses() {
        return List.of(status(ModelKind.EMBEDDING), status(ModelKind.RERANKER));
    }

    public synchronized ModelStatus upload(String kindValue, MultipartFile model, MultipartFile tokenizer) {
        ModelKind kind = ModelKind.from(kindValue);
        ModelDefinition definition = definition(kind);
        validateUpload(model, ".onnx", MAX_MODEL_BYTES, "ONNX model");
        validateUpload(tokenizer, ".json", MAX_TOKENIZER_BYTES, "tokenizer");

        Path stagedModel = null;
        Path stagedTokenizer = null;
        try {
            stagedModel = stage(model, definition.modelPath(), definition.modelSha256(), "ONNX model");
            stagedTokenizer = stage(tokenizer, definition.tokenizerPath(),
                    definition.tokenizerSha256(), "tokenizer");

            prepareForReplacement(kind);
            replacePair(stagedModel, definition.modelPath(), stagedTokenizer, definition.tokenizerPath());
            stagedModel = null;
            stagedTokenizer = null;
            reload(kind);
            return status(kind);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not store the uploaded model files: "
                    + exception.getMessage(), exception);
        } finally {
            deleteQuietly(stagedModel);
            deleteQuietly(stagedTokenizer);
        }
    }

    private ModelStatus status(ModelKind kind) {
        ModelDefinition definition = definition(kind);
        boolean modelPresent = Files.isRegularFile(definition.modelPath());
        boolean tokenizerPresent = Files.isRegularFile(definition.tokenizerPath());
        boolean installed = modelPresent && tokenizerPresent;
        boolean ready;
        String health;
        if (kind == ModelKind.EMBEDDING) {
            ready = embeddingPlugin.isReady();
            health = embeddingPlugin.health();
        } else {
            ready = reranker.isLoaded();
            if (!installed) {
                health = "model files not installed";
            } else if (ready) {
                health = "model loaded, ready to rerank";
            } else if (reranker.loadError() != null) {
                health = reranker.loadError();
            } else {
                health = "files installed; model loads on the first search";
            }
        }
        return new ModelStatus(
                kind.apiName(), definition.name(), definition.modelPath().getFileName().toString(),
                definition.tokenizerPath().getFileName().toString(), installed, ready,
                size(definition.modelPath()), size(definition.tokenizerPath()), health,
                definition.modelDownloadUrl(), definition.tokenizerDownloadUrl());
    }

    private void prepareForReplacement(ModelKind kind) {
        if (kind == ModelKind.EMBEDDING) {
            embeddingPlugin.prepareForModelReplacement();
        } else {
            reranker.unloadModel();
        }
    }

    private void reload(ModelKind kind) {
        if (kind == ModelKind.EMBEDDING) {
            embeddingPlugin.refreshAfterModelUpload();
        } else {
            reranker.reloadModel();
        }
    }

    private Path stage(MultipartFile upload, Path target, String expectedSha256, String label)
            throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IllegalStateException("Model target has no parent directory");
        Files.createDirectories(parent);
        Path staged = Files.createTempFile(parent, ".mcp-model-upload-", ".tmp");
        MessageDigest digest = sha256();
        try (InputStream raw = upload.getInputStream();
             DigestInputStream verified = new DigestInputStream(raw, digest)) {
            Files.copy(verified, staged, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            deleteQuietly(staged);
            throw exception;
        }
        String actualSha256 = HexFormat.of().formatHex(digest.digest());
        if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
            deleteQuietly(staged);
            throw new IllegalArgumentException(label + " checksum does not match the supported pinned file");
        }
        return staged;
    }

    private void replacePair(Path stagedModel, Path modelTarget,
                             Path stagedTokenizer, Path tokenizerTarget) throws IOException {
        Path modelBackup = backupPath(modelTarget);
        Path tokenizerBackup = backupPath(tokenizerTarget);
        boolean hadModel = Files.exists(modelTarget);
        boolean hadTokenizer = Files.exists(tokenizerTarget);
        boolean modelBackedUp = false;
        boolean tokenizerBackedUp = false;
        boolean installed = false;
        try {
            if (hadModel) {
                move(modelTarget, modelBackup);
                modelBackedUp = true;
            }
            if (hadTokenizer) {
                move(tokenizerTarget, tokenizerBackup);
                tokenizerBackedUp = true;
            }
            move(stagedTokenizer, tokenizerTarget);
            move(stagedModel, modelTarget);
            installed = true;
        } catch (IOException failure) {
            if (!hadModel || modelBackedUp) deleteQuietly(modelTarget);
            if (!hadTokenizer || tokenizerBackedUp) deleteQuietly(tokenizerTarget);
            try {
                if (modelBackedUp && Files.exists(modelBackup)) move(modelBackup, modelTarget);
                if (tokenizerBackedUp && Files.exists(tokenizerBackup)) move(tokenizerBackup, tokenizerTarget);
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            // Preserve a backup for manual recovery if rollback itself failed.
            if (installed) {
                deleteQuietly(modelBackup);
                deleteQuietly(tokenizerBackup);
            }
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validateUpload(MultipartFile file, String extension, long maxBytes, String label) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(label + " file is required");
        }
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException(label + " is larger than " + (maxBytes / 1024 / 1024) + " MB");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(extension)) {
            throw new IllegalArgumentException(label + " must be a " + extension + " file");
        }
    }

    private ModelDefinition definition(ModelKind kind) {
        ModelDefinition definition = definitions.get(kind);
        if (definition == null) throw new IllegalArgumentException("Unsupported model kind: " + kind.apiName());
        return definition;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static long size(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static Path backupPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".backup-" + UUID.randomUUID());
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    enum ModelKind {
        EMBEDDING("embedding"),
        RERANKER("reranker");

        private final String apiName;

        ModelKind(String apiName) {
            this.apiName = apiName;
        }

        String apiName() {
            return apiName;
        }

        static ModelKind from(String value) {
            for (ModelKind kind : values()) {
                if (kind.apiName.equalsIgnoreCase(value)) return kind;
            }
            throw new IllegalArgumentException("Unsupported model kind: " + value);
        }
    }

    record ModelDefinition(ModelKind kind, String name, Path modelPath, Path tokenizerPath,
                           String modelSha256, String tokenizerSha256,
                           String modelDownloadUrl, String tokenizerDownloadUrl) {
    }

    public record ModelStatus(String kind, String name, String modelFile, String tokenizerFile,
                              boolean installed, boolean ready, long modelBytes, long tokenizerBytes,
                              String health, String modelDownloadUrl, String tokenizerDownloadUrl) {
    }
}
