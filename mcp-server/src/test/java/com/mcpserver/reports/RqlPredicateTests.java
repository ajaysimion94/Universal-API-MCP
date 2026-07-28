package com.mcpserver.reports;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The predicate semantics ported from the report automation engine. */
class RqlPredicateTests {

    private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");

    private final RqlPredicate.Context plain = new RqlPredicate.Context(Map.of(), NOW);

    private Map<String, Object> row(Object... pairs) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return row;
    }

    private boolean match(Map<String, Object> row, String expression) {
        return RqlPredicate.matches(row, expression, Map.of(), plain);
    }

    @Test
    void comparesTextWithoutCaringAboutCase() {
        Map<String, Object> row = row("status", "Open", "title", "Migration Guide");
        assertThat(match(row, "status = \"open\"")).isTrue();
        assertThat(match(row, "title contains \"guide\"")).isTrue();
        assertThat(match(row, "title starts with \"migration\"")).isTrue();
        assertThat(match(row, "title ends with \"GUIDE\"")).isTrue();
        assertThat(match(row, "status in (\"OPEN\", \"closed\")")).isTrue();
        assertThat(match(row, "title not_contains \"draft\"")).isTrue();
        assertThat(match(row, "title not_contains \"MIGRATION\"")).isFalse();
    }

    @Test
    void regexMatchesAnywhereInTheValue() {
        Map<String, Object> row = row("code", "ticket ERR-421 raised");
        assertThat(match(row, "code regex \"ERR-[0-9]+\"")).isTrue();
        assertThat(match(row, "code regex \"^ERR-[0-9]+$\"")).isFalse();
    }

    @Test
    void conditionalAppliesADifferentThresholdPerBranch() {
        String expression = "if priority = \"high\" then (severity > 7) else (severity > 3)";
        assertThat(match(row("priority", "high", "severity", 8), expression)).isTrue();
        assertThat(match(row("priority", "high", "severity", 5), expression)).isFalse();
        assertThat(match(row("priority", "low", "severity", 5), expression)).isTrue();
        assertThat(match(row("priority", "low", "severity", 2), expression)).isFalse();
    }

    @Test
    void conditionalWithoutAnElseBranchKeepsNonMatchingRows() {
        String expression = "if status = \"active\" then (score > 50)";
        assertThat(match(row("status", "active", "score", 80), expression)).isTrue();
        assertThat(match(row("status", "active", "score", 10), expression)).isFalse();
        assertThat(match(row("status", "paused", "score", 10), expression)).isTrue();
    }

    @Test
    void trailingAndBindsOutsideTheConditional() {
        String expression = "if type = \"A\" then (val > 10) else (val > 5) and category = \"premium\"";
        assertThat(match(row("type", "A", "val", 12, "category", "premium"), expression)).isTrue();
        assertThat(match(row("type", "A", "val", 12, "category", "basic"), expression)).isFalse();
        assertThat(match(row("type", "B", "val", 7, "category", "premium"), expression)).isTrue();
    }

    @Test
    void nestedConditionalsBindTheirOwnElseBranch() {
        String expression = "if tier = \"gold\" then if region = \"eu\" then (score > 90) else (score > 70) "
                + "else (score > 10)";
        assertThat(match(row("tier", "gold", "region", "eu", "score", 95), expression)).isTrue();
        assertThat(match(row("tier", "gold", "region", "eu", "score", 80), expression)).isFalse();
        assertThat(match(row("tier", "gold", "region", "us", "score", 80), expression)).isTrue();
        assertThat(match(row("tier", "silver", "region", "us", "score", 20), expression)).isTrue();
        assertThat(match(row("tier", "silver", "region", "us", "score", 5), expression)).isFalse();
    }

    @Test
    void betweenStillWorksInsideAConjunction() {
        Map<String, Object> row = row("total", 120, "status", "open");
        assertThat(match(row, "status = \"open\" and total between 100 and 200")).isTrue();
        assertThat(match(row, "status = \"open\" and total between 200 and 300")).isFalse();
    }

    @Test
    void datePresetUsesTheConfiguredFormatAndZone() {
        RqlPredicate.Context context = new RqlPredicate.Context(
                Map.of("createdAt", new RqlDates.FieldConfig("yyyy-MM-dd'T'HH:mm:ss'Z'", "UTC")), NOW);
        assertThat(RqlPredicate.matches(row("createdAt", "2026-07-28T09:30:00Z"),
                "createdAt date_preset TODAY", Map.of(), context)).isTrue();
        assertThat(RqlPredicate.matches(row("createdAt", "2026-07-27T09:30:00Z"),
                "createdAt date_preset TODAY", Map.of(), context)).isFalse();
        assertThat(RqlPredicate.matches(row("createdAt", "2026-07-27T09:30:00Z"),
                "createdAt date_preset YESTERDAY", Map.of(), context)).isTrue();
        assertThat(RqlPredicate.matches(row("createdAt", "2026-07-02T00:00:00Z"),
                "createdAt date_preset THIS_MONTH", Map.of(), context)).isTrue();
        assertThat(RqlPredicate.matches(row("createdAt", "2026-06-30T23:00:00Z"),
                "createdAt date_preset THIS_MONTH", Map.of(), context)).isFalse();
    }

    @Test
    void unparseableDatesNeverMatchADateRule() {
        RqlPredicate.Context context = new RqlPredicate.Context(
                Map.of("createdAt", new RqlDates.FieldConfig(null, "UTC")), NOW);
        assertThat(RqlPredicate.matches(row("createdAt", "not a date"),
                "createdAt date_preset TODAY", Map.of(), context)).isFalse();
    }

    @Test
    void dateRangeIncludesTheWholeOfADateOnlyUpperBound() {
        RqlPredicate.Context context = new RqlPredicate.Context(
                Map.of("createdAt", new RqlDates.FieldConfig(null, "UTC")), NOW);
        String expression = "createdAt between \"2026-01-01\" and \"2026-01-31\"";
        assertThat(RqlPredicate.matches(row("createdAt", "2026-01-31T18:45:00Z"), expression, Map.of(), context))
                .isTrue();
        assertThat(RqlPredicate.matches(row("createdAt", "2026-02-01T00:10:00Z"), expression, Map.of(), context))
                .isFalse();
    }

    @Test
    void variablesResolveInsidePredicates() {
        assertThat(RqlPredicate.matches(row("userId", 7), "userId = $target",
                Map.of("target", 7), plain)).isTrue();
        assertThat(RqlPredicate.matches(row("userId", 7), "userId = $target",
                Map.of("target", 8), plain)).isFalse();
    }

    @Test
    void nullAndBooleanKeywordsReadMissingFieldsCorrectly() {
        assertThat(match(row("deletedAt", null), "deletedAt is null")).isTrue();
        assertThat(match(row("id", 1), "deletedAt is null")).isTrue();
        assertThat(match(row("active", true), "active is true")).isTrue();
        assertThat(match(row("active", "false"), "active is false")).isTrue();
        assertThat(match(row("active", true), "active is not false")).isTrue();
    }

    @Test
    void presetWindowsUseMondayWeeksAndInclusiveBoundaries() {
        ZoneId utc = ZoneId.of("UTC");
        RqlDates.Window week = RqlDates.window("THIS_WEEK", utc, NOW);
        assertThat(week.from()).isEqualTo(Instant.parse("2026-07-27T00:00:00Z"));
        assertThat(week.contains(Instant.parse("2026-08-02T23:59:59Z"))).isTrue();
        assertThat(week.contains(Instant.parse("2026-08-03T00:00:00Z"))).isFalse();

        RqlDates.Window quarter = RqlDates.window("LAST_QUARTER", utc, NOW);
        assertThat(quarter.from()).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"));
        assertThat(quarter.contains(Instant.parse("2026-06-30T12:00:00Z"))).isTrue();
        assertThat(quarter.contains(Instant.parse("2026-07-01T00:00:00Z"))).isFalse();

        assertThat(RqlDates.window("NEXT_DECADE", utc, NOW)).isNull();
    }
}
