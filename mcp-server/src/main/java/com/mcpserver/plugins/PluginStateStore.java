package com.mcpserver.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Component
public class PluginStateStore {

    private static final Logger log = LoggerFactory.getLogger(PluginStateStore.class);
    private static final String STATE_FILE = "plugins-state.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, PluginState> states = new HashMap<>();

    public PluginStateStore() {
        load();
    }

    public synchronized boolean isEnabled(String pluginId) {
        PluginState state = states.get(pluginId);
        return state == null || state.enabled;
    }

    public synchronized boolean isInstalled(String pluginId) {
        PluginState state = states.get(pluginId);
        return state != null && state.installed;
    }

    public synchronized void setEnabled(String pluginId, boolean enabled) {
        PluginState state = states.computeIfAbsent(pluginId, k -> new PluginState());
        state.enabled = enabled;
        save();
    }

    public synchronized void setInstalled(String pluginId, boolean installed) {
        PluginState state = states.computeIfAbsent(pluginId, k -> new PluginState());
        state.installed = installed;
        save();
    }

    private void load() {
        File file = new File(STATE_FILE);
        if (!file.exists()) return;
        try {
            Map<String, PluginState> loaded = mapper.readValue(file,
                    mapper.getTypeFactory().constructMapType(HashMap.class, String.class, PluginState.class));
            if (loaded != null) states = loaded;
        } catch (IOException e) {
            log.warn("Failed to load plugin state from {}: {}", STATE_FILE, e.getMessage());
        }
    }

    private void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(STATE_FILE), states);
        } catch (IOException e) {
            log.warn("Failed to save plugin state to {}: {}", STATE_FILE, e.getMessage());
        }
    }

    public static class PluginState {
        public boolean installed = false;
        public boolean enabled = true;
    }
}
