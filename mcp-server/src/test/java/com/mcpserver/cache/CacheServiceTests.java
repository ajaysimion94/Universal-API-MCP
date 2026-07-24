package com.mcpserver.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
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
        String key = CacheService.searchCacheKey("how to install", 10, false);
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
    void testStats() {
        Map<String, Object> stats = cacheService.stats();
        assertThat(stats).containsKey("searchResults");
        assertThat(stats).containsKey("toolResponses");
    }
}
