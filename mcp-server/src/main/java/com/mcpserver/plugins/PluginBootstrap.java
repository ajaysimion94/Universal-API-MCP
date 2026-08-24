package com.mcpserver.plugins;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Restores plugin runtime state after the application has finished booting. */
@Component
public class PluginBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PluginBootstrap.class);

    private final PluginRegistry registry;
    private final PluginStateStore stateStore;
    private final boolean autoStartEnabled;

    public PluginBootstrap(PluginRegistry registry,
                           PluginStateStore stateStore,
                           @Value("${plugins.auto-start-enabled:true}") boolean autoStartEnabled) {
        this.registry = registry;
        this.stateStore = stateStore;
        this.autoStartEnabled = autoStartEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreEnabledPlugins() {
        if (!autoStartEnabled) {
            log.info("Automatic plugin startup is disabled");
            return;
        }

        for (Plugin plugin : registry.getAll()) {
            boolean installed = plugin.builtIn() || stateStore.isInstalled(plugin.id());
            if (!installed || !plugin.isEnabled()) continue;
            try {
                if (!plugin.isReady()) plugin.install();
                if (!plugin.isRunning()) plugin.start();
                log.info("Restored enabled plugin {} ({})", plugin.id(), plugin.status());
            } catch (Exception exception) {
                // A failed optional plugin must never prevent the main application from starting.
                log.error("Automatic startup failed for plugin {}", plugin.id(), exception);
            }
        }
    }
}
