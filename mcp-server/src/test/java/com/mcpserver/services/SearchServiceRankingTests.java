package com.mcpserver.services;

import com.mcpserver.cache.CacheService;
import com.mcpserver.models.Chunk;
import com.mcpserver.plugins.PluginRegistry;
import com.mcpserver.rag.reranker.Reranker;
import com.mcpserver.rag.retrieval.SearchPipeline;
import com.mcpserver.rag.web.WebFetcher;
import com.mcpserver.rag.web.WebSearchService;
import com.mcpserver.repositories.ChunkRepository;
import com.mcpserver.rag.embedding.EmbeddingClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchServiceRankingTests {

    private final EmbeddingClient embeddings = mock(EmbeddingClient.class);
    private final ChunkRepository repository = mock(ChunkRepository.class);
    private final Reranker reranker = mock(Reranker.class);
    private final WebSearchService webSearch = mock(WebSearchService.class);
    private final PluginRegistry plugins = mock(PluginRegistry.class);
    private final SearchService service = new SearchService(
            embeddings, repository, reranker, webSearch, plugins,
            new CacheService(60, 30), 40, 40, 60, 0.015f, 5);

    @Test
    void filenameFeatureChangesBothScoreAndOrdering() {
        Chunk unrelated = chunk("a", "misc.md", "deployment reference");
        Chunk titled = chunk("b", "deployment-guide.md", "deployment reference");
        when(repository.lexicalSearch(anyString(), anyInt())).thenReturn(List.of(unrelated, titled));
        when(reranker.rerank(anyString(), anyList())).thenReturn(List.of(
                new Reranker.ScoredChunk(unrelated, 0.40f),
                new Reranker.ScoredChunk(titled, 0.39f)));

        List<SearchPipeline.SearchResult> results =
                service.search("deployment guide", 2, List.of(), false);

        assertThat(results).extracting(SearchPipeline.SearchResult::sourceName)
                .containsExactly("deployment-guide.md", "misc.md");
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    void webAndLocalEvidenceAreSortedOnCalibratedScores() {
        Chunk local = chunk("local", "local.md", "migration reference");
        when(repository.lexicalSearch(anyString(), anyInt())).thenReturn(List.of(local));
        when(reranker.rerank(anyString(), anyList())).thenReturn(List.of(
                new Reranker.ScoredChunk(local, 0.50f)));
        when(plugins.isReady("searxng")).thenReturn(true);
        WebFetcher.WebResult web = new WebFetcher.WebResult(
                "https://docs.example.test/migration", "Official migration", "reference",
                "test", "migration", 1, 1d, null);
        when(webSearch.search(anyString(), anyList(), anyInt())).thenReturn(List.of(
                new WebSearchService.RankedWebResult(web, 0.90f, "Official reference")));

        List<SearchPipeline.SearchResult> results =
                service.search("migration", 3, List.of(), true);

        assertThat(results).extracting(SearchPipeline.SearchResult::sourceKind)
                .containsExactly("web", "local");
        assertThat(results).extracting(SearchPipeline.SearchResult::score)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void candidatesBelowTheRelevanceFloorAreNotReturned() {
        Chunk unrelated = chunk("unrelated", "cooking.md", "sourdough starter");
        when(repository.lexicalSearch(anyString(), anyInt())).thenReturn(List.of(unrelated));
        when(reranker.rerank(anyString(), anyList())).thenReturn(List.of(
                new Reranker.ScoredChunk(unrelated, 0.01f)));

        assertThat(service.search("enterprise access policy", 5, List.of(), false)).isEmpty();
    }

    private static Chunk chunk(String id, String name, String content) {
        return Chunk.create(id, name, "/" + name, content, null,
                List.of("public"), 0, 10);
    }
}
