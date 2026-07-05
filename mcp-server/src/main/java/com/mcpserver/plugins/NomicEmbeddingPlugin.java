package com.mcpserver.plugins;

import com.mcpserver.rag.embedding.OnnxEmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;

@Component
public class NomicEmbeddingPlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(NomicEmbeddingPlugin.class);
    private static final String PLUGIN_ID = "nomic-embedding";

    private final OnnxEmbeddingClient embeddingClient;
    private final PluginStateStore stateStore;
    private final String modelDir;
    private final String modelFile;
    private volatile String errorMsg;

    public NomicEmbeddingPlugin(
            OnnxEmbeddingClient embeddingClient,
            PluginStateStore stateStore,
            @Value("${rag.embedding.model-dir}") String modelDir,
            @Value("${rag.embedding.model-file}") String modelFile) {
        this.embeddingClient = embeddingClient;
        this.stateStore = stateStore;
        this.modelDir = modelDir;
        this.modelFile = modelFile;
        tryLoadIfInstalled();
    }

    @Override
    public String id() { return PLUGIN_ID; }

    @Override
    public String name() { return "Nomic embedding model"; }

    @Override
    public String description() { return "nomic-embed-text-v1.5 (768-dim) — in-process ONNX embedding for RAG search. Downloads ~131MB from HuggingFace."; }

    @Override
    public Category category() { return Category.REQUIRED; }

    @Override
    public Status status() {
        if (errorMsg != null) return Status.ERROR;
        if (embeddingClient.isReady()) return Status.ACTIVE;
        if (filesExist()) return Status.INSTALLED;
        return Status.NOT_INSTALLED;
    }

    @Override
    public boolean isEnabled() { return true; }

    @Override
    public boolean isRunning() { return embeddingClient.isReady(); }

    @Override
    public void install() throws Exception {
        Path dir = Path.of(modelDir);
        Files.createDirectories(dir);
        Path modelPath = dir.resolve(modelFile);
        Path tokenizerPath = dir.resolve("tokenizer.json");

        if (!Files.exists(modelPath)) {
            download("https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/resolve/main/onnx/model_quantized.onnx", modelPath);
        }
        if (!Files.exists(tokenizerPath)) {
            download("https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/resolve/main/tokenizer.json", tokenizerPath);
        }

        embeddingClient.ensureLoaded();
        stateStore.setInstalled(PLUGIN_ID, true);
        errorMsg = null;
    }

    @Override
    public void enable() {}

    @Override
    public void disable() {}

    @Override
    public void start() {}

    @Override
    public void stop() {}

    @Override
    public String health() {
        if (embeddingClient.isReady()) return "model loaded, ready to embed";
        if (filesExist()) return "files present, loading…";
        return errorMsg != null ? errorMsg : "not installed";
    }

    @Override
    public boolean isReady() { return embeddingClient.isReady(); }

    private boolean filesExist() {
        return Files.exists(Path.of(modelDir, modelFile)) && Files.exists(Path.of(modelDir, "tokenizer.json"));
    }

    private void tryLoadIfInstalled() {
        if (!stateStore.isInstalled(PLUGIN_ID)) return;
        if (!filesExist()) return;
        try {
            embeddingClient.ensureLoaded();
            errorMsg = null;
        } catch (Exception e) {
            errorMsg = "Failed to load model: " + e.getMessage();
            log.warn("Failed to load embedding model: {}", e.getMessage());
        }
    }

    private void download(String url, Path target) throws Exception {
        log.info("Downloading {} to {}", url, target);
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(target));
        if (resp.statusCode() != 200) {
            throw new IOException("Download failed with status " + resp.statusCode() + " for " + url);
        }
        log.info("Downloaded {} ({} bytes)", target, Files.size(target));
    }
}
