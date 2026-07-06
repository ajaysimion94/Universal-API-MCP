package com.mcpserver.plugins;

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
import java.time.Duration;

@Component
public class SearXngPlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(SearXngPlugin.class);
    private static final String PLUGIN_ID = "searxng";
    private static final String INSTALL_DIR = "lib/searxng";
    /** Pinned SearXNG source snapshot bundled in the jar at build time (see pom.xml). */
    private static final String BUNDLED_SOURCE = "bundled/searxng/searxng-src.zip";

    private final PluginStateStore stateStore;
    private final String searxngUrl;
    private volatile Process process;
    private volatile String errorMsg;

    public SearXngPlugin(PluginStateStore stateStore,
                         @Value("${rag.web.searxng-url:http://127.0.0.1:8888}") String searxngUrl) {
        this.stateStore = stateStore;
        this.searxngUrl = searxngUrl;
    }

    @Override
    public String id() { return PLUGIN_ID; }

    @Override
    public String name() { return "SearXNG web search"; }

    @Override
    public String description() { return "Self-hosted meta-search engine for web augmentation. Native Python process on :8888. Optional — only needed for the Web toggle on search. Source ships in the jar; install requires Python 3.10+ and internet (pip dependencies)."; }

    @Override
    public Category category() { return Category.OPTIONAL; }

    @Override
    public Status status() {
        if (errorMsg != null && !stateStore.isInstalled(PLUGIN_ID)) return Status.ERROR;
        if (!stateStore.isEnabled(PLUGIN_ID)) return Status.DISABLED;
        if (process != null && process.isAlive()) return Status.ACTIVE;
        if (stateStore.isInstalled(PLUGIN_ID)) return Status.INSTALLED;
        return Status.NOT_INSTALLED;
    }

    @Override
    public boolean isEnabled() { return stateStore.isEnabled(PLUGIN_ID); }

    @Override
    public boolean isRunning() { return process != null && process.isAlive(); }

    @Override
    public void install() throws Exception {
        try {
            Path dir = Path.of(INSTALL_DIR);
            Files.createDirectories(dir);
            Path srcDir = dir.resolve("src");
            Path venvDir = dir.resolve("venv");

            if (!Files.exists(srcDir.resolve("searx"))) {
                log.info("Extracting bundled SearXNG source to {}", srcDir);
                extractBundledSource(srcDir);
            }

            if (!Files.exists(venvDir)) {
                String python = detectPython();
                log.info("Creating Python venv with {}", python);
                runCommand(python, "-m", "venv", venvDir.toString());
            }

            Path pip = venvBin("pip");
            if (!Files.exists(dir.resolve(".installed"))) {
                log.info("Installing SearXNG and its dependencies from source");
                runCommand(pip.toString(), "install", "--upgrade", "pip", "setuptools", "wheel");
                // requirements.txt must land first: setup.py imports the package at build
                // time (e.g. msgspec), which fails inside pip's isolated build env otherwise.
                runCommand(pip.toString(), "install", "-r", srcDir.resolve("requirements.txt").toString());
                runCommand(pip.toString(), "install", "--no-build-isolation", "-e", srcDir.toString());
                Files.writeString(dir.resolve(".installed"), "installed");
            }

            writeSettingsFile(dir);
            stateStore.setInstalled(PLUGIN_ID, true);
            errorMsg = null;
        } catch (Exception e) {
            errorMsg = e.getMessage();
            throw e;
        }
    }

    @Override
    public void enable() {
        stateStore.setEnabled(PLUGIN_ID, true);
    }

    @Override
    public void disable() {
        stateStore.setEnabled(PLUGIN_ID, false);
        if (process != null && process.isAlive()) {
            process.destroy();
            process = null;
        }
    }

    @Override
    public void start() throws Exception {
        if (!stateStore.isInstalled(PLUGIN_ID)) {
            throw new IllegalStateException("SearXNG not installed");
        }
        if (process != null && process.isAlive()) return;

        Path dir = Path.of(INSTALL_DIR);
        Path srcDir = dir.resolve("src");
        Path python = venvBin("python").toAbsolutePath();
        Path settingsFile = dir.resolve("settings.yml");

        ProcessBuilder pb = new ProcessBuilder(python.toString(), "-m", "searx.webapp");
        pb.environment().put("SEARXNG_SETTINGS_PATH", settingsFile.toAbsolutePath().toString());
        pb.directory(srcDir.toFile());
        pb.redirectErrorStream(true);
        process = pb.start();
        log.info("SearXNG process started (pid={})", process.pid());
    }

    @Override
    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                process.waitFor();
            } catch (InterruptedException ignored) {}
            process = null;
            log.info("SearXNG process stopped");
        }
    }

    @Override
    public String health() {
        if (!stateStore.isEnabled(PLUGIN_ID)) return "disabled";
        if (process != null && process.isAlive()) return "running on " + searxngUrl;
        if (stateStore.isInstalled(PLUGIN_ID)) return "installed, not running";
        return errorMsg != null ? errorMsg : "not installed";
    }

    @Override
    public boolean isReady() {
        return isEnabled() && process != null && process.isAlive();
    }

    /**
     * Extract the bundled SearXNG source zip (GitHub zipball) from the classpath —
     * pure-Java unzip, so target machines need no git/tar/curl. The zipball wraps
     * everything in a top-level {@code searxng-<ref>/} directory, which is stripped.
     */
    private void extractBundledSource(Path srcDir) throws IOException {
        var resource = new org.springframework.core.io.ClassPathResource(BUNDLED_SOURCE);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "SearXNG source not bundled in this build (jar built with -Dskip.bundle=true?)");
        }
        Path root = srcDir.toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (var zip = new java.util.zip.ZipInputStream(resource.getInputStream())) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                int slash = name.indexOf('/');
                if (slash < 0) continue; // top-level wrapper dir entry itself
                String relative = name.substring(slash + 1);
                if (relative.isEmpty()) continue;
                Path target = root.resolve(relative).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("Blocked zip entry escaping target dir: " + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (!Files.exists(srcDir.resolve("searx"))) {
            throw new IOException("Bundled SearXNG source extracted but searx/ package not found in " + srcDir);
        }
    }

    private String detectPython() {
        for (String cmd : new String[]{"python3", "python", "py"}) {
            try {
                Process p = new ProcessBuilder(cmd, "--version").start();
                if (p.waitFor() == 0) return cmd;
            } catch (Exception ignored) {}
        }
        throw new IllegalStateException("Python not found (install Python 3.10+ to use SearXNG)");
    }

    private void runCommand(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                log.debug("[searxng-install] {}", line);
            }
        }
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("Command failed with exit " + exit + ": " + String.join(" ", cmd));
        }
    }

    private Path venvBin(String name) {
        Path venvDir = Path.of(INSTALL_DIR, "venv");
        if (isWindows()) {
            return venvDir.resolve("Scripts").resolve(name + ".exe");
        }
        return venvDir.resolve("bin").resolve(name);
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private void writeSettingsFile(Path dir) throws IOException {
        String settings = """
                use_default_settings: true
                general:
                  instance_name: "MCP Local SearXNG"
                search:
                  formats: [html, json]
                  default_lang: "en"
                server:
                  secret_key: "mcp-local-searxng-secret"
                  bind_address: "127.0.0.1"
                  port: 8888
                  limiter: false
                redis:
                  url: false
                """;
        Files.writeString(dir.resolve("settings.yml"), settings);
    }
}
