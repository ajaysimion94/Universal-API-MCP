package com.mcpserver.plugins;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);

    private final List<Plugin> plugins;
    private final PluginStateStore stateStore;
    private final ExecutorService installExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "plugin-install-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, InstallJob> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger jobCounter = new AtomicInteger(0);

    public PluginRegistry(List<Plugin> plugins, PluginStateStore stateStore) {
        this.plugins = plugins;
        this.stateStore = stateStore;
    }

    public List<Plugin> getAll() {
        return plugins;
    }

    public Optional<Plugin> findById(String id) {
        return plugins.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public boolean isReady(String pluginId) {
        return findById(pluginId).map(Plugin::isReady).orElse(false);
    }

    public String startInstall(String pluginId) {
        Plugin target = findById(pluginId)
                .orElseThrow(() -> new IllegalArgumentException("Plugin not found: " + pluginId));
        if (target.builtIn()) {
            throw new IllegalArgumentException(
                    target.name() + " is built into the app — there is nothing to install");
        }
        String jobId = "job-" + jobCounter.incrementAndGet();
        InstallJob job = new InstallJob(pluginId, "running");
        jobs.put(jobId, job);
        installExecutor.submit(() -> {
            try {
                Plugin plugin = findById(pluginId)
                        .orElseThrow(() -> new IllegalArgumentException("Plugin not found: " + pluginId));
                plugin.install();
                stateStore.setInstalled(pluginId, true);
                job.status = "completed";
                log.info("Plugin {} installed successfully", pluginId);
            } catch (Exception e) {
                job.status = "failed";
                job.error = e.getMessage();
                log.error("Plugin {} install failed: {}", pluginId, e.getMessage());
            }
        });
        return jobId;
    }

    public InstallJob getJob(String jobId) {
        return jobs.get(jobId);
    }

    @PreDestroy
    void shutdown() {
        installExecutor.shutdown();
        try {
            installExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public static class InstallJob {
        public final String pluginId;
        public volatile String status;
        public volatile String error;

        public InstallJob(String pluginId, String status) {
            this.pluginId = pluginId;
            this.status = status;
        }
    }
}
