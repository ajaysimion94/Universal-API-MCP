package com.mcpserver.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class CacheService {
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
        String prefix = toolIdPrefix + ":";
        toolResponses.asMap().keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void invalidateSearchResults() {
        searchResults.invalidateAll();
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
        return toolId + ":" + digest(canonical(args == null ? Map.of() : args));
    }

    public static String searchCacheKey(String query, int topK, boolean web,
                                        boolean vectorReady, boolean webReady,
                                        List<String> aclTags) {
        return searchCacheKey(query, topK, web, vectorReady, webReady, aclTags, "baseline");
    }

    /**
     * @param armId the ranking arm that produced (or will produce) the response. Part of the key
     *              because a cached response from one fusion blend must never be served while the
     *              policy has selected another — the impression would then record an arm the user
     *              never actually saw, silently corrupting the off-policy evaluation data.
     */
    public static String searchCacheKey(String query, int topK, boolean web,
                                        boolean vectorReady, boolean webReady,
                                        List<String> aclTags, String armId) {
        Map<String, Object> attributes = Map.of(
                "query", query,
                "topK", topK,
                "web", web,
                "vectorReady", vectorReady,
                "webReady", webReady,
                "armId", armId == null ? "baseline" : armId,
                "aclTags", aclTags == null ? List.of()
                        : aclTags.stream().filter(Objects::nonNull).sorted().distinct().toList());
        return "search:" + digest(canonical(attributes));
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String canonical(Object value) {
        StringBuilder out = new StringBuilder();
        appendCanonical(out, value);
        return out.toString();
    }

    private static void appendCanonical(StringBuilder out, Object value) {
        if (value == null) {
            out.append('N');
        } else if (value instanceof Map<?, ?> map) {
            out.append("M").append(map.size()).append(':');
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> {
                        appendText(out, "K", String.valueOf(entry.getKey()));
                        appendCanonical(out, entry.getValue());
                    });
        } else if (value instanceof Collection<?> collection) {
            out.append("L").append(collection.size()).append(':');
            collection.forEach(item -> appendCanonical(out, item));
        } else if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            out.append("A").append(length).append(':');
            for (int i = 0; i < length; i++) appendCanonical(out, Array.get(value, i));
        } else if (value instanceof Number number) {
            String normalized;
            try {
                normalized = new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException exception) {
                normalized = number.toString();
            }
            appendText(out, "D", normalized);
        } else if (value instanceof Boolean bool) {
            out.append(bool ? "B1" : "B0");
        } else {
            appendText(out, "S", String.valueOf(value));
        }
    }

    private static void appendText(StringBuilder out, String type, String value) {
        out.append(type).append(value.length()).append(':').append(value);
    }
}
