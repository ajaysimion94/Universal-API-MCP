package com.mcpserver.services;

import com.mcpserver.cache.CacheService;
import com.mcpserver.learning.FeedbackMemory;
import com.mcpserver.learning.LearningService;
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
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchServiceRankingTests {

    private final EmbeddingClient embeddings = mock(EmbeddingClient.class);
    private final ChunkRepository repository = mock(ChunkRepository.class);
    private final Reranker reranker = mock(Reranker.class);
    private final WebSearchService webSearch = mock(WebSearchService.class);
    private final PluginRegistry plugins = mock(PluginRegistry.class);
    /** Learning disabled: these tests assert the base ranking contract, unperturbed by feedback. */
    private final SearchService service = new SearchService(
            embeddings, repository, reranker, webSearch, plugins,
            new CacheService(60, 30), new LearningService(null, null, null, false), 40, 40, 60, 0.015f, 5);

    /**
     * The load-bearing safety property of the feedback memory: the learned delta is applied after
     * the relevance filter, so a strong demotion can only sink a result — never remove it. If a
     * thumbs-down could delete a result, the user would have no way to find it again and undo.
     */
    @Test
    void aStronglyDemotedResultSinksButIsNeverFilteredOut() {
        LearningService learning = mock(LearningService.class);
        SearchService learningService = new SearchService(
                embeddings, repository, reranker, webSearch, plugins,
                new CacheService(60, 30), learning, 40, 40, 60, 0.015f, 5);

        Chunk demoted = chunk("demoted", "demoted.md", "migration reference");
        Chunk neutral = chunk("neutral", "neutral.md", "migration reference");
        when(repository.lexicalSearch(anyString(), anyInt())).thenReturn(List.of(demoted, neutral));
        when(reranker.rerank(anyString(), anyList())).thenReturn(List.of(
                new Reranker.ScoredChunk(demoted, 0.60f),
                new Reranker.ScoredChunk(neutral, 0.50f)));
        when(learning.decide(anyString()))
                .thenReturn(LearningService.RankingDecision.of("migration"));
        // Far larger than the configured max-demote, to prove the guarantee is structural rather
        // than a consequence of the delta happening to be small.
        when(learning.memoryAdjustments(any(), any())).thenReturn(Map.of(
                demoted.id(), new FeedbackMemory.Adjustment(demoted.id(), -0.95f, -1f)));

        List<SearchPipeline.SearchResult> results =
                learningService.search("migration", 5, List.of(), false);

        assertThat(results).extracting(result -> result.chunk().sourceName())
                .containsExactly("neutral.md", "demoted.md");
        assertThat(results.get(1).score()).isGreaterThanOrEqualTo(0f);
    }

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
        when(webSearch.plannedQueries(anyString(), anyList()))
                .thenReturn(List.of("migration local.md official documentation"));

        SearchPipeline.SearchResponse response =
                service.searchWithMetadata("migration", 3, List.of(), true);
        List<SearchPipeline.SearchResult> results = response.results();

        assertThat(results).extracting(SearchPipeline.SearchResult::sourceKind)
                .containsExactly("web", "local");
        assertThat(results).extracting(SearchPipeline.SearchResult::score)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(response.webQueries())
                .containsExactly("migration local.md official documentation");
        verify(webSearch).search(eq("migration"),
                argThat(context -> !context.isEmpty() && context.get(0).contains("local.md")),
                eq(3));
        verify(webSearch).plannedQueries(eq("migration"),
                argThat(context -> !context.isEmpty() && context.get(0).contains("local.md")));
    }

    @Test
    void candidatesBelowTheRelevanceFloorAreNotReturned() {
        Chunk unrelated = chunk("unrelated", "cooking.md", "sourdough starter");
        when(repository.lexicalSearch(anyString(), anyInt())).thenReturn(List.of(unrelated));
        when(reranker.rerank(anyString(), anyList())).thenReturn(List.of(
                new Reranker.ScoredChunk(unrelated, 0.01f)));

        assertThat(service.search("enterprise access policy", 5, List.of(), false)).isEmpty();
    }

    @Test
    void nonPositiveResultLimitReturnsWithoutRunningRetrieval() {
        assertThat(service.search("policy", 0, List.of(), true)).isEmpty();
        assertThat(service.search("policy", -10, List.of(), false)).isEmpty();

        verifyNoInteractions(repository, reranker, webSearch);
    }

    @Test
    void resultLimitIsClampedAndAppliesToCombinedResults() {
        List<Chunk> chunks = IntStream.range(0, 120)
                .mapToObj(index -> chunk("id-" + index, "doc-" + index + ".md", "policy reference"))
                .toList();
        when(repository.lexicalSearch(anyString(), anyInt())).thenReturn(chunks);
        when(reranker.rerank(anyString(), anyList())).thenAnswer(invocation ->
                invocation.<List<Chunk>>getArgument(1).stream()
                        .map(chunk -> new Reranker.ScoredChunk(chunk, 0.5f))
                        .toList());

        assertThat(service.search("policy", Integer.MAX_VALUE, List.of(), false)).hasSize(100);
    }

    @Test
    void pluginReadinessChangeDoesNotReuseDegradedWebCacheEntry() {
        Chunk local = chunk("local", "local.md", "migration reference");
        when(repository.lexicalSearch(anyString(), anyInt())).thenReturn(List.of(local));
        when(reranker.rerank(anyString(), anyList())).thenReturn(List.of(
                new Reranker.ScoredChunk(local, 0.50f)));
        WebFetcher.WebResult web = new WebFetcher.WebResult(
                "https://docs.example.test/migration", "Official migration", "reference",
                "test", "migration", 1, 1d, null);
        when(webSearch.search(anyString(), anyList(), anyInt())).thenReturn(List.of(
                new WebSearchService.RankedWebResult(web, 0.90f, "Official reference")));

        when(plugins.isReady("searxng")).thenReturn(false, true);

        assertThat(service.search("migration", 3, List.of(), true))
                .extracting(SearchPipeline.SearchResult::sourceKind).containsExactly("local");
        assertThat(service.search("migration", 3, List.of(), true))
                .extracting(SearchPipeline.SearchResult::sourceKind).containsExactly("web", "local");
    }

    private static Chunk chunk(String id, String name, String content) {
        return Chunk.create(id, name, "/" + name, content, null,
                List.of("public"), 0, 10);
    }
}
