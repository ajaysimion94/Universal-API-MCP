package com.mcpserver.plugins;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginRegistryTests {

    private PluginRegistry registry;

    @AfterEach
    void closeRegistry() {
        if (registry != null) registry.shutdown();
    }

    @Test
    void setupInstallsEnablesAndStartsPluginInOneJob() throws Exception {
        Plugin plugin = mockPlugin("searxng");
        PluginStateStore state = mock(PluginStateStore.class);
        when(state.isInstalled("searxng")).thenReturn(false);
        when(plugin.isEnabled()).thenReturn(false);
        when(plugin.isRunning()).thenReturn(false);
        registry = new PluginRegistry(List.of(plugin), state);

        String jobId = registry.startSetup("searxng");
        PluginRegistry.InstallJob job = awaitFinished(jobId);

        assertThat(job.status).isEqualTo("completed");
        InOrder order = inOrder(plugin, state);
        order.verify(plugin).install();
        order.verify(state).setInstalled("searxng", true);
        order.verify(plugin).enable();
        order.verify(plugin).start();
    }

    @Test
    void repeatedSetupClicksReuseTheActiveJob() throws Exception {
        Plugin plugin = mockPlugin("searxng");
        PluginStateStore state = mock(PluginStateStore.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await();
            return null;
        }).when(plugin).install();
        registry = new PluginRegistry(List.of(plugin), state);

        String first = registry.startSetup("searxng");
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        String second = registry.startSetup("searxng");
        release.countDown();

        assertThat(second).isEqualTo(first);
        assertThat(awaitFinished(first).status).isEqualTo("completed");
        verify(plugin).install();
    }

    private Plugin mockPlugin(String id) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.id()).thenReturn(id);
        when(plugin.name()).thenReturn(id);
        when(plugin.builtIn()).thenReturn(false);
        when(plugin.isReady()).thenReturn(false);
        return plugin;
    }

    private PluginRegistry.InstallJob awaitFinished(String jobId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        PluginRegistry.InstallJob job;
        do {
            job = registry.getJob(jobId);
            if (job != null && !"running".equals(job.status)) return job;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        return registry.getJob(jobId);
    }
}
