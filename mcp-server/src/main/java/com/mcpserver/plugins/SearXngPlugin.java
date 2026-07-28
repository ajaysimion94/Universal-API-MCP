package com.mcpserver.plugins;

import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.TimeUnit;

@Component
public class SearXngPlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(SearXngPlugin.class);
    private static final String PLUGIN_ID = "searxng";
    private static final String INSTALL_DIR = "lib/searxng";
    private static final String PID_FILE = "searxng.pid";
    /** Pinned SearXNG source snapshot bundled in the jar at build time (see pom.xml). */
    private static final String BUNDLED_SOURCE = "bundled/searxng/searxng-src.zip";

    private final PluginStateStore stateStore;
    private final String searxngUrl;
    private final HttpClient healthClient;
    private volatile Process process;
    private volatile ProcessHandle managedHandle;
    private volatile String errorMsg;
    private volatile boolean lastHealth;
    private volatile long lastHealthCheckNanos;

    public SearXngPlugin(PluginStateStore stateStore,
                         @Value("${rag.web.searxng-url:http://127.0.0.1:8888}") String searxngUrl) {
        this.stateStore = stateStore;
        this.searxngUrl = searxngUrl;
        this.healthClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        adoptManagedProcess();
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
        if (isReady()) return Status.ACTIVE;
        if (stateStore.isInstalled(PLUGIN_ID)) return Status.INSTALLED;
        return Status.NOT_INSTALLED;
    }

    @Override
    public boolean isEnabled() { return stateStore.isEnabled(PLUGIN_ID); }

    @Override
    public boolean isRunning() { return endpointHealthy(); }

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
        stop();
    }

    @Override
    public synchronized void start() throws Exception {
        if (!stateStore.isInstalled(PLUGIN_ID)) {
            throw new IllegalStateException("SearXNG not installed");
        }
        if (endpointHealthy(true)) {
            log.info("SearXNG is already healthy on {}; reusing it", searxngUrl);
            return;
        }
        adoptManagedProcess();
        if (managedProcessAlive()) {
            waitUntilHealthy();
            return;
        }

        Path dir = Path.of(INSTALL_DIR);
        Path srcDir = dir.resolve("src");
        Path python = venvBin("python").toAbsolutePath();
        Path settingsFile = dir.resolve("settings.yml");

        ProcessBuilder pb = new ProcessBuilder(python.toString(), "-m", "searx.webapp");
        pb.environment().put("SEARXNG_SETTINGS_PATH", settingsFile.toAbsolutePath().toString());
        pb.directory(srcDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("searxng.log").toFile()));
        process = pb.start();
        managedHandle = process.toHandle();
        Files.writeString(dir.resolve(PID_FILE), Long.toString(process.pid()),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        invalidateHealth();
        log.info("SearXNG process started (pid={})", process.pid());
        try {
            waitUntilHealthy();
            errorMsg = null;
        } catch (Exception e) {
            errorMsg = e.getMessage();
            stop();
            throw e;
        }
    }

    @Override
    public synchronized void stop() {
        ProcessHandle handle = managedHandle;
        if (handle == null && process != null) handle = process.toHandle();
        if (handle != null && handle.isAlive() && isSearXngProcess(handle)) {
            handle.descendants().forEach(SearXngPlugin::destroyQuietly);
            handle.destroy();
            try {
                handle.onExit().get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                handle.descendants().forEach(SearXngPlugin::destroyForciblyQuietly);
                handle.destroyForcibly();
            }
            log.info("Managed SearXNG process stopped (pid={})", handle.pid());
        }
        process = null;
        managedHandle = null;
        deletePidFile();
        invalidateHealth();
    }

    @Override
    public String health() {
        if (!stateStore.isEnabled(PLUGIN_ID)) return "disabled";
        if (endpointHealthy()) {
            return managedProcessAlive()
                    ? "healthy on " + searxngUrl + " (managed)"
                    : "healthy on " + searxngUrl + " (external process)";
        }
        if (managedProcessAlive()) return "process running, health check pending on " + searxngUrl;
        if (stateStore.isInstalled(PLUGIN_ID)) return "installed, not running";
        return errorMsg != null ? errorMsg : "not installed";
    }

    @Override
    public boolean isReady() {
        return isEnabled() && endpointHealthy();
    }

    private void waitUntilHealthy() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (endpointHealthy(true)) return;
            if (!managedProcessAlive()) {
                throw new IOException("SearXNG exited before its HTTP endpoint became ready");
            }
            Thread.sleep(200);
        }
        throw new IOException("SearXNG did not become healthy on " + searxngUrl + " within 20 seconds");
    }

    private boolean endpointHealthy() {
        return endpointHealthy(false);
    }

    private boolean endpointHealthy(boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastHealthCheckNanos < Duration.ofSeconds(1).toNanos()) {
            return lastHealth;
        }
        boolean healthy = false;
        try {
            URI base = URI.create(searxngUrl.endsWith("/") ? searxngUrl : searxngUrl + "/");
            HttpRequest request = HttpRequest.newBuilder(base)
                    .header("Accept", "text/html, application/json;q=0.5")
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            int status = healthClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            healthy = status >= 200 && status < 400;
        } catch (Exception ignored) {
            healthy = false;
        }
        lastHealth = healthy;
        lastHealthCheckNanos = now;
        return healthy;
    }

    private void invalidateHealth() {
        lastHealth = false;
        lastHealthCheckNanos = 0;
    }

    private boolean managedProcessAlive() {
        if (process != null && process.isAlive()) return true;
        return managedHandle != null && managedHandle.isAlive() && isSearXngProcess(managedHandle);
    }

    private void adoptManagedProcess() {
        Path pidFile = Path.of(INSTALL_DIR, PID_FILE);
        if (!Files.exists(pidFile)) return;
        try {
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                    .filter(SearXngPlugin::isSearXngProcess)
                    .ifPresent(handle -> {
                        managedHandle = handle;
                        log.info("Adopted managed SearXNG process from pid file (pid={})", pid);
                    });
            if (managedHandle == null) Files.deleteIfExists(pidFile);
        } catch (Exception e) {
            try { Files.deleteIfExists(pidFile); } catch (IOException ignored) {}
        }
    }

    private static boolean isSearXngProcess(ProcessHandle handle) {
        String commandLine = handle.info().commandLine().orElse("").toLowerCase();
        String[] arguments = handle.info().arguments().orElse(new String[0]);
        String joinedArgs = String.join(" ", arguments).toLowerCase();
        return commandLine.contains("searx.webapp") || joinedArgs.contains("searx.webapp");
    }

    private static void destroyQuietly(ProcessHandle handle) {
        try { handle.destroy(); } catch (Exception ignored) {}
    }

    private static void destroyForciblyQuietly(ProcessHandle handle) {
        try { handle.destroyForcibly(); } catch (Exception ignored) {}
    }

    private void deletePidFile() {
        try {
            Files.deleteIfExists(Path.of(INSTALL_DIR, PID_FILE));
        } catch (IOException e) {
            log.debug("Could not remove stale SearXNG pid file: {}", e.getMessage());
        }
    }

    @PreDestroy
    void close() {
        stop();
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
