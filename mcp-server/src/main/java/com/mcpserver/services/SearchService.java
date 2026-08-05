package com.mcpserver.services;

import com.mcpserver.cache.CacheService;
import com.mcpserver.connectors.ConnectorContentResolver;
import com.mcpserver.learning.FeedbackMemory;
import com.mcpserver.learning.LearningModel;
import com.mcpserver.learning.LearningService;
import com.mcpserver.models.Chunk;
import com.mcpserver.plugins.PluginRegistry;
import com.mcpserver.rag.embedding.EmbeddingClient;
import com.mcpserver.rag.reranker.Reranker;
import com.mcpserver.rag.retrieval.RrfFusion;
import com.mcpserver.rag.retrieval.SearchPipeline;
import com.mcpserver.rag.retrieval.TextSignals;
import com.mcpserver.rag.web.WebSearchService;
import com.mcpserver.repositories.ChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class SearchService implements SearchPipeline {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final int MAX_RESULTS = 100;

    private final EmbeddingClient embeddingClient;
    private final ChunkRepository chunkRepository;
    private final Reranker reranker;
    private final RrfFusion rrf;
    private final WebSearchService webSearchService;
    private final PluginRegistry pluginRegistry;
    private final CacheService cacheService;
    private final LearningService learningService;
    private final int vectorTopK;
    private final int lexicalTopK;
    private final int webResultCount;
    private final float minimumRelevanceScore;
    private ConnectorContentResolver connectorContentResolver;

    public SearchService(EmbeddingClient embeddingClient,
                         ChunkRepository chunkRepository,
                         Reranker reranker,
                         WebSearchService webSearchService,
                         PluginRegistry pluginRegistry,
                         CacheService cacheService,
                         LearningService learningService,
                         @Value("${rag.search.vector-top-k}") int vectorTopK,
                         @Value("${rag.search.lexical-top-k}") int lexicalTopK,
                         @Value("${rag.search.rrf-k}") int rrfK,
                         @Value("${rag.search.min-relevance-score:0.015}") float minimumRelevanceScore,
                         @Value("${rag.web.page-count:5}") int webResultCount) {
        this.embeddingClient = embeddingClient;
        this.chunkRepository = chunkRepository;
        this.reranker = reranker;
        this.rrf = new RrfFusion(rrfK);
        this.webSearchService = webSearchService;
        this.pluginRegistry = pluginRegistry;
        this.cacheService = cacheService;
        this.learningService = learningService;
        this.vectorTopK = vectorTopK;
        this.lexicalTopK = lexicalTopK;
        this.minimumRelevanceScore = minimumRelevanceScore;
        this.webResultCount = webResultCount;
    }

    @Autowired(required = false)
    void setConnectorContentResolver(ConnectorContentResolver connectorContentResolver) {
        this.connectorContentResolver = connectorContentResolver;
    }

    @Override
    public List<SearchResult> search(String query, int topN, List<String> userAclTags) {
        return search(query, topN, userAclTags, false);
    }

    /**
     * The overload MCP clients reach. Its impressions are logged for the latency guard but tagged
     * {@code mcp} — that surface has no way to send feedback, so those rows never train anything.
     */
    @Override
    public List<SearchResult> search(String query, int topN, List<String> userAclTags, boolean includeWeb) {
        return execute(query, topN, userAclTags, includeWeb, LearningService.SURFACE_MCP).results();
    }

    @Override
    public SearchResponse searchWithMetadata(
            String query, int topN, List<String> userAclTags, boolean includeWeb) {
        return execute(query, topN, userAclTags, includeWeb, LearningService.SURFACE_WEB);
    }

    private SearchResponse execute(
            String query, int topN, List<String> userAclTags, boolean includeWeb, String surface) {
        if (query == null || query.isBlank() || topN <= 0) {
            return new SearchResponse(List.of(), List.of());
        }
        int safeTopN = Math.min(topN, MAX_RESULTS);
        long startedAt = System.nanoTime();
        LearningService.RankingDecision decision = learningService.decide(query);

        // Resolve metadata-only connector entries before consulting the result cache. Hydration
        // invalidates the cache through IngestionService; checking the cache first could preserve a
        // stale "no results" response after the matching remote page was fetched.
        if (connectorContentResolver != null) {
            int hydrated = connectorContentResolver.hydrateTitleMatches(query);
            if (hydrated > 0) {
                log.info("Lazy connector hydration fetched {} title-matched item(s) for '{}'", hydrated, query);
            }
        }

        // Lexical FTS5 search always works (built into SQLite); the vector leg needs
        // both the embedding model and the sqlite-vec store. Degrade gracefully.
        boolean vectorLegReady = embeddingClient.isReady() && chunkRepository.isVec0Available();
        boolean webLegReady = includeWeb && pluginRegistry.isReady("searxng");

        // Readiness and ACL scope are part of the key: otherwise a degraded result can survive
        // a plugin becoming ready, or a future ACL-aware caller can receive another scope's rows.
        String cacheKey = CacheService.searchCacheKey(
                query, safeTopN, includeWeb, vectorLegReady, webLegReady, userAclTags, decision.armId());
        Optional<Object> cached = cacheService.getSearchResult(cacheKey);
        if (cached.orElse(null) instanceof SearchResponse response) {
            log.debug("Search cache hit for key: {}", cacheKey);
            // A cache hit still logs an impression. Skipping it would make repeat queries inside the
            // TTL invisible, and repeat queries are exactly what the feedback memory learns from.
            String impressionId = learningService.recordImpression(
                    decision, surface, safeTopN, includeWeb, !vectorLegReady, true,
                    servedResults(response.results()), response.learnedAdjustments(),
                    elapsedMs(startedAt));
            return response.withImpressionId(impressionId);
        }

        if (!vectorLegReady) {
            log.info("Vector leg unavailable (embedding model or vector store not ready) — lexical-only search");
        }

        float[] queryEmbedding = vectorLegReady
                ? embeddingClient.embed(query, EmbeddingClient.Mode.QUERY)
                : null;
        List<Chunk> vectorHits = vectorLegReady
                ? chunkRepository.vectorSearch(queryEmbedding, vectorTopK)
                : List.of();
        List<Chunk> lexicalHits = chunkRepository.lexicalSearch(query, lexicalTopK);

        List<String> vectorIds = vectorHits.stream().map(Chunk::id).toList();
        List<String> lexicalIds = lexicalHits.stream().map(Chunk::id).toList();
        List<Map.Entry<String, Float>> fused = rrf.fuse(
                vectorIds, lexicalIds, decision.vectorWeight(), decision.lexicalWeight());

        Map<String, Chunk> byId = new HashMap<>();
        vectorHits.forEach(c -> byId.put(c.id(), c));
        lexicalHits.forEach(c -> byId.put(c.id(), c));
        int candidateLimit = safeTopN * 3;
        List<Chunk> candidates = new ArrayList<>(Math.min(candidateLimit, fused.size()));
        for (var entry : fused) {
            Chunk c = byId.get(entry.getKey());
            if (c != null) candidates.add(c);
            if (candidates.size() >= candidateLimit) break;
        }

        Map<String, FeedbackMemory.Adjustment> learned =
                learningService.memoryAdjustments(decision, queryEmbedding);
        int[] learnedApplied = {0};

        List<Reranker.ScoredChunk> reranked = reranker.rerank(query, candidates);
        // Filename relevance is a small, bounded feature and is applied before the
        // final sort. The previous post-sort 3x mutation produced scores that
        // contradicted result positions.
        //
        // Order matters here and is load-bearing: the learned delta is applied AFTER the relevance
        // filter, never before. That is what guarantees a thumbs-down can only sink a result, never
        // remove it — so the user can always find it again and change their mind.
        reranked = reranked.stream()
                .map(sc -> new Reranker.ScoredChunk(sc.chunk(),
                        Math.min(1f, sc.score() + 0.08f * titleCoverage(query, sc.chunk().sourceName()))))
                .filter(sc -> sc.score() >= minimumRelevanceScore
                        || lexicalCoverage(query, sc.chunk().content()) >= 0.30f)
                .map(sc -> {
                    FeedbackMemory.Adjustment adjustment = learned.get(sc.chunk().id());
                    if (adjustment == null) return sc;
                    learnedApplied[0]++;
                    return new Reranker.ScoredChunk(sc.chunk(),
                            Math.max(0f, Math.min(1f, sc.score() + adjustment.delta())));
                })
                .sorted(Comparator.comparing(Reranker.ScoredChunk::score).reversed()
                        .thenComparing(sc -> sc.chunk().id()))
                .toList();

        List<SearchResult> results = new ArrayList<>();
        int limit = Math.min(safeTopN, reranked.size());
        for (int i = 0; i < limit; i++) {
            Reranker.ScoredChunk sc = reranked.get(i);
            Chunk c = sc.chunk();
            float score = sc.score();

            // Ingested content injection defense (§9): wrap excerpt in clear delimiters
            // and quotes with explicit provenance labels to isolate it from instructions.
            String rawExcerpt = excerpt(c.content(), query);
            String safeExcerpt = String.format(
                    "--- START RETRIEVED CONTEXT FROM %s (%s) ---\n\"%s\"\n--- END RETRIEVED CONTEXT ---",
                    c.sourceName(),
                    c.sourcePath() != null ? c.sourcePath() : "local upload",
                    rawExcerpt.replace("\"", "\\\"").replaceAll("(?i)<script.*?>.*?</script>", "")
            );

            results.add(new SearchResult(
                    c, score, c.sourceName(), c.sourcePath(), null, "local",
                    c.aclTags(), safeExcerpt
            ));
        }

        List<String> webQueries = List.of();
        if (webLegReady) {
            List<String> localContext = reranked.stream()
                    .limit(3)
                    .map(sc -> sc.chunk().sourceName() + " " + excerpt(sc.chunk().content(), query))
                    .toList();
            webQueries = webSearchService.plannedQueries(query, localContext);
            List<SearchResult> webResults = fetchWebResults(query, localContext, safeTopN);
            results.addAll(webResults);
            log.info("Web augmentation: +{} web results for '{}'", webResults.size(), query);
        }

        results.sort(Comparator.comparing(SearchResult::score).reversed()
                .thenComparing(SearchResult::sourceKind)
                .thenComparing(result -> result.sourceName() == null ? "" : result.sourceName())
                .thenComparing(result -> result.chunk().id()));

        List<SearchResult> finalResults = List.copyOf(
                results.subList(0, Math.min(safeTopN, results.size())));
        SearchResponse response = new SearchResponse(finalResults, webQueries, null, learnedApplied[0]);
        // Cached without an impression id: the id identifies one *serving*, not one result set, so
        // every cache hit mints its own (see the hit path above).
        cacheService.putSearchResult(cacheKey, response);

        String impressionId = learningService.recordImpression(
                decision, surface, safeTopN, includeWeb, !vectorLegReady, false,
                servedResults(finalResults), learnedApplied[0], elapsedMs(startedAt));
        return response.withImpressionId(impressionId);
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /** The served ordering, flattened to what feedback needs to attribute a vote to a position. */
    private static List<LearningModel.ServedResult> servedResults(List<SearchResult> results) {
        List<LearningModel.ServedResult> served = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            served.add(new LearningModel.ServedResult(result.chunk().id(), i + 1, result.score()));
        }
        return served;
    }

    public List<String> getNotReadyPlugins() {
        List<String> notReady = new ArrayList<>();
        if (!pluginRegistry.isReady("sqlite-vec-store")) notReady.add("sqlite-vec-store");
        if (!pluginRegistry.isReady("nomic-embedding")) notReady.add("nomic-embedding");
        return notReady;
    }

    /** Whether anything has been ingested at all (lexical-only chunks count). */
    public boolean hasIndexedChunks() {
        try {
            return chunkRepository.count() > 0
                    || (connectorContentResolver != null && connectorContentResolver.hasCatalogEntries());
        } catch (Exception e) {
            return false;
        }
    }

    private List<SearchResult> fetchWebResults(String query, List<String> localContext, int topN) {
        List<WebSearchService.RankedWebResult> webResults =
                webSearchService.search(query, localContext, Math.min(topN, webResultCount));
        if (webResults.isEmpty()) return List.of();

        List<SearchResult> out = new ArrayList<>();
        for (WebSearchService.RankedWebResult ranked : webResults) {
            var wr = ranked.result();
            Chunk placeholder = Chunk.create(
                    "web-" + UUID.randomUUID(), wr.title(), wr.url(),
                    ranked.excerpt(), new float[0], List.of("source:web"), 0, 0,
                    "web", wr.url(), wr.url(), wr.publishedAt());
            out.add(new SearchResult(
                    placeholder, ranked.score(), wr.title(), null, wr.url(), "web",
                    List.of("source:web"), ranked.excerpt()
            ));
        }
        return out;
    }

    private static float titleCoverage(String query, String sourceName) {
        return TextSignals.titleCoverage(query, sourceName);
    }

    private static float lexicalCoverage(String query, String content) {
        return TextSignals.lexicalCoverage(query, content);
    }

    private static String excerpt(String content, String query) {
        if (content == null) return "";
        int max = 240;
        if (content.length() <= max) return content;
        String lower = content.toLowerCase();
        String firstTerm = query.toLowerCase().replaceAll("[^a-z0-9\\s]", "").trim().split("\\s+")[0];
        int idx = lower.indexOf(firstTerm);
        if (idx < 0) idx = 0;
        int start = Math.max(0, idx - max / 3);
        int end = Math.min(content.length(), start + max);
        String ex = content.substring(start, end);
        return (start > 0 ? "…" : "") + ex + (end < content.length() ? "…" : "");
    }
}
