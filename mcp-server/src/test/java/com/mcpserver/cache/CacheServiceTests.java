package com.mcpserver.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class CacheServiceTests {

    @Autowired
    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService.invalidateAll();
    }

    @Test
    void testSearchCacheHitMiss() {
        String key = CacheService.searchCacheKey(
                "how to install", 10, false, true, false, List.of("public"));
        Optional<Object> cached = cacheService.getSearchResult(key);
        assertThat(cached).isEmpty();

        List<String> mockResults = List.of("result 1", "result 2");
        cacheService.putSearchResult(key, mockResults);

        cached = cacheService.getSearchResult(key);
        assertThat(cached).isPresent().contains(mockResults);
    }

    @Test
    void testToolCacheInvalidation() {
        String toolId = "jira_create_issue";
        String key = CacheService.toolCacheKey(toolId, Map.of("title", "Bug"));

        cacheService.putToolResponse(key, "created");
        assertThat(cacheService.getToolResponse(key)).isPresent().contains("created");

        // Invalidate by prefix
        cacheService.invalidateToolResponses(toolId);
        assertThat(cacheService.getToolResponse(key)).isEmpty();
    }

    @Test
    void toolCacheKeysDoNotUseCollidingMapHashCodes() {
        assertThat(Map.of("Aa", 1).hashCode()).isEqualTo(Map.of("BB", 1).hashCode());

        assertThat(CacheService.toolCacheKey("tool", Map.of("Aa", 1)))
                .isNotEqualTo(CacheService.toolCacheKey("tool", Map.of("BB", 1)));
    }

    @Test
    void toolCacheKeyIsStableAcrossNestedMapInsertionOrder() {
        Map<String, Object> firstNested = new LinkedHashMap<>();
        firstNested.put("status", "open");
        firstNested.put("page", 2);
        Map<String, Object> secondNested = new LinkedHashMap<>();
        secondNested.put("page", 2);
        secondNested.put("status", "open");

        assertThat(CacheService.toolCacheKey("tool", Map.of("filter", firstNested)))
                .isEqualTo(CacheService.toolCacheKey("tool", Map.of("filter", secondNested)));
    }

    @Test
    void searchCacheSeparatesReadinessAndAclScopes() {
        String base = CacheService.searchCacheKey(
                "policy", 10, true, false, false, List.of("public"));

        assertThat(CacheService.searchCacheKey(
                "policy", 10, true, true, true, List.of("public"))).isNotEqualTo(base);
        assertThat(CacheService.searchCacheKey(
                "policy", 10, true, false, false, List.of("private"))).isNotEqualTo(base);
    }

    @Test
    void testStats() {
        Map<String, Object> stats = cacheService.stats();
        assertThat(stats).containsKey("searchResults");
        assertThat(stats).containsKey("toolResponses");
    }
}
