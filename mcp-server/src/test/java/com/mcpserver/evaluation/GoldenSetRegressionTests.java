package com.mcpserver.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.models.Chunk;
import com.mcpserver.plugins.BundledResourceExtractor;
import com.mcpserver.rag.embedding.EmbeddingClient;
import com.mcpserver.rag.embedding.OnnxEmbeddingClient;
import com.mcpserver.rag.reranker.OnnxCrossEncoderReranker;
import com.mcpserver.rag.reranker.Reranker;
import com.mcpserver.rag.retrieval.RrfFusion;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * Offline ranking regression gate over the actual Nomic embedding and MiniLM
 * cross-encoder models. It exercises vector ordering, lexical ordering, RRF,
 * and final reranking, then emits P@1, MRR, and nDCG@10.
 */
class GoldenSetRegressionTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "does",
            "for", "from", "how", "i", "in", "is", "it", "of", "on", "or", "our",
            "that", "the", "this", "to", "was", "we", "what", "when", "where",
            "which", "who", "why", "with");

    @Test
    void rankingMeetsGoldenSetBaseline() throws Exception {
        Path embeddingDir = Path.of("target/classes/bundled/model");
        Path rerankerDir = Path.of("target/classes/bundled/reranker");
        assumeTrue(Files.exists(embeddingDir.resolve("model_quantized.onnx"))
                        && Files.exists(rerankerDir.resolve("model.onnx")),
                "model bundle was explicitly skipped; run scripts/run-eval.sh for the quality gate");

        List<DocumentFixture> documents = MAPPER.readValue(
                REPO_ROOT.resolve("eval-harness/corpus/documents.json").toFile(),
                new TypeReference<>() {});
        List<GoldenQuery> queries = MAPPER.readValue(
                REPO_ROOT.resolve("eval-harness/golden-set/search.json").toFile(),
                new TypeReference<>() {});
        List<GoldenQuery> negativeQueries = MAPPER.readValue(
                REPO_ROOT.resolve("eval-harness/golden-set/negative-search.json").toFile(),
                new TypeReference<>() {});
        Baseline baseline = MAPPER.readValue(
                REPO_ROOT.resolve("eval-harness/golden-set/baseline.json").toFile(),
                Baseline.class);
        assertThat(queries).hasSize(baseline.queryCount());
        assertThat(negativeQueries).hasSize(baseline.negativeQueryCount());

        OnnxEmbeddingClient embeddings = new OnnxEmbeddingClient(
                embeddingDir.toString(), "model_quantized.onnx", 768, 512);
        embeddings.ensureLoaded();
        assertThat(embeddings.isReady()).isTrue();
        OnnxCrossEncoderReranker reranker = new OnnxCrossEncoderReranker(
                embeddings, mock(BundledResourceExtractor.class),
                rerankerDir.toString(), "model.onnx", 256);
        assertThat(reranker.isModelReady()).isTrue();

        List<Chunk> corpus = new ArrayList<>();
        for (DocumentFixture document : documents) {
            corpus.add(new Chunk(
                    document.id(), document.id(), document.sourceName(), "/" + document.sourceName(),
                    document.content(),
                    embeddings.embed(document.content(), EmbeddingClient.Mode.DOCUMENT),
                    List.of("public"), 0, document.content().length() / 4, Instant.EPOCH,
                    "eval", document.id(), null, null));
        }

        List<QueryResult> details = new ArrayList<>();
        double reciprocalRankSum = 0d;
        double ndcgSum = 0d;
        int precisionAt1Hits = 0;
        int negativeRejections = 0;
        RrfFusion fusion = new RrfFusion(60);
        List<GoldenQuery> allQueries = new ArrayList<>(queries);
        allQueries.addAll(negativeQueries);
        for (GoldenQuery golden : allQueries) {
            float[] queryEmbedding = embeddings.embed(golden.query(), EmbeddingClient.Mode.QUERY);
            List<Chunk> vectorRanked = corpus.stream()
                    .sorted(Comparator.comparingDouble(
                                    (Chunk chunk) -> cosine(queryEmbedding, chunk.embedding())).reversed()
                            .thenComparing(Chunk::id))
                    .toList();
            List<Chunk> lexicalRanked = corpus.stream()
                    .sorted(Comparator.comparingDouble(
                                    (Chunk chunk) -> lexicalScore(golden.query(), chunk.content())).reversed()
                            .thenComparing(Chunk::id))
                    .toList();

            Map<String, Chunk> byId = new HashMap<>();
            corpus.forEach(chunk -> byId.put(chunk.id(), chunk));
            List<Chunk> candidates = fusion.fuse(
                            vectorRanked.stream().map(Chunk::id).toList(),
                            lexicalRanked.stream().map(Chunk::id).toList()).stream()
                    .limit(6)
                    .map(entry -> byId.get(entry.getKey()))
                    .toList();
            List<Reranker.ScoredChunk> rawRanked = reranker.rerank(golden.query(), candidates);
            List<Reranker.ScoredChunk> ranked = rawRanked.stream()
                    .filter(scored -> scored.score() >= baseline.minimumRelevanceScore()
                            || lexicalScore(golden.query(), scored.chunk().content()) >= 0.30d)
                    .toList();

            int rank = relevantRank(ranked, golden.relevantIds());
            if (golden.relevantIds().isEmpty()) {
                if (ranked.isEmpty()) negativeRejections++;
            } else {
                if (rank == 1) precisionAt1Hits++;
                if (rank > 0) {
                    reciprocalRankSum += 1d / rank;
                    ndcgSum += 1d / log2(rank + 1d);
                }
            }
            details.add(new QueryResult(golden.query(), golden.relevantIds(), rank,
                    ranked.stream().map(scored -> scored.chunk().id()).toList(),
                    rawRanked.stream().limit(3)
                            .map(scored -> new RawScore(scored.chunk().id(), scored.score(),
                                    lexicalScore(golden.query(), scored.chunk().content())))
                            .toList()));
        }

        double precisionAt1 = precisionAt1Hits / (double) queries.size();
        double mrr = reciprocalRankSum / queries.size();
        double ndcgAt10 = ndcgSum / queries.size();
        double negativeRejectionAccuracy =
                negativeRejections / (double) negativeQueries.size();
        String runId = System.getProperty("eval.run.id",
                Long.toString(System.currentTimeMillis()));
        Path reportPath = REPO_ROOT.resolve("eval-runs").resolve(runId).resolve("report.json");
        Files.createDirectories(reportPath.getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", runId);
        report.put("queryCount", queries.size());
        report.put("negativeQueryCount", negativeQueries.size());
        report.put("precisionAt1", precisionAt1);
        report.put("mrr", mrr);
        report.put("ndcgAt10", ndcgAt10);
        report.put("negativeRejectionAccuracy", negativeRejectionAccuracy);
        report.put("baseline", baseline);
        report.put("queries", details);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);

        reranker.close();
        embeddings.unload();

        assertThat(precisionAt1).as("P@1; report: %s", reportPath)
                .isGreaterThanOrEqualTo(baseline.minimumPrecisionAt1());
        assertThat(mrr).as("MRR; report: %s", reportPath)
                .isGreaterThanOrEqualTo(baseline.minimumMrr());
        assertThat(ndcgAt10).as("nDCG@10; report: %s", reportPath)
                .isGreaterThanOrEqualTo(baseline.minimumNdcgAt10());
        assertThat(negativeRejectionAccuracy).as("negative rejection accuracy; report: %s", reportPath)
                .isGreaterThanOrEqualTo(baseline.minimumNegativeRejectionAccuracy());
    }

    private static int relevantRank(List<Reranker.ScoredChunk> ranked, List<String> relevantIds) {
        for (int index = 0; index < ranked.size(); index++) {
            if (relevantIds.contains(ranked.get(index).chunk().id())) return index + 1;
        }
        return 0;
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0d;
        double leftNorm = 0d;
        double rightNorm = 0d;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static double lexicalScore(String query, String document) {
        Set<String> queryTerms = terms(query);
        Set<String> documentTerms = terms(document);
        if (queryTerms.isEmpty()) return 0d;
        long matches = queryTerms.stream().filter(documentTerms::contains).count();
        return matches / (double) queryTerms.size();
    }

    private static Set<String> terms(String value) {
        Set<String> terms = new HashSet<>();
        for (String term : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (term.length() > 2 && !STOP_WORDS.contains(term)) terms.add(term);
        }
        return terms;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2d);
    }

    private record DocumentFixture(String id, String sourceName, String content) {}
    private record GoldenQuery(String query, List<String> relevantIds) {}
    private record Baseline(int queryCount, int negativeQueryCount,
                            double minimumPrecisionAt1, double minimumMrr,
                            double minimumNdcgAt10, double minimumNegativeRejectionAccuracy,
                            double minimumRelevanceScore) {}
    private record QueryResult(String query, List<String> relevantIds,
                               int rank, List<String> rankedIds, List<RawScore> rawTopScores) {}
    private record RawScore(String id, float score, double lexicalCoverage) {}
}
