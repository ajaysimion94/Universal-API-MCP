package com.mcpserver.plugins;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Legacy consumer-Copilot answer connector. It is deliberately opt-in because the
 * undocumented upstream session protocol requires credentials supplied through local
 * environment configuration and can change without notice.
 */
@Component
public class CopilotChatPlugin implements Plugin {

    public static final String ID = "copilot-chat";

    private final PluginStateStore stateStore;
    private final boolean credentialsConfigured;

    public CopilotChatPlugin(
            PluginStateStore stateStore,
            @Value("${chat.copilot.access-token:}") String accessToken,
            @Value("${chat.copilot.cookies:}") String cookies) {
        this.stateStore = stateStore;
        this.credentialsConfigured = !accessToken.isBlank() || !cookies.isBlank();
    }

    @Override
    public String id() { return ID; }

    @Override
    public String name() { return "Copilot Chat (legacy)"; }

    @Override
    public String description() {
        return "Optional Microsoft Copilot answer connector. Disabled by default; it only works when "
                + "credentials are supplied through local environment configuration."
                + " The consumer protocol is undocumented and may change.";
    }

    @Override
    public Category category() { return Category.OPTIONAL; }

    @Override
    public boolean builtIn() { return true; }

    @Override
    public Status status() {
        if (!isEnabled()) return Status.DISABLED;
        return credentialsConfigured ? Status.ACTIVE : Status.INSTALLED;
    }

    @Override
    public boolean isEnabled() { return stateStore.isEnabled(ID, false); }

    @Override
    public boolean isRunning() { return isReady(); }

    @Override
    public void install() { }

    @Override
    public void enable() { stateStore.setEnabled(ID, true); }

    @Override
    public void disable() { stateStore.setEnabled(ID, false); }

    @Override
    public void start() { }

    @Override
    public void stop() { }

    @Override
    public String health() {
        if (!isEnabled()) return "disabled";
        return credentialsConfigured
                ? "configured from environment"
                : "enabled, but no environment credentials are configured";
    }

    @Override
    public boolean isReady() { return isEnabled() && credentialsConfigured; }
}
