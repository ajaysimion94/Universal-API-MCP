package com.mcpserver.plugins;

import com.mcpserver.repositories.ChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;

@Component
public class SqliteVecStorePlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(SqliteVecStorePlugin.class);
    private static final String PLUGIN_ID = "sqlite-vec-store";
    private static final String LIB_DIR = "lib/sqlite-vec";

    private final ChunkRepository chunkRepository;
    private final PluginStateStore stateStore;
    private volatile String errorMsg;

    public SqliteVecStorePlugin(ChunkRepository chunkRepository, PluginStateStore stateStore) {
        this.chunkRepository = chunkRepository;
        this.stateStore = stateStore;
        tryLoadIfInstalled();
    }

    @Override
    public String id() { return PLUGIN_ID; }

    @Override
    public String name() { return "Embedded vector store"; }

    @Override
    public String description() { return "SQLite + sqlite-vec + FTS5 — vector and lexical search over ingested chunks. Built into the app; the sqlite-vec native extension is downloaded on install."; }

    @Override
    public Category category() { return Category.REQUIRED; }

    @Override
    public Status status() {
        if (errorMsg != null) return Status.ERROR;
        if (chunkRepository.isVec0Available()) return Status.ACTIVE;
        if (stateStore.isInstalled(PLUGIN_ID)) return Status.INSTALLED;
        return Status.NOT_INSTALLED;
    }

    @Override
    public boolean isEnabled() { return true; }

    @Override
    public boolean isRunning() { return chunkRepository.isVec0Available(); }

    @Override
    public void install() throws Exception {
        String os = detectOs();
        String arch = detectArch();
        String ext = extForOs(os);
        String fileName = "vec0" + ext;
        Path libDir = Path.of(LIB_DIR);
        Files.createDirectories(libDir);
        Path libPath = libDir.resolve(fileName);

        if (Files.exists(libPath)) {
            log.info("sqlite-vec lib already exists at {}", libPath);
        } else {
            String url = downloadUrl(os, arch);
            log.info("Downloading sqlite-vec from {}", url);
            
            Path tarPath = libDir.resolve("sqlite-vec.tar.gz");
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(tarPath));
            if (resp.statusCode() != 200) {
                throw new IOException("Download failed with status " + resp.statusCode());
            }
            log.info("Downloaded sqlite-vec tarball to {}", tarPath);
            
            extractTarGz(tarPath, libDir);
            Files.deleteIfExists(tarPath);
            
            if (!Files.exists(libPath)) {
                throw new IOException("Extraction succeeded but " + fileName + " not found in " + libDir);
            }
            log.info("Extracted sqlite-vec to {}", libPath);
        }

        String extPath = libPath.toAbsolutePath().toString();
        if (extPath.endsWith(ext)) {
            extPath = extPath.substring(0, extPath.length() - ext.length());
        }
        chunkRepository.loadVecExtensionAndInit(extPath);
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
        if (chunkRepository.isVec0Available()) return "vec0 table active";
        if (stateStore.isInstalled(PLUGIN_ID)) return "installed, restart to activate";
        return errorMsg != null ? errorMsg : "not installed";
    }

    @Override
    public boolean isReady() { return chunkRepository.isVec0Available(); }

    private void tryLoadIfInstalled() {
        if (!stateStore.isInstalled(PLUGIN_ID)) return;
        String os = detectOs();
        String ext = extForOs(os);
        Path libPath = Path.of(LIB_DIR, "vec0" + ext);
        if (!Files.exists(libPath)) return;
        try {
            String extPath = libPath.toAbsolutePath().toString();
            if (extPath.endsWith(ext)) {
                extPath = extPath.substring(0, extPath.length() - ext.length());
            }
            chunkRepository.loadVecExtensionAndInit(extPath);
        } catch (Exception e) {
            errorMsg = "Failed to load sqlite-vec: " + e.getMessage();
            log.warn("Failed to load sqlite-vec extension: {}", e.getMessage());
        }
    }

    private void extractTarGz(Path tarGzPath, Path destDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", tarGzPath.toString(), "-C", destDir.toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("tar extraction failed with exit code " + exitCode);
        }
    }

    private static String detectOs() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("linux")) return "linux";
        if (os.contains("win")) return "windows";
        return "unknown";
    }

    private static String detectArch() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) return "aarch64";
        return "x86_64";
    }

    private static String extForOs(String os) {
        return switch (os) {
            case "macos" -> ".dylib";
            case "linux" -> ".so";
            case "windows" -> ".dll";
            default -> ".so";
        };
    }

    private static String downloadUrl(String os, String arch) {
        String platform = os + "-" + arch;
        return "https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-loadable-" + platform + ".tar.gz";
    }
}
