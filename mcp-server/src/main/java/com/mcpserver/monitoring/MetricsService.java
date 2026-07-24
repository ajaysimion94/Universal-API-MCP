package com.mcpserver.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MetricsService {
    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);
    
    private final MeterRegistry registry;
    
    public MetricsService(MeterRegistry registry) {
        this.registry = registry;
        
        // Register metrics on startup so they appear even before being incremented
        registry.counter("mcp.requests.total", "type", "unknown");
        registry.timer("tool.execution.time", "tool", "unknown");
        registry.timer("search.latency");
        registry.counter("tool.errors.total", "tool", "unknown");
        registry.counter("workflow.state.transitions", "tool", "unknown", "state", "unknown");
    }
    
    public void recordMcpRequest(String type) {
        registry.counter("mcp.requests.total", "type", type).increment();
    }
    
    public Timer.Sample startToolTimer() {
        return Timer.start(registry);
    }
    
    public void stopToolTimer(Timer.Sample sample, String toolName) {
        sample.stop(registry.timer("tool.execution.time", "tool", toolName));
    }
    
    public Timer.Sample startSearchTimer() {
        return Timer.start(registry);
    }
    
    public void stopSearchTimer(Timer.Sample sample) {
        sample.stop(registry.timer("search.latency"));
    }
    
    public void recordToolError(String toolName) {
        registry.counter("tool.errors.total", "tool", toolName).increment();
    }
    
    public void recordWorkflowTransition(String toolName, String state) {
        registry.counter("workflow.state.transitions", "tool", toolName, "state", state).increment();
    }
}
