package com.mcpserver.plugins;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Materializes resources bundled inside the jar (embedding model, per-platform
 * sqlite-vec extension) onto disk at the paths the loaders already read from, so a
 * fresh machine needs zero downloads. Runs during bean construction, before the
 * plugins that consume the files (they declare a constructor dependency on this).
 * Missing classpath resources (a {@code -Dskip.bundle=true} build) are skipped —
 * already-extracted local files keep working.
 */
@Component
public class BundledResourceExtractor {

    private static final Logger log = LoggerFactory.getLogger(BundledResourceExtractor.class);

    public static final String SQLITE_VEC_DIR = "lib/sqlite-vec";

    public BundledResourceExtractor(@Value("${rag.embedding.model-dir}") String modelDir,
                                    @Value("${rag.embedding.model-file}") String modelFile,
                                    @Value("${rag.reranker.model-dir:${user.dir}/models/ms-marco-MiniLM-L6-v2}") String rerankerDir,
                                    @Value("${rag.reranker.model-file:model.onnx}") String rerankerFile) {
        extractIfMissing("bundled/model/" + modelFile, Path.of(modelDir, modelFile));
        extractIfMissing("bundled/model/tokenizer.json", Path.of(modelDir, "tokenizer.json"));
        extractIfMissing("bundled/reranker/" + rerankerFile, Path.of(rerankerDir, rerankerFile));
        extractIfMissing("bundled/reranker/tokenizer.json", Path.of(rerankerDir, "tokenizer.json"));
        String lib = "vec0" + libExtension();
        extractIfMissing("bundled/sqlite-vec/" + detectOs() + "-" + detectArch() + "/" + lib,
                Path.of(SQLITE_VEC_DIR, lib));
    }

    private void extractIfMissing(String resource, Path target) {
        try {
            ClassPathResource cpr = new ClassPathResource(resource);
            if (!cpr.exists()) {
                log.info("Bundled resource {} not in this build — expecting {} to already exist", resource, target);
                return;
            }
            long size = cpr.contentLength();
            if (Files.exists(target) && Files.size(target) == size) {
                return;
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            try (InputStream in = cpr.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Extracted bundled {} → {} ({} bytes)", resource, target, size);
        } catch (IOException e) {
            log.warn("Failed to extract bundled resource {} to {}: {}", resource, target, e.getMessage());
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
