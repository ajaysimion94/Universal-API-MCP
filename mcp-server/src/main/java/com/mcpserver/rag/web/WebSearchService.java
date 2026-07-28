package com.mcpserver.rag.web;

import com.mcpserver.models.Chunk;
import com.mcpserver.rag.embedding.EmbeddingClient;
import com.mcpserver.rag.reranker.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Contextual web-research pipeline: query planning, metasearch, canonical
 * deduplication, bounded page extraction, semantic reranking, authority/freshness
 * quality features, and domain-diverse final selection.
 */
@Service
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "how",
            "in", "is", "it", "of", "on", "or", "that", "the", "this", "to", "was",
            "what", "when", "where", "which", "who", "why", "with");
    private static final Set<String> TRACKING_PARAMS = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "gclid", "fbclid", "mc_cid", "mc_eid", "ref", "source");

    private final WebQueryPlanner queryPlanner;
    private final WebFetcher webFetcher;
    private final WebPageContentFetcher pageFetcher;
    private final Reranker reranker;
    private final EmbeddingClient embeddingClient;
    private final int candidateCount;
    private final int pageFetchCount;
    private final double minimumRelevanceScore;

    public WebSearchService(
            WebQueryPlanner queryPlanner,
            WebFetcher webFetcher,
            WebPageContentFetcher pageFetcher,
            Reranker reranker,
            EmbeddingClient embeddingClient,
            @Value("${rag.web.candidate-count:24}") int candidateCount,
            @Value("${rag.web.page-fetch-count:10}") int pageFetchCount,
            @Value("${rag.web.min-relevance-score:0.20}") double minimumRelevanceScore) {
        this.queryPlanner = queryPlanner;
        this.webFetcher = webFetcher;
        this.pageFetcher = pageFetcher;
        this.reranker = reranker;
        this.embeddingClient = embeddingClient;
        this.candidateCount = Math.max(5, candidateCount);
        this.pageFetchCount = Math.max(0, pageFetchCount);
        this.minimumRelevanceScore = clamp01(minimumRelevanceScore);
    }

    public List<RankedWebResult> search(String userQuery, List<String> localContext, int topN) {
        WebQueryPlanner.QueryPlan plan = queryPlanner.plan(userQuery, localContext);
        if (plan.queries().isEmpty() || topN <= 0) return List.of();

        int perQuery = Math.max(3,
                (int) Math.ceil((double) candidateCount / plan.queries().size()));
        List<WebFetcher.WebResult> raw = webFetcher.fetch(plan.queries(), perQuery);
        List<AggregatedResult> candidates = aggregate(raw).stream()
                .peek(candidate -> candidate.preScore = preliminaryScore(
                        userQuery, candidate, plan.timeSensitive(), plan.queries().size()))
                .sorted(Comparator.comparingDouble((AggregatedResult c) -> c.preScore).reversed()
                        .thenComparing(c -> c.canonicalUrl))
                .limit(candidateCount)
                .toList();
        if (candidates.isEmpty()) return List.of();

        Map<String, String> pages = pageFetcher.fetch(
                candidates.stream().map(AggregatedResult::representative).toList(),
                pageFetchCount);

        Map<String, AggregatedResult> byChunkId = new HashMap<>();
        List<Chunk> chunks = new ArrayList<>(candidates.size());
        for (AggregatedResult candidate : candidates) {
            String pageContent = pages.getOrDefault(candidate.representative.url(), "");
            candidate.pageContent = pageContent;
            String passage = passage(candidate);
            float[] embedding = null;
            if (embeddingClient.isReady()) {
                try {
                    embedding = embeddingClient.embed(passage, EmbeddingClient.Mode.DOCUMENT);
                } catch (RuntimeException e) {
                    log.debug("Web candidate embedding failed for {}: {}",
                            candidate.canonicalUrl, e.getMessage());
                }
            }
            Chunk chunk = Chunk.create(
                    "web-" + Integer.toUnsignedString(candidate.canonicalUrl.hashCode()),
                    candidate.representative.title(), candidate.representative.url(),
                    passage, embedding, List.of("source:web"), 0, approximateTokens(passage),
                    "web", candidate.canonicalUrl, candidate.representative.url(),
                    candidate.publishedAt);
            chunks.add(chunk);
            byChunkId.put(chunk.id(), candidate);
        }

        List<ScoredCandidate> semanticallyRanked = new ArrayList<>();
        for (Reranker.ScoredChunk scored : reranker.rerank(userQuery, chunks)) {
            AggregatedResult candidate = byChunkId.get(scored.chunk().id());
            if (candidate == null) continue;
            double pageEvidence = candidate.pageContent.isBlank() ? 0d : 1d;
            double authority = authorityScore(userQuery, candidate.representative);
            double score = clamp01(0.45d * scored.score()
                    + 0.20d * candidate.preScore
                    + 0.30d * authority
                    + 0.05d * pageEvidence);
            semanticallyRanked.add(new ScoredCandidate(candidate, score));
        }
        semanticallyRanked.sort(Comparator
                .comparingDouble(ScoredCandidate::score).reversed()
                .thenComparing(scored -> scored.candidate.canonicalUrl));
        semanticallyRanked.removeIf(scored -> scored.score < minimumRelevanceScore);

        List<RankedWebResult> selected = diversify(semanticallyRanked, topN);
        log.info("Contextual web search: {} variants → {} raw → {} unique → {} ranked",
                plan.queries().size(), raw.size(), candidates.size(), selected.size());
        return selected;
    }

    public List<String> plannedQueries(String userQuery) {
        return queryPlanner.plan(userQuery, List.of()).queries();
    }

    private static List<AggregatedResult> aggregate(List<WebFetcher.WebResult> raw) {
        Map<String, AggregatedResult> unique = new LinkedHashMap<>();
        for (WebFetcher.WebResult result : raw) {
            String canonical = canonicalUrl(result.url());
            if (canonical.isBlank()) continue;
            unique.computeIfAbsent(canonical, ignored -> new AggregatedResult(canonical, result))
                    .add(result);
        }
        return new ArrayList<>(unique.values());
    }

    private static double preliminaryScore(String query, AggregatedResult candidate,
                                           boolean timeSensitive, int queryCount) {
        String searchable = candidate.representative.title() + " "
                + candidate.representative.description();
        double lexical = termCoverage(query, searchable);
        double authority = authorityScore(query, candidate.representative);
        double rankSignal = 1d / Math.max(1, candidate.bestRank);
        double engineScore = candidate.providerScore <= 0 ? 0
                : candidate.providerScore / (1d + Math.abs(candidate.providerScore));
        double provider = Math.max(rankSignal, engineScore);
        double corroboration = clamp01(
                (candidate.queries.size() / (double) Math.max(1, queryCount)) * 0.65d
                        + Math.min(1d, candidate.engines.size() / 3d) * 0.35d);
        double freshness = freshnessScore(candidate, timeSensitive);
        return clamp01(0.28d * lexical + 0.22d * authority + 0.18d * provider
                + 0.17d * corroboration + 0.15d * freshness);
    }

    static double authorityScore(String query, WebFetcher.WebResult result) {
        String host = host(result.url());
        String path = "";
        try {
            path = URI.create(result.url()).getPath().toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {}
        String title = result.title().toLowerCase(Locale.ROOT);

        double score = 0.35d;
        if (host.endsWith(".gov") || host.endsWith(".edu")) score += 0.30d;
        if (host.equals("github.com") || host.startsWith("docs.")
                || host.startsWith("developer.") || host.startsWith("support.")) score += 0.18d;
        if (path.contains("/docs") || path.contains("/documentation")
                || path.contains("/reference") || path.contains("/spec")
                || path.contains("/releases") || path.contains("/advisories")) score += 0.15d;
        if (title.contains("official") || title.contains("documentation")
                || title.contains("reference") || title.contains("release notes")
                || title.contains("security advisory")) score += 0.12d;

        Set<String> queryTerms = terms(query);
        boolean entityDomainMatch = queryTerms.stream()
                .filter(term -> term.length() >= 5)
                .anyMatch(term -> host.replace("-", "").contains(term.replace("-", "")));
        if (entityDomainMatch) score += 0.18d;

        if (title.matches(".*\\b(ultimate|complete|best|top \\d+|everything you need)\\b.*")) {
            score -= 0.22d;
        }
        return clamp01(score);
    }

    private static double freshnessScore(AggregatedResult candidate, boolean timeSensitive) {
        if (!timeSensitive) return 0.5d;
        if (candidate.publishedAt != null) {
            long days = Math.abs(Duration.between(candidate.publishedAt, Instant.now()).toDays());
            if (days <= 365) return 1d;
            if (days <= 1_095) return 0.65d;
            return 0.25d;
        }
        int year = Year.now().getValue();
        String text = candidate.representative.title() + " " + candidate.representative.description();
        if (text.contains(String.valueOf(year))) return 0.85d;
        if (text.contains(String.valueOf(year - 1))) return 0.65d;
        return 0.30d;
    }

    private static List<RankedWebResult> diversify(List<ScoredCandidate> ranked, int topN) {
        List<ScoredCandidate> remaining = new ArrayList<>(ranked);
        List<RankedWebResult> selected = new ArrayList<>();
        Map<String, Integer> domainCounts = new HashMap<>();
        while (!remaining.isEmpty() && selected.size() < topN) {
            ScoredCandidate best = null;
            double bestAdjusted = -1d;
            for (ScoredCandidate candidate : remaining) {
                String domain = host(candidate.candidate.representative.url());
                int count = domainCounts.getOrDefault(domain, 0);
                double adjusted = candidate.score - (0.14d * count);
                if (adjusted > bestAdjusted
                        || (adjusted == bestAdjusted && (best == null
                        || candidate.candidate.canonicalUrl.compareTo(best.candidate.canonicalUrl) < 0))) {
                    best = candidate;
                    bestAdjusted = adjusted;
                }
            }
            if (best == null) break;
            remaining.remove(best);
            String domain = host(best.candidate.representative.url());
            domainCounts.merge(domain, 1, Integer::sum);
            selected.add(new RankedWebResult(best.candidate.representative,
                    (float) clamp01(bestAdjusted), excerpt(best.candidate)));
        }
        return selected;
    }

    private static String passage(AggregatedResult candidate) {
        StringBuilder passage = new StringBuilder();
        append(passage, candidate.representative.title());
        append(passage, candidate.representative.description());
        append(passage, candidate.pageContent);
        return passage.toString();
    }

    private static String excerpt(AggregatedResult candidate) {
        String value = candidate.pageContent.isBlank()
                ? candidate.representative.description()
                : candidate.pageContent;
        value = value.replaceAll("(?is)<script.*?>.*?</script>", "")
                .replaceAll("(?is)<style.*?>.*?</style>", "")
                .replaceAll("\\s+", " ").trim();
        return value.length() > 500 ? value.substring(0, 500).trim() + "…" : value;
    }

    private static void append(StringBuilder target, String value) {
        if (value == null || value.isBlank()) return;
        if (!target.isEmpty()) target.append('\n');
        target.append(value);
    }

    private static double termCoverage(String query, String candidate) {
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) return 0d;
        Set<String> candidateTerms = terms(candidate);
        long matches = queryTerms.stream().filter(candidateTerms::contains).count();
        return matches / (double) queryTerms.size();
    }

    private static Set<String> terms(String value) {
        if (value == null) return Set.of();
        Set<String> terms = new HashSet<>();
        for (String raw : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}._+#/-]+")) {
            if (raw.length() > 1 && !STOP_WORDS.contains(raw)) terms.add(raw);
        }
        return terms;
    }

    static String canonicalUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if ((!"http".equals(scheme) && !"https".equals(scheme)) || host.isBlank()) return "";
            int port = uri.getPort();
            String authority = port < 0 ? host : host + ":" + port;
            String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String query = uri.getRawQuery();
            if (query != null) {
                query = java.util.Arrays.stream(query.split("&"))
                        .filter(part -> {
                            String key = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
                            return !TRACKING_PARAMS.contains(key);
                        })
                        .sorted()
                        .collect(Collectors.joining("&"));
            }
            return new URI(scheme, authority, path, query == null || query.isBlank() ? null : query, null)
                    .toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return "";
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "";
        }
    }

    private static int approximateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }

    private static double clamp01(double value) {
        return Math.max(0d, Math.min(1d, value));
    }

    public record RankedWebResult(WebFetcher.WebResult result, float score, String excerpt) {}

    private static final class AggregatedResult {
        private final String canonicalUrl;
        private WebFetcher.WebResult representative;
        private final Set<String> queries = new LinkedHashSet<>();
        private final Set<String> engines = new LinkedHashSet<>();
        private int bestRank = Integer.MAX_VALUE;
        private double providerScore;
        private Instant publishedAt;
        private double preScore;
        private String pageContent = "";

        private AggregatedResult(String canonicalUrl, WebFetcher.WebResult representative) {
            this.canonicalUrl = canonicalUrl;
            this.representative = representative;
        }

        private void add(WebFetcher.WebResult result) {
            queries.add(result.query());
            if (result.engine() != null) {
                for (String engine : result.engine().split(",")) {
                    if (!engine.isBlank()) engines.add(engine.trim());
                }
            }
            bestRank = Math.min(bestRank, result.providerRank());
            providerScore = Math.max(providerScore, result.providerScore());
            if (result.publishedAt() != null
                    && (publishedAt == null || result.publishedAt().isAfter(publishedAt))) {
                publishedAt = result.publishedAt();
            }
            if (safe(result.description()).length() > safe(representative.description()).length()) {
                representative = result;
            }
        }

        private WebFetcher.WebResult representative() {
            return representative;
        }
    }

    private record ScoredCandidate(AggregatedResult candidate, double score) {}

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
