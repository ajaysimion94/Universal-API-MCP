package com.mcpserver.rag.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic contextual query planner for live web retrieval.
 *
 * <p>The embedding model cannot generate text, and the server intentionally has no
 * answer LLM. This planner extracts intent/entities/version tokens from the user's
 * wording and creates focused primary-source and recency variants without sending
 * the query to an external generation service.</p>
 */
@Component
public class WebQueryPlanner {

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "about", "an", "and", "are", "as", "at", "be", "based", "by",
            "can", "check", "do", "does", "for", "from", "get", "give", "how", "i",
            "in", "is", "it", "me", "my", "of", "on", "or", "our", "please", "search",
            "should", "that", "the", "their", "them", "this", "to", "use", "using",
            "was", "we", "what", "when", "where", "which", "who", "why", "with", "would");
    private static final Set<String> TIME_TERMS = Set.of(
            "current", "currently", "latest", "new", "recent", "today", "upgrade",
            "migration", "release", "version", "security", "cve", "advisory", "support");
    private static final Set<String> SECURITY_TERMS = Set.of(
            "security", "cve", "vulnerability", "advisory", "patch", "exploit");
    private static final Set<String> COMPARISON_TERMS = Set.of(
            "compare", "comparison", "versus", "vs", "review", "evaluate", "tradeoff");
    private static final Set<String> QUERY_INSTRUCTION_TERMS = Set.of(
            "compatibility", "documentation", "guide", "guidance", "issue", "issues",
            "migration", "official", "reference", "review", "security", "service",
            "source", "specification", "upgrade", "uses");
    private static final Set<String> CALENDAR_TERMS = Set.of(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december");
    private static final Pattern VERSIONED_ENTITY = Pattern.compile(
            "(?iu)\\b([\\p{L}][\\p{L}\\p{N}+#.-]*(?:\\s+[\\p{L}][\\p{L}\\p{N}+#.-]*){0,2})"
                    + "\\s+(\\d+(?:\\.\\d+){0,3})\\b");

    private final int maxQueries;
    private final Clock clock;

    @Autowired
    public WebQueryPlanner(@Value("${rag.web.query-count:4}") int maxQueries) {
        this(maxQueries, Clock.systemUTC());
    }

    WebQueryPlanner(int maxQueries, Clock clock) {
        this.maxQueries = Math.max(1, Math.min(maxQueries, 6));
        this.clock = clock;
    }

    public QueryPlan plan(String userQuery, List<String> localContext) {
        String original = clean(userQuery);
        if (original.isBlank()) return new QueryPlan(List.of(), false);

        LinkedHashSet<String> focusTerms = significantTerms(original, 10);
        addContextTerms(focusTerms, localContext);
        String anchors = versionedEntityAnchors(original);
        String focused = focusedQuery(anchors, focusTerms);
        if (focused.isBlank()) focused = original;

        Set<String> lowerTerms = significantTerms(original.toLowerCase(Locale.ROOT), 40);
        boolean timeSensitive = lowerTerms.stream().anyMatch(TIME_TERMS::contains);
        boolean security = lowerTerms.stream().anyMatch(SECURITY_TERMS::contains);
        boolean comparison = lowerTerms.stream().anyMatch(COMPARISON_TERMS::contains);
        int year = Year.now(clock).getValue();

        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(original);
        queries.add(focused + " official documentation migration guide");
        if (security) {
            queries.add(focused + " security advisory CVE compatibility " + year);
        } else if (timeSensitive) {
            queries.add(focused + " release notes upgrade support " + year);
        } else if (comparison) {
            queries.add(focused + " independent technical comparison evidence");
        } else {
            queries.add(focused + " technical documentation research");
        }
        queries.add(focused + " release notes upgrade support " + year);

        return new QueryPlan(queries.stream().limit(maxQueries).toList(), timeSensitive);
    }

    private static String focusedQuery(String anchors, LinkedHashSet<String> focusTerms) {
        LinkedHashSet<String> anchorTerms = significantTerms(
                anchors.replace("\"", ""), 30);
        List<String> intent = focusTerms.stream()
                .filter(term -> !anchorTerms.contains(term))
                .filter(term -> !QUERY_INSTRUCTION_TERMS.contains(term.toLowerCase(Locale.ROOT)))
                .filter(term -> !CALENDAR_TERMS.contains(term.toLowerCase(Locale.ROOT)))
                .filter(term -> !term.matches("(?:19|20)\\d{2}"))
                .limit(4)
                .toList();
        if (anchors.isBlank()) return String.join(" ", focusTerms);
        return intent.isEmpty() ? anchors : anchors + " " + String.join(" ", intent);
    }

    /**
     * Pulls version-bearing product phrases to the front and quotes them. This
     * prevents engines from reducing a verbose request such as "Java 17 service
     * using Spring Boot 3.3.4" to the ambiguous leading term "Java".
     */
    static String versionedEntityAnchors(String input) {
        LinkedHashSet<String> anchors = new LinkedHashSet<>();
        Matcher matcher = VERSIONED_ENTITY.matcher(clean(input));
        while (matcher.find() && anchors.size() < 3) {
            List<String> words = new ArrayList<>(List.of(matcher.group(1).split("\\s+")));
            while (!words.isEmpty()
                    && (STOP_WORDS.contains(words.get(0).toLowerCase(Locale.ROOT))
                    || QUERY_INSTRUCTION_TERMS.contains(words.get(0).toLowerCase(Locale.ROOT)))) {
                words.remove(0);
            }
            if (!words.isEmpty()
                    && !CALENDAR_TERMS.contains(words.get(0).toLowerCase(Locale.ROOT))) {
                anchors.add("\"" + String.join(" ", words) + " " + matcher.group(2) + "\"");
            }
        }
        return String.join(" ", anchors);
    }

    static LinkedHashSet<String> significantTerms(String input, int limit) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String raw : clean(input).split("[^\\p{L}\\p{N}._+#/-]+")) {
            String term = raw.trim();
            String lower = term.toLowerCase(Locale.ROOT);
            if (term.length() < 2 || STOP_WORDS.contains(lower)) continue;
            terms.add(term);
            if (terms.size() >= limit) break;
        }
        return terms;
    }

    private static void addContextTerms(LinkedHashSet<String> focusTerms, List<String> context) {
        if (context == null || context.isEmpty() || focusTerms.size() >= 14) return;
        Set<String> existing = new LinkedHashSet<>();
        focusTerms.forEach(term -> existing.add(term.toLowerCase(Locale.ROOT)));
        // Context is deliberately a small hint from the top local evidence, not a copy
        // of arbitrary retrieved instructions into a web query.
        for (String hint : context.stream().limit(3).toList()) {
            for (String candidate : significantTerms(hint, 4)) {
                String lower = candidate.toLowerCase(Locale.ROOT);
                if (candidate.length() >= 4 && !existing.contains(lower)) {
                    focusTerms.add(candidate);
                    existing.add(lower);
                }
                if (focusTerms.size() >= 14) return;
            }
        }
    }

    private static String clean(String input) {
        if (input == null) return "";
        String value = input.replaceAll("\\s+", " ").trim();
        return value.length() > 500 ? value.substring(0, 500).trim() : value;
    }

    public record QueryPlan(List<String> queries, boolean timeSensitive) {
        public QueryPlan {
            queries = List.copyOf(new ArrayList<>(queries));
        }
    }
}
