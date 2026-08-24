package com.mcpserver.plugins;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginBootstrapTests {

    @Test
    void restoresInstalledEnabledOptionalPlugin() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.id()).thenReturn("searxng");
        when(plugin.builtIn()).thenReturn(false);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.isReady()).thenReturn(false);
        when(plugin.isRunning()).thenReturn(false);
        when(plugin.status()).thenReturn(Plugin.Status.ACTIVE);
        PluginStateStore state = mock(PluginStateStore.class);
        when(state.isInstalled("searxng")).thenReturn(true);
        PluginRegistry registry = mock(PluginRegistry.class);
        when(registry.getAll()).thenReturn(List.of(plugin));

        new PluginBootstrap(registry, state, true).restoreEnabledPlugins();

        verify(plugin).install();
        verify(plugin).start();
    }

    @Test
    void leavesUninstalledOptionalPluginAlone() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.id()).thenReturn("searxng");
        when(plugin.builtIn()).thenReturn(false);
        PluginStateStore state = mock(PluginStateStore.class);
        PluginRegistry registry = mock(PluginRegistry.class);
        when(registry.getAll()).thenReturn(List.of(plugin));

        new PluginBootstrap(registry, state, true).restoreEnabledPlugins();

        verify(plugin, never()).install();
        verify(plugin, never()).start();
    }

    @Test
    void disablingAutomaticStartupSkipsEveryPlugin() throws Exception {
        Plugin plugin = mock(Plugin.class);
        PluginStateStore state = mock(PluginStateStore.class);
        PluginRegistry registry = mock(PluginRegistry.class);
        when(registry.getAll()).thenReturn(List.of(plugin));

        new PluginBootstrap(registry, state, false).restoreEnabledPlugins();

        verify(plugin, never()).install();
        verify(plugin, never()).start();
    }
}
