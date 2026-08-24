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
    private final Map<String, String> activeJobs = new ConcurrentHashMap<>();
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
        return submitJob(target, false);
    }

    /**
     * Runs the complete first-use lifecycle in one background job: install when needed,
     * enable, and start. Repeated clicks return the already-running job for that plugin.
     */
    public String startSetup(String pluginId) {
        Plugin target = findById(pluginId)
                .orElseThrow(() -> new IllegalArgumentException("Plugin not found: " + pluginId));
        return submitJob(target, true);
    }

    private synchronized String submitJob(Plugin target, boolean setup) {
        String existing = activeJobs.get(target.id());
        if (existing != null) return existing;

        String jobId = "job-" + jobCounter.incrementAndGet();
        InstallJob job = new InstallJob(target.id(), "running");
        jobs.put(jobId, job);
        activeJobs.put(target.id(), jobId);
        installExecutor.submit(() -> {
            try {
                boolean installed = target.builtIn() || stateStore.isInstalled(target.id());
                if (!installed || !setup) {
                    target.install();
                    stateStore.setInstalled(target.id(), true);
                } else if (!target.isReady()) {
                    // Built-ins and partially initialized plugins use install() as a safe retry hook.
                    target.install();
                }
                if (setup) {
                    if (!target.isEnabled()) target.enable();
                    if (!target.isRunning()) target.start();
                }
                job.status = "completed";
                log.info("Plugin {} {} successfully", target.id(), setup ? "set up" : "installed");
            } catch (Exception e) {
                job.status = "failed";
                job.error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.error("Plugin {} {} failed", target.id(), setup ? "setup" : "install", e);
            } finally {
                activeJobs.remove(target.id(), jobId);
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
