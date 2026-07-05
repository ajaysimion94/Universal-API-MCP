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
    public String description() { return "Self-hosted meta-search engine for web augmentation. Native Python process on :8888. Optional — only needed for the Web toggle on search."; }

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
        Path dir = Path.of(INSTALL_DIR);
        Files.createDirectories(dir);
        Path venvDir = dir.resolve("venv");

        if (!Files.exists(venvDir)) {
            String python = detectPython();
            log.info("Creating Python venv with {}", python);
            runCommand(python, "-m", "venv", venvDir.toString());
        }

        Path pip = venvBin("pip");
        if (!Files.exists(dir.resolve(".installed"))) {
            log.info("Installing searxng in venv");
            runCommand(pip.toString(), "install", "searxng");
            Files.writeString(dir.resolve(".installed"), "installed");
        }

        writeSettingsFile(dir);
        stateStore.setInstalled(PLUGIN_ID, true);
        errorMsg = null;
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
        Path searxngRun = venvBin(isWindows() ? "python" : "searxng-run");
        Path settingsFile = dir.resolve("settings.yml");

        ProcessBuilder pb = new ProcessBuilder(searxngRun.toString());
        pb.environment().put("SEARXNG_SETTINGS_PATH", settingsFile.toAbsolutePath().toString());
        pb.directory(dir.toFile());
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
