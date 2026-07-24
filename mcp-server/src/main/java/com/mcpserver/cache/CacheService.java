package com.mcpserver.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

@Component
public class CacheService {
    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final Cache<String, Object> toolResponses;
    private final Cache<String, Object> searchResults;

    public CacheService(
            @Value("${cache.tool-response-ttl-seconds:60}") long toolResponseTtl,
            @Value("${cache.search-ttl-seconds:30}") long searchTtl) {
        
        this.toolResponses = Caffeine.newBuilder()
                .expireAfterWrite(toolResponseTtl, TimeUnit.SECONDS)
                .maximumSize(500)
                .recordStats()
                .build();
                
        this.searchResults = Caffeine.newBuilder()
                .expireAfterWrite(searchTtl, TimeUnit.SECONDS)
                .maximumSize(200)
                .recordStats()
                .build();
    }

    public Optional<Object> getToolResponse(String cacheKey) {
        return Optional.ofNullable(toolResponses.getIfPresent(cacheKey));
    }

    public void putToolResponse(String cacheKey, Object result) {
        toolResponses.put(cacheKey, result);
    }

    public Optional<Object> getSearchResult(String cacheKey) {
        return Optional.ofNullable(searchResults.getIfPresent(cacheKey));
    }

    public void putSearchResult(String cacheKey, Object result) {
        searchResults.put(cacheKey, result);
    }

    public void invalidateToolResponses(String toolIdPrefix) {
        toolResponses.asMap().keySet().removeIf(key -> key.startsWith(toolIdPrefix));
    }

    public void invalidateAll() {
        toolResponses.invalidateAll();
        searchResults.invalidateAll();
    }

    public Map<String, Object> stats() {
        return Map.of(
                "toolResponses", extractStats(toolResponses.stats(), toolResponses.estimatedSize()),
                "searchResults", extractStats(searchResults.stats(), searchResults.estimatedSize())
        );
    }
    
    private Map<String, Object> extractStats(CacheStats stats, long size) {
        return Map.of(
                "hitCount", stats.hitCount(),
                "missCount", stats.missCount(),
                "hitRate", stats.hitRate(),
                "evictionCount", stats.evictionCount(),
                "size", size
        );
    }

    public static String toolCacheKey(String toolId, Map<String, Object> args) {
        // Sort args to make the key deterministic
        Map<String, Object> sortedArgs = args == null ? Map.of() : new TreeMap<>(args);
        return toolId + ":" + sortedArgs.hashCode();
    }

    public static String searchCacheKey(String query, int topK, boolean web) {
        return query + ":" + topK + ":" + web;
    }
}
