package com.mcpserver.plugins;

public interface Plugin {

    enum Category { REQUIRED, OPTIONAL }

    enum Status { NOT_INSTALLED, INSTALLING, INSTALLED, ACTIVE, ERROR, DISABLED }

    String id();
    String name();
    String description();
    Category category();
    /** Bundled inside the jar — nothing to install, no downloads. */
    default boolean builtIn() { return false; }
    Status status();
    boolean isEnabled();
    boolean isRunning();
    void install() throws Exception;
    void enable();
    void disable();
    void start() throws Exception;
    void stop() throws Exception;
    String health();
    boolean isReady();
}
