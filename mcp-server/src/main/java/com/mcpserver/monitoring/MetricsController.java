package com.mcpserver.monitoring;

import com.mcpserver.cache.CacheService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {
    private static final Logger log = LoggerFactory.getLogger(MetricsController.class);

    private final MeterRegistry registry;
    private final CacheService cacheService;

    public MetricsController(MeterRegistry registry, CacheService cacheService) {
        this.registry = registry;
        this.cacheService = cacheService;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> summary = new HashMap<>();
        
        summary.put("totalMcpRequests", getCounterValue("mcp.requests.total"));
        summary.put("totalToolExecutions", getTimerCount("tool.execution.time"));
        summary.put("totalErrors", getCounterValue("tool.errors.total"));
        summary.put("totalSearches", getTimerCount("search.latency"));
        summary.put("connectorOperations", getCounterValue("connector.operation.total"));
        summary.put("cache", cacheService.stats());
        
        return summary;
    }
    
    private double getCounterValue(String name) {
        return registry.find(name).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }
    
    private long getTimerCount(String name) {
        return registry.find(name).timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count)
                .sum();
    }
}
