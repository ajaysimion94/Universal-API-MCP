package com.mcpserver.plugins;

import com.mcpserver.rag.embedding.OnnxEmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class NomicEmbeddingPlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(NomicEmbeddingPlugin.class);
    private static final String PLUGIN_ID = "nomic-embedding";

    private final OnnxEmbeddingClient embeddingClient;
    private final PluginStateStore stateStore;
    private final String modelDir;
    private final String modelFile;

    /**
     * The extractor dependency guarantees the bundled model files are on disk before
     * this constructor loads them. Loading is skipped while the plugin is disabled —
     * the RAM escape hatch for low-memory machines (search falls back to keyword-only).
     */
    public NomicEmbeddingPlugin(
            OnnxEmbeddingClient embeddingClient,
            PluginStateStore stateStore,
            BundledResourceExtractor extractor,
            @Value("${rag.embedding.model-dir}") String modelDir,
            @Value("${rag.embedding.model-file}") String modelFile) {
        this.embeddingClient = embeddingClient;
        this.stateStore = stateStore;
        this.modelDir = modelDir;
        this.modelFile = modelFile;
        if (isEnabled() && filesExist()) {
            embeddingClient.ensureLoaded();
        } else if (!isEnabled()) {
            log.info("Embedding model disabled — running keyword-only search (enable via Plugins page)");
        }
    }

    @Override
    public String id() { return PLUGIN_ID; }

    @Override
    public String name() { return "Nomic embedding model"; }

    @Override
    public String description() { return "nomic-embed-text-v1.5 (768-dim) — in-process ONNX embedding for RAG search. Built into the app; disable on low-memory machines to run keyword-only search."; }

    @Override
    public Category category() { return Category.REQUIRED; }

    @Override
    public boolean builtIn() { return true; }

    @Override
    public Status status() {
        if (!isEnabled()) return Status.DISABLED;
        if (embeddingClient.isReady()) return Status.ACTIVE;
        if (!filesExist()) return Status.ERROR;
        return embeddingClient.getLoadError() != null ? Status.ERROR : Status.INSTALLED;
    }

    @Override
    public boolean isEnabled() { return stateStore.isEnabled(PLUGIN_ID); }

    @Override
    public boolean isRunning() { return embeddingClient.isReady(); }

    @Override
    public void install() {
        // Built-in: nothing to install. Retry the load in case the first attempt failed.
        if (isEnabled() && !embeddingClient.isReady()) embeddingClient.ensureLoaded();
    }

    @Override
    public void enable() {
        stateStore.setEnabled(PLUGIN_ID, true);
        if (filesExist()) {
            embeddingClient.ensureLoaded();
        }
    }

    @Override
    public void disable() {
        stateStore.setEnabled(PLUGIN_ID, false);
        embeddingClient.unload();
        log.info("Embedding model disabled and unloaded — search is keyword-only until re-enabled");
    }

    @Override
    public void start() {}

    @Override
    public void stop() {}

    @Override
    public String health() {
        if (!isEnabled()) return "disabled — keyword-only search";
        if (embeddingClient.isReady()) return "model loaded, ready to embed";
        if (!filesExist()) {
            return "model files missing at " + modelDir + " (jar built with -Dskip.bundle=true?)";
        }
        String err = embeddingClient.getLoadError();
        return err != null ? err : "files present, loading…";
    }

    @Override
    public boolean isReady() { return embeddingClient.isReady(); }

    /** Releases Windows file handles before an uploaded model replaces the installed files. */
    public void prepareForModelReplacement() {
        embeddingClient.unload();
    }

    /** Loads a newly uploaded model immediately when semantic search is enabled. */
    public void refreshAfterModelUpload() {
        if (isEnabled()) embeddingClient.ensureLoaded();
    }

    private boolean filesExist() {
        return Files.exists(Path.of(modelDir, modelFile)) && Files.exists(Path.of(modelDir, "tokenizer.json"));
    }
}
