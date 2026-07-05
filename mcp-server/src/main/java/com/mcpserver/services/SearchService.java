package com.mcpserver.services;

import com.mcpserver.models.Chunk;
import com.mcpserver.rag.embedding.EmbeddingClient;
import com.mcpserver.rag.reranker.Reranker;
import com.mcpserver.rag.retrieval.RrfFusion;
import com.mcpserver.rag.retrieval.SearchPipeline;
import com.mcpserver.rag.web.WebFetcher;
import com.mcpserver.repositories.ChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the RAG retrieval pipeline (plan.md §5.6):
 * embed query → hybrid search (vector + lexical, RRF) → rerank → cited context.
 * <p>
 * Returns cited context, not generated answers (the AI client synthesizes).
 * When the web toggle is on, live-web results (title + description + URL from
 * SearXNG) are merged into results tagged sourceKind="web" — not persisted.
 */
@Service
public class SearchService implements SearchPipeline {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final EmbeddingClient embeddingClient;
    private final ChunkRepository chunkRepository;
    private final Reranker reranker;
    private final RrfFusion rrf;
    private final WebFetcher webFetcher;
    private final int vectorTopK;
    private final int lexicalTopK;
    private final int rrfK;
    private final int webResultCount;

    public SearchService(EmbeddingClient embeddingClient,
                         ChunkRepository chunkRepository,
                         Reranker reranker,
                         WebFetcher webFetcher,
                         @Value("${rag.search.vector-top-k}") int vectorTopK,
                         @Value("${rag.search.lexical-top-k}") int lexicalTopK,
                         @Value("${rag.search.rrf-k}") int rrfK,
                         @Value("${rag.web.page-count:5}") int webResultCount) {
        this.embeddingClient = embeddingClient;
        this.chunkRepository = chunkRepository;
        this.reranker = reranker;
        this.rrf = new RrfFusion(rrfK);
        this.webFetcher = webFetcher;
        this.vectorTopK = vectorTopK;
        this.lexicalTopK = lexicalTopK;
        this.rrfK = rrfK;
        this.webResultCount = webResultCount;
    }

    @Override
    public List<SearchResult> search(String query, int topN, List<String> userAclTags) {
        return search(query, topN, userAclTags, false);
    }

    @Override
    public List<SearchResult> search(String query, int topN, List<String> userAclTags, boolean includeWeb) {
        if (query == null || query.isBlank()) return List.of();

        // 1. Embed the query (QUERY mode — nomic prefix).
        float[] queryEmbedding = embeddingClient.embed(query, EmbeddingClient.Mode.QUERY);

        // 2. Local hybrid search — both legs.
        List<Chunk> vectorHits = chunkRepository.vectorSearch(queryEmbedding, vectorTopK);
        List<Chunk> lexicalHits = chunkRepository.lexicalSearch(query, lexicalTopK);

        // 3. RRF fusion by chunk id.
        List<String> vectorIds = vectorHits.stream().map(Chunk::id).toList();
        List<String> lexicalIds = lexicalHits.stream().map(Chunk::id).toList();
        List<Map.Entry<String, Float>> fused = rrf.fuse(vectorIds, lexicalIds);

        // 4. Resolve fused ids to chunks.
        Map<String, Chunk> byId = new HashMap<>();
        vectorHits.forEach(c -> byId.put(c.id(), c));
        lexicalHits.forEach(c -> byId.put(c.id(), c));
        List<Chunk> candidates = new ArrayList<>();
        for (var entry : fused) {
            Chunk c = byId.get(entry.getKey());
            if (c != null) candidates.add(c);
            if (candidates.size() >= Math.max(topN, fused.size())) break;
        }
        if (candidates.size() > topN * 3) candidates = candidates.subList(0, topN * 3);

        // 5. Rerank (pass-through for now — keeps RRF order).
        List<Reranker.ScoredChunk> reranked = reranker.rerank(query, candidates);

        // 6. Build cited local results.
        List<SearchResult> results = new ArrayList<>();
        int limit = Math.min(topN, reranked.size());
        for (int i = 0; i < limit; i++) {
            Reranker.ScoredChunk sc = reranked.get(i);
            Chunk c = sc.chunk();
            results.add(new SearchResult(
                    c, sc.score(), c.sourceName(), c.sourcePath(), null, "local",
                    c.aclTags(), excerpt(c.content(), query)
            ));
        }

        // 7. Web augmentation (title + description from SearXNG, not persisted).
        if (includeWeb) {
            List<SearchResult> webResults = fetchWebResults(query, topN);
            results.addAll(webResults);
            log.info("Web augmentation: +{} web results for '{}'", webResults.size(), query);
        }

        return results;
    }

    private List<SearchResult> fetchWebResults(String query, int topN) {
        List<WebFetcher.WebResult> webResults = webFetcher.fetch(query, webResultCount);
        if (webResults.isEmpty()) return List.of();

        List<SearchResult> out = new ArrayList<>();
        float score = 1f;
        for (WebFetcher.WebResult wr : webResults) {
            Chunk placeholder = Chunk.create(
                    "web-" + UUID.randomUUID(), wr.title(), wr.url(),
                    wr.description(), new float[0], List.of("source:web"), 0, 0);
            out.add(new SearchResult(
                    placeholder, score, wr.title(), null, wr.url(), "web",
                    List.of("source:web"), wr.description()
            ));
            score -= 0.05f;
        }
        return out.size() > topN ? out.subList(0, topN) : out;
    }

    /** Extract a short excerpt around the first query-term match. */
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
