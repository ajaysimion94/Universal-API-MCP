package com.mcpserver.plugins;

import com.mcpserver.repositories.ChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class SqliteVecStorePlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(SqliteVecStorePlugin.class);
    private static final String PLUGIN_ID = "sqlite-vec-store";

    private final ChunkRepository chunkRepository;
    private volatile String errorMsg;

    /**
     * The extractor dependency guarantees the bundled vec0 library is on disk
     * before this constructor loads it into the shared SQLite connection.
     */
    public SqliteVecStorePlugin(ChunkRepository chunkRepository, BundledResourceExtractor extractor) {
        this.chunkRepository = chunkRepository;
        tryLoad();
    }

    @Override
    public String id() { return PLUGIN_ID; }

    @Override
    public String name() { return "Embedded vector store"; }

    @Override
    public String description() { return "SQLite + sqlite-vec + FTS5 — vector and lexical search over ingested chunks. Built into the app; the native extension ships inside the jar."; }

    @Override
    public Category category() { return Category.REQUIRED; }

    @Override
    public boolean builtIn() { return true; }

    @Override
    public Status status() {
        if (chunkRepository.isVec0Available()) return Status.ACTIVE;
        return Status.ERROR;
    }

    @Override
    public boolean isEnabled() { return true; }

    @Override
    public boolean isRunning() { return chunkRepository.isVec0Available(); }

    @Override
    public void install() {
        // Built-in: nothing to install. Retry the load in case the first attempt failed.
        if (!chunkRepository.isVec0Available()) tryLoad();
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
        if (chunkRepository.isVec0Available()) return "vec0 table active";
        return errorMsg != null ? errorMsg : "sqlite-vec extension not loaded";
    }

    @Override
    public boolean isReady() { return chunkRepository.isVec0Available(); }

    private void tryLoad() {
        Path libPath = Path.of(BundledResourceExtractor.SQLITE_VEC_DIR,
                "vec0" + BundledResourceExtractor.libExtension());
        if (!Files.exists(libPath)) {
            errorMsg = "sqlite-vec library missing at " + libPath
                    + " (jar built with -Dskip.bundle=true?)";
            log.warn(errorMsg);
            return;
        }
        try {
            chunkRepository.loadVecExtensionAndInit(libPath.toAbsolutePath().normalize().toString());
            errorMsg = null;
        } catch (Exception e) {
            errorMsg = "Failed to load sqlite-vec: " + e.getMessage();
            log.warn("Failed to load sqlite-vec extension: {}", e.getMessage());
        }
    }
}
