package com.mcpserver.evaluation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.models.Chunk;
import com.mcpserver.plugins.BundledResourceExtractor;
import com.mcpserver.rag.embedding.EmbeddingClient;
import com.mcpserver.rag.embedding.OnnxEmbeddingClient;
import com.mcpserver.rag.reranker.OnnxCrossEncoderReranker;
import com.mcpserver.rag.reranker.Reranker;
import com.mcpserver.rag.retrieval.RrfFusion;
import com.mcpserver.rag.retrieval.TextSignals;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * Offline ranking regression gate over the actual Nomic embedding and MiniLM cross-encoder models.
 * It exercises vector ordering, lexical ordering, RRF and final reranking, then emits P@1, MRR,
 * graded nDCG@10 and Recall@candidate.
 * <p>
 * <strong>This test deliberately does not start Spring.</strong> A Spring-backed version would pull
 * in the shared datasource and the native vector extension, which is absent in CI — the vector leg
 * would silently degrade and the gate would then be measuring a different, weaker pipeline while
 * still reporting green. Staying context-free is also why it can run on every push. The cost is
 * that the retrieval <em>plumbing</em> is re-implemented here; the <em>scoring</em> is not, because
 * every scoring primitive comes from {@link TextSignals}, which production uses too.
 * <p>
 * Known gap: the corpus is scored one chunk per document, so chunk-level ranking within a long
 * document is not exercised by this gate.
 */
class GoldenSetRegressionTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path MODULE_ROOT = locateModuleRoot();

    /** Mirrors the production filter in SearchService: a weak score survives on strong term overlap. */
    private static final double LEXICAL_RESCUE_COVERAGE = 0.30d;

    @Test
    void rankingMeetsGoldenSetBaseline() throws Exception {
        Optional<Path> embeddingDirCandidate = firstCompleteModelDirectory(
                "model_quantized.onnx",
                MODULE_ROOT.resolve("target/classes/bundled/model"),
                MODULE_ROOT.resolve("models/nomic-embed-text-v1.5"));
        Optional<Path> rerankerDirCandidate = firstCompleteModelDirectory(
                "model.onnx",
                MODULE_ROOT.resolve("target/classes/bundled/reranker"),
                MODULE_ROOT.resolve("models/ms-marco-MiniLM-L6-v2"));
        assumeTrue(embeddingDirCandidate.isPresent() && rerankerDirCandidate.isPresent(),
                "ONNX model files were not found in the build output or mcp-server/models; "
                        + "bundle them or upload both pinned model/tokenizer pairs from /plugins");
        Path embeddingDir = embeddingDirCandidate.orElseThrow();
        Path rerankerDir = rerankerDirCandidate.orElseThrow();

        List<DocumentFixture> documents = MAPPER.readValue(
                MODULE_ROOT.resolve("eval-harness/corpus/documents.json").toFile(),
                new TypeReference<>() {});
        List<GoldenQuery> queries = MAPPER.readValue(
                MODULE_ROOT.resolve("eval-harness/golden-set/search.json").toFile(),
                new TypeReference<>() {});
        List<GoldenQuery> negativeQueries = MAPPER.readValue(
                MODULE_ROOT.resolve("eval-harness/golden-set/negative-search.json").toFile(),
                new TypeReference<>() {});
        Baseline baseline = MAPPER.readValue(
                MODULE_ROOT.resolve("eval-harness/golden-set/baseline.json").toFile(),
                Baseline.class);
        assertThat(queries).hasSize(baseline.queryCount());
        assertThat(negativeQueries).hasSize(baseline.negativeQueryCount());

        // Pipeline knobs come from application.yml, never from the gate file: a baseline defines
        // what "good enough" means, and must not also define the behaviour being measured.
        SearchSettings settings = SearchSettings.fromApplicationYaml();

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
        Map<String, Chunk> byId = new HashMap<>();
        corpus.forEach(chunk -> byId.put(chunk.id(), chunk));

        List<QueryResult> details = new ArrayList<>();
        double reciprocalRankSum = 0d;
        double ndcgSum = 0d;
        int precisionAt1Hits = 0;
        int negativeRejections = 0;
        int candidateRecallHits = 0;
        int candidateLimit = settings.finalTopN() * 3;
        RrfFusion fusion = new RrfFusion(settings.rrfK());

        List<GoldenQuery> allQueries = new ArrayList<>(queries);
        allQueries.addAll(negativeQueries);
        for (GoldenQuery golden : allQueries) {
            float[] queryEmbedding = embeddings.embed(golden.query(), EmbeddingClient.Mode.QUERY);
            List<String> vectorRanked = corpus.stream()
                    .sorted(Comparator.comparingDouble(
                                    (Chunk chunk) -> TextSignals.cosine(queryEmbedding, chunk.embedding())).reversed()
                            .thenComparing(Chunk::id))
                    .map(Chunk::id)
                    .toList();
            List<String> lexicalRanked = corpus.stream()
                    .sorted(Comparator.comparingDouble(
                                    (Chunk chunk) -> TextSignals.lexicalCoverage(golden.query(), chunk.content())).reversed()
                            .thenComparing(Chunk::id))
                    .map(Chunk::id)
                    .toList();

            List<Chunk> candidates = fusion.fuse(vectorRanked, lexicalRanked).stream()
                    .limit(candidateLimit)
                    .map(entry -> byId.get(entry.getKey()))
                    .toList();

            List<Reranker.ScoredChunk> rawRanked = reranker.rerank(golden.query(), candidates);
            List<Reranker.ScoredChunk> ranked = rawRanked.stream()
                    .map(scored -> new Reranker.ScoredChunk(scored.chunk(), (float) Math.min(1d,
                            scored.score() + 0.08d * TextSignals.titleCoverage(
                                    golden.query(), scored.chunk().sourceName()))))
                    .filter(scored -> scored.score() >= settings.minimumRelevanceScore()
                            || TextSignals.lexicalCoverage(golden.query(), scored.chunk().content())
                                    >= LEXICAL_RESCUE_COVERAGE)
                    .sorted(Comparator.comparing(Reranker.ScoredChunk::score).reversed()
                            .thenComparing(scored -> scored.chunk().id()))
                    .toList();

            List<String> rankedIds = ranked.stream().map(scored -> scored.chunk().id()).toList();
            int rank = relevantRank(rankedIds, golden.relevantIds());

            if (golden.relevantIds().isEmpty()) {
                if (ranked.isEmpty()) negativeRejections++;
            } else {
                if (rank == 1) precisionAt1Hits++;
                if (rank > 0) reciprocalRankSum += 1d / rank;
                ndcgSum += ndcgAt10(rankedIds, golden.grades());
                // Recall@candidate is the metric the fusion weights actually control: everything
                // after this point can only reorder what the candidate window already contains.
                boolean recalled = candidates.stream()
                        .anyMatch(chunk -> golden.relevantIds().contains(chunk.id()));
                if (recalled) candidateRecallHits++;
            }

            details.add(new QueryResult(golden.query(), golden.relevantIds(), rank, rankedIds,
                    rawRanked.stream().limit(3)
                            .map(scored -> new RawScore(scored.chunk().id(), scored.score(),
                                    TextSignals.lexicalCoverage(golden.query(), scored.chunk().content())))
                            .toList()));
        }

        double precisionAt1 = precisionAt1Hits / (double) queries.size();
        double mrr = reciprocalRankSum / queries.size();
        double ndcgAt10 = ndcgSum / queries.size();
        double recallAtCandidate = candidateRecallHits / (double) queries.size();
        double negativeRejectionAccuracy = negativeRejections / (double) negativeQueries.size();

        String runId = System.getProperty("eval.run.id", Long.toString(System.currentTimeMillis()));
        Path reportPath = MODULE_ROOT.resolve("eval-runs").resolve(runId).resolve("report.json");
        Files.createDirectories(reportPath.getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", runId);
        report.put("documentCount", documents.size());
        report.put("queryCount", queries.size());
        report.put("negativeQueryCount", negativeQueries.size());
        report.put("precisionAt1", precisionAt1);
        report.put("mrr", mrr);
        report.put("ndcgAt10", ndcgAt10);
        report.put("recallAtCandidate", recallAtCandidate);
        report.put("negativeRejectionAccuracy", negativeRejectionAccuracy);
        report.put("settings", Map.of(
                "rrfK", settings.rrfK(),
                "finalTopN", settings.finalTopN(),
                "candidateLimit", candidateLimit,
                "minimumRelevanceScore", settings.minimumRelevanceScore()));
        report.put("baseline", baseline);
        report.put("queries", details);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);

        reranker.close();
        embeddings.unload();

        assertThat(precisionAt1).as("P@1; report: %s", reportPath)
                .isGreaterThanOrEqualTo(baseline.minimumPrecisionAt1());
        assertThat(mrr).as("MRR; report: %s", reportPath)
                .isGreaterThanOrEqualTo(baseline.minimumMrr());
        assertThat(ndcgAt10).as("graded nDCG@10; report: %s", reportPath)
                .isGreaterThanOrEqualTo(baseline.minimumNdcgAt10());
        assertThat(recallAtCandidate).as("Recall@candidate; report: %s", reportPath)
                .isGreaterThanOrEqualTo(baseline.minimumRecallAtCandidate());
        assertThat(negativeRejectionAccuracy).as("negative rejection accuracy; report: %s", reportPath)
                .isGreaterThanOrEqualTo(baseline.minimumNegativeRejectionAccuracy());
    }

    private static Optional<Path> firstCompleteModelDirectory(String modelFile, Path... candidates) {
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate.resolve(modelFile))
                    && Files.isRegularFile(candidate.resolve("tokenizer.json"))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Locate the Maven module from Surefire, an IDE launched at the repository root, or Windows. */
    private static Path locateModuleRoot() {
        Optional<Path> fromWorkingDirectory = findModuleRoot(Path.of("").toAbsolutePath());
        if (fromWorkingDirectory.isPresent()) return fromWorkingDirectory.get();
        try {
            Path classLocation = Path.of(GoldenSetRegressionTests.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Optional<Path> fromClassLocation = findModuleRoot(classLocation);
            if (fromClassLocation.isPresent()) return fromClassLocation.get();
        } catch (Exception ignored) {
            // The clear exception below includes the working directory the operator can correct.
        }
        throw new IllegalStateException("Could not locate mcp-server from working directory "
                + Path.of("").toAbsolutePath());
    }

    private static Optional<Path> findModuleRoot(Path start) {
        Path current = Files.isDirectory(start) ? start : start.getParent();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("src/main"))) {
                return Optional.of(current);
            }
            Path nestedModule = current.resolve("mcp-server");
            if (Files.isRegularFile(nestedModule.resolve("pom.xml"))
                    && Files.isDirectory(nestedModule.resolve("src/main"))) {
                return Optional.of(nestedModule);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private static int relevantRank(List<String> rankedIds, List<String> relevantIds) {
        for (int index = 0; index < rankedIds.size(); index++) {
            if (relevantIds.contains(rankedIds.get(index))) return index + 1;
        }
        return 0;
    }

    /**
     * Graded nDCG with a real ideal ordering. With one relevant document per query the ideal DCG is
     * 1 and this degenerates to MRR-like behaviour, which is exactly what made the previous fixture's
     * nDCG uninformative; the graded fixture is what gives the metric something to measure.
     */
    private static double ndcgAt10(List<String> rankedIds, Map<String, Integer> grades) {
        if (grades == null || grades.isEmpty()) return 0d;
        double dcg = 0d;
        int limit = Math.min(10, rankedIds.size());
        for (int index = 0; index < limit; index++) {
            int grade = grades.getOrDefault(rankedIds.get(index), 0);
            if (grade > 0) dcg += (Math.pow(2, grade) - 1) / log2(index + 2d);
        }
        double idealDcg = 0d;
        List<Integer> idealGrades = grades.values().stream()
                .sorted(Comparator.reverseOrder()).limit(10).toList();
        for (int index = 0; index < idealGrades.size(); index++) {
            idealDcg += (Math.pow(2, idealGrades.get(index)) - 1) / log2(index + 2d);
        }
        return idealDcg == 0 ? 0d : dcg / idealDcg;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2d);
    }

    /** Retrieval knobs, read from the same file the running application reads. */
    private record SearchSettings(int rrfK, int finalTopN, double minimumRelevanceScore) {

        @SuppressWarnings("unchecked")
        static SearchSettings fromApplicationYaml() throws Exception {
            try (InputStream stream = GoldenSetRegressionTests.class
                    .getResourceAsStream("/application.yml")) {
                Map<String, Object> root = new Yaml().load(stream);
                Map<String, Object> search =
                        (Map<String, Object>) ((Map<String, Object>) root.get("rag")).get("search");
                return new SearchSettings(
                        ((Number) search.get("rrf-k")).intValue(),
                        ((Number) search.get("final-top-n")).intValue(),
                        ((Number) search.get("min-relevance-score")).doubleValue());
            }
        }
    }

    private record DocumentFixture(String id, String sourceName, String content) {}

    /**
     * @param grades optional per-document relevance grades (3 = answers it, 1 = related). Absent in
     *               the negative fixture, where the point is that nothing is relevant.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GoldenQuery(String query, List<String> relevantIds, Map<String, Integer> grades) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Baseline(int queryCount, int negativeQueryCount,
                            double minimumPrecisionAt1, double minimumMrr,
                            double minimumNdcgAt10, double minimumRecallAtCandidate,
                            double minimumNegativeRejectionAccuracy) {}

    private record QueryResult(String query, List<String> relevantIds,
                               int rank, List<String> rankedIds, List<RawScore> rawTopScores) {}

    private record RawScore(String id, float score, double lexicalCoverage) {}
}
