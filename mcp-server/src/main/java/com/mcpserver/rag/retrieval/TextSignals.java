package com.mcpserver.rag.retrieval;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Cheap, model-free lexical signals shared across the retrieval pipeline.
 * <p>
 * These live here rather than as private helpers on one class because three places need exactly the
 * same definition of "a query term": {@code SearchService} (relevance filtering and the filename
 * bonus), the {@code com.mcpserver.learning} subsystem (query normalization and Jaccard matching),
 * and the golden-set eval harness. When the harness kept its own copy the two drifted, which meant
 * the quality gate was scoring a slightly different pipeline than the one that shipped.
 */
public final class TextSignals {

    /**
     * Mirrors {@code ChunkRepository.FTS_STOP_WORDS} — the words the FTS5 leg strips — so a term
     * that cannot influence lexical retrieval also cannot influence lexical scoring.
     */
    public static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "does",
            "for", "from", "how", "i", "in", "is", "it", "of", "on", "or", "our",
            "that", "the", "this", "to", "was", "we", "what", "when", "where",
            "which", "who", "why", "with");

    private TextSignals() {
    }

    /** Content-bearing terms: lowercased, longer than two characters, stop words removed. */
    public static Set<String> terms(String value) {
        Set<String> terms = new HashSet<>();
        if (value == null) return terms;
        for (String term : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (term.length() > 2 && !STOP_WORDS.contains(term)) terms.add(term);
        }
        return terms;
    }

    /**
     * A stable key for "the same question asked again". Sorting the term set means word order and
     * filler words cannot fork one query family into two, which is what the feedback memory keys on.
     * Returns the lowercased original when a query is nothing but stop words, so short queries still
     * group with themselves rather than collapsing into one empty bucket.
     */
    public static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) return "";
        Set<String> sorted = new TreeSet<>(terms(query));
        if (sorted.isEmpty()) return query.trim().toLowerCase(Locale.ROOT);
        return String.join(" ", sorted);
    }

    /** Fraction of the query's terms that appear in the content. */
    public static float lexicalCoverage(String query, String content) {
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) return 0f;
        Set<String> contentTerms = terms(content);
        long matches = queryTerms.stream().filter(contentTerms::contains).count();
        return (float) matches / queryTerms.size();
    }

    /** Fraction of the query's terms that appear in a source's filename or title. */
    public static float titleCoverage(String query, String sourceName) {
        if (sourceName == null || sourceName.isBlank()) return 0f;
        Set<String> queryTerms = terms(query);
        Set<String> titleTerms = terms(sourceName);
        if (queryTerms.isEmpty() || titleTerms.isEmpty()) return 0f;
        long matches = queryTerms.stream().filter(titleTerms::contains).count();
        return (float) matches / queryTerms.size();
    }

    /** Jaccard overlap of two term sets. Empty on either side is 0, never 1. */
    public static float jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0f;
        int intersection = 0;
        for (String term : left) {
            if (right.contains(term)) intersection++;
        }
        int union = left.size() + right.size() - intersection;
        return union == 0 ? 0f : (float) intersection / union;
    }

    /** Cosine similarity of two equal-length vectors; 0 when either is absent or degenerate. */
    public static float cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) return 0f;
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) return 0f;
        return (float) (dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm)));
    }
}
