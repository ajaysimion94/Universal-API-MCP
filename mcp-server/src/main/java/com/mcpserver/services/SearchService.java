package com.mcpserver.services;

import com.mcpserver.cache.CacheService;
import com.mcpserver.models.Chunk;
import com.mcpserver.plugins.PluginRegistry;
import com.mcpserver.rag.embedding.EmbeddingClient;
import com.mcpserver.rag.reranker.Reranker;
import com.mcpserver.rag.retrieval.RrfFusion;
import com.mcpserver.rag.retrieval.SearchPipeline;
import com.mcpserver.rag.web.WebSearchService;
import com.mcpserver.repositories.ChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class SearchService implements SearchPipeline {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "does",
            "for", "from", "how", "i", "in", "is", "it", "of", "on", "or", "our",
            "that", "the", "this", "to", "was", "we", "what", "when", "where",
            "which", "who", "why", "with");

    private final EmbeddingClient embeddingClient;
    private final ChunkRepository chunkRepository;
    private final Reranker reranker;
    private final RrfFusion rrf;
    private final WebSearchService webSearchService;
    private final PluginRegistry pluginRegistry;
    private final CacheService cacheService;
    private final int vectorTopK;
    private final int lexicalTopK;
    private final int webResultCount;
    private final float minimumRelevanceScore;

    public SearchService(EmbeddingClient embeddingClient,
                         ChunkRepository chunkRepository,
                         Reranker reranker,
                         WebSearchService webSearchService,
                         PluginRegistry pluginRegistry,
                         CacheService cacheService,
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
        this.vectorTopK = vectorTopK;
        this.lexicalTopK = lexicalTopK;
        this.minimumRelevanceScore = minimumRelevanceScore;
        this.webResultCount = webResultCount;
    }

    @Override
    public List<SearchResult> search(String query, int topN, List<String> userAclTags) {
        return search(query, topN, userAclTags, false);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<SearchResult> search(String query, int topN, List<String> userAclTags, boolean includeWeb) {
        if (query == null || query.isBlank()) return List.of();

        // 1. In-process cache check (Phase 3 — §9)
        String cacheKey = CacheService.searchCacheKey(query, topN, includeWeb);
        Optional<Object> cached = cacheService.getSearchResult(cacheKey);
        if (cached.isPresent()) {
            log.debug("Search cache hit for key: {}", cacheKey);
            return (List<SearchResult>) cached.get();
        }

        // Lexical FTS5 search always works (built into SQLite); the vector leg needs
        // both the embedding model and the sqlite-vec store. Degrade gracefully.
        boolean vectorLegReady = embeddingClient.isReady() && chunkRepository.isVec0Available();
        if (!vectorLegReady) {
            log.info("Vector leg unavailable (embedding model or vector store not ready) — lexical-only search");
        }

        List<Chunk> vectorHits = vectorLegReady
                ? chunkRepository.vectorSearch(embeddingClient.embed(query, EmbeddingClient.Mode.QUERY), vectorTopK)
                : List.of();
        List<Chunk> lexicalHits = chunkRepository.lexicalSearch(query, lexicalTopK);

        List<String> vectorIds = vectorHits.stream().map(Chunk::id).toList();
        List<String> lexicalIds = lexicalHits.stream().map(Chunk::id).toList();
        List<Map.Entry<String, Float>> fused = rrf.fuse(vectorIds, lexicalIds);

        Map<String, Chunk> byId = new HashMap<>();
        vectorHits.forEach(c -> byId.put(c.id(), c));
        lexicalHits.forEach(c -> byId.put(c.id(), c));
        int candidateLimit = Math.max(topN, topN * 3);
        List<Chunk> candidates = new ArrayList<>(Math.min(candidateLimit, fused.size()));
        for (var entry : fused) {
            Chunk c = byId.get(entry.getKey());
            if (c != null) candidates.add(c);
            if (candidates.size() >= candidateLimit) break;
        }

        List<Reranker.ScoredChunk> reranked = reranker.rerank(query, candidates);
        // Filename relevance is a small, bounded feature and is applied before the
        // final sort. The previous post-sort 3x mutation produced scores that
        // contradicted result positions.
        reranked = reranked.stream()
                .map(sc -> new Reranker.ScoredChunk(sc.chunk(),
                        Math.min(1f, sc.score() + 0.08f * titleCoverage(query, sc.chunk().sourceName()))))
                .filter(sc -> sc.score() >= minimumRelevanceScore
                        || lexicalCoverage(query, sc.chunk().content()) >= 0.30f)
                .sorted(Comparator.comparing(Reranker.ScoredChunk::score).reversed()
                        .thenComparing(sc -> sc.chunk().id()))
                .toList();

        List<SearchResult> results = new ArrayList<>();
        int limit = Math.min(topN, reranked.size());
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

        if (includeWeb && pluginRegistry.isReady("searxng")) {
            List<SearchResult> webResults = fetchWebResults(query, List.of(), topN);
            results.addAll(webResults);
            log.info("Web augmentation: +{} web results for '{}'", webResults.size(), query);
        }

        results.sort(Comparator.comparing(SearchResult::score).reversed()
                .thenComparing(SearchResult::sourceKind)
                .thenComparing(result -> result.sourceName() == null ? "" : result.sourceName())
                .thenComparing(result -> result.chunk().id()));

        // Cache the search results before returning
        cacheService.putSearchResult(cacheKey, results);
        return results;
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
            return chunkRepository.count() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public List<String> plannedWebQueries(String query) {
        return webSearchService.plannedQueries(query);
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
        if (sourceName == null || sourceName.isBlank()) return 0f;
        Set<String> queryTerms = terms(query);
        Set<String> titleTerms = terms(sourceName);
        if (queryTerms.isEmpty() || titleTerms.isEmpty()) return 0f;
        long matches = queryTerms.stream().filter(titleTerms::contains).count();
        return (float) matches / queryTerms.size();
    }

    private static float lexicalCoverage(String query, String content) {
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) return 0f;
        Set<String> contentTerms = terms(content);
        long matches = queryTerms.stream().filter(contentTerms::contains).count();
        return (float) matches / queryTerms.size();
    }

    private static Set<String> terms(String value) {
        Set<String> terms = new HashSet<>();
        if (value == null) return terms;
        for (String term : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (term.length() > 2 && !STOP_WORDS.contains(term)) terms.add(term);
        }
        return terms;
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
