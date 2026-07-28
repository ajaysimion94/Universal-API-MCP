package com.mcpserver.reports;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.mcpserver.reports.RqlValues.compare;
import static com.mcpserver.reports.RqlValues.field;
import static com.mcpserver.reports.RqlValues.literal;
import static com.mcpserver.reports.RqlValues.normalizeScalar;
import static com.mcpserver.reports.RqlValues.text;
import static com.mcpserver.reports.RqlValues.trimParens;
import static com.mcpserver.reports.RqlValues.value;

/**
 * Evaluates one {@code where} / {@code having} expression against a row.
 *
 * <p>Precedence is or → and → not → primary, with {@code if … then … else …} evaluated as a
 * primary so that a trailing {@code and} binds outside the conditional, matching the report
 * automation engine this is ported from. Text operators are case-insensitive and {@code regex}
 * uses find() semantics, for the same reason.
 */
public final class RqlPredicate {

    private static final Pattern IF_ELSE = Pattern.compile("(?is)^if\\s+(.+)$");
    private static final Pattern IS = Pattern.compile("(?is)^(.+?)\\s+is\\s+(not\\s+)?(null|true|false)$");
    private static final Pattern IN = Pattern.compile("(?is)^(.+?)\\s+(not\\s+)?in\\s*\\((.*)\\)$");
    private static final Pattern TEXT_OP = Pattern.compile(
            "(?is)^(.+?)\\s+(not\\s+)?(like|ilike|not_contains|contains|starts\\s+with|ends\\s+with|regex)\\s+(.+)$");
    private static final Pattern COMPARISON = Pattern.compile("(?is)^(.+?)\\s*(>=|<=|!=|<>|==|=|>|<)\\s*(.+)$");
    private static final Pattern BETWEEN = Pattern.compile("(?is)^(.+?)\\s+between\\s+(.+?)\\s+and\\s+(.+)$");
    private static final Pattern DATE_PRESET = Pattern.compile("(?is)^(.+?)\\s+date_preset\\s+(\\S+)$");

    /** Field-level date configuration plus the reference instant a preset resolves against. */
    public record Context(Map<String, RqlDates.FieldConfig> dateConfig, Instant now) {

        public static Context of(Map<String, RqlDates.FieldConfig> dateConfig) {
            return new Context(dateConfig == null ? Map.of() : dateConfig, Instant.now());
        }

        public RqlDates.FieldConfig configFor(String fieldPath) {
            if (dateConfig.isEmpty() || fieldPath == null) return null;
            RqlDates.FieldConfig exact = dateConfig.get(fieldPath.trim());
            return exact != null ? exact : dateConfig.get(RqlValues.fieldName(fieldPath));
        }
    }

    private RqlPredicate() {
    }

    public static boolean matches(Map<String, Object> row, String expression,
                                 Map<String, Object> variables, Context context) {
        String normalized = trimParens(expression == null ? "" : expression.trim());
        if (normalized.isEmpty()) return true;

        List<String> orParts = RqlValues.splitKeyword(normalized, "or");
        if (orParts.size() > 1) {
            return orParts.stream().anyMatch(part -> matches(row, part, variables, context));
        }

        // 'between x and y' owns its 'and', so it is matched before the AND split — but only when
        // nothing above it is being combined, otherwise "a = 1 and b between 2 and 3" misparses.
        Matcher between = BETWEEN.matcher(normalized);
        if (between.matches() && RqlValues.splitKeyword(between.group(1), "and").size() == 1) {
            return betweenMatches(row, between, variables, context);
        }

        List<String> andParts = RqlValues.splitKeyword(normalized, "and");
        if (andParts.size() > 1) {
            return andParts.stream().allMatch(part -> matches(row, part, variables, context));
        }

        Matcher conditional = IF_ELSE.matcher(normalized);
        if (conditional.matches()) return ifElseMatches(row, conditional.group(1), variables, context);

        if (normalized.toLowerCase(Locale.ROOT).startsWith("not ")) {
            return !matches(row, normalized.substring(4), variables, context);
        }

        Matcher preset = DATE_PRESET.matcher(normalized);
        if (preset.matches()) return presetMatches(row, preset.group(1), preset.group(2), context);

        Matcher is = IS.matcher(normalized);
        if (is.matches()) {
            Object actual = value(row, is.group(1), variables);
            Object expected = literal(is.group(3), variables);
            boolean equal = Objects.equals(normalizeScalar(actual), normalizeScalar(expected))
                    || (expected instanceof Boolean bool && actual != null && RqlValues.truthy(actual) == bool);
            return is.group(2) == null ? equal : !equal;
        }

        Matcher in = IN.matcher(normalized);
        if (in.matches()) {
            Object actual = value(row, in.group(1), variables);
            boolean found = RqlParser.splitTopLevel(in.group(3), ",", 0).stream()
                    .map(part -> value(row, part.text(), variables))
                    .anyMatch(item -> compare(actual, item) == 0);
            return in.group(2) == null ? found : !found;
        }

        Matcher textOp = TEXT_OP.matcher(normalized);
        if (textOp.matches()) return textMatches(row, textOp, variables);

        Matcher comparison = COMPARISON.matcher(normalized);
        if (comparison.matches()) {
            Object left = value(row, comparison.group(1), variables);
            Object right = value(row, comparison.group(3), variables);
            int result = compareOperands(left, right, comparison.group(1), context);
            return switch (comparison.group(2)) {
                case "=", "==" -> result == 0;
                case "!=", "<>" -> result != 0;
                case ">" -> result > 0;
                case ">=" -> result >= 0;
                case "<" -> result < 0;
                case "<=" -> result <= 0;
                default -> false;
            };
        }

        Object bare = value(row, normalized, variables);
        return bare instanceof Boolean bool ? bool : RqlValues.truthy(bare);
    }

    /**
     * {@code if <condition> then <expr> [else <expr>]}. With no else branch a false condition
     * leaves the row in place — the conditional only ever adds a requirement.
     */
    private static boolean ifElseMatches(Map<String, Object> row, String body,
                                         Map<String, Object> variables, Context context) {
        List<String> thenParts = RqlValues.splitKeyword(body, "then");
        if (thenParts.size() < 2) return true;
        String condition = thenParts.get(0);
        String remainder = String.join(" then ", thenParts.subList(1, thenParts.size()));
        String[] branches = RqlValues.splitAtMatchingElse(remainder);
        if (matches(row, condition, variables, context)) {
            return matches(row, branches[0], variables, context);
        }
        // No else branch means the conditional only ever adds a requirement, never removes a row.
        return branches[1] == null || matches(row, branches[1], variables, context);
    }

    private static boolean betweenMatches(Map<String, Object> row, Matcher between,
                                          Map<String, Object> variables, Context context) {
        String fieldPath = between.group(1);
        Object actual = value(row, fieldPath, variables);
        Object low = value(row, between.group(2), variables);
        Object high = value(row, between.group(3), variables);
        RqlDates.FieldConfig config = context.configFor(fieldPath);
        if (config != null || RqlDates.looksLikeDate(actual) || RqlDates.looksLikeDate(low)) {
            Instant instant = RqlDates.parse(actual, config);
            Instant from = RqlDates.parse(low, config);
            Instant to = RqlDates.parse(high, config);
            if (instant == null) return false;
            if (from != null && instant.isBefore(from)) return false;
            // A date-only upper bound covers its whole day, matching the report engine's windows.
            if (to != null && instant.isAfter(endOfDayIfDateOnly(high, to, config))) return false;
            return from != null || to != null;
        }
        return compare(actual, low) >= 0 && compare(actual, high) <= 0;
    }

    private static Instant endOfDayIfDateOnly(Object raw, Instant parsed, RqlDates.FieldConfig config) {
        String text = text(raw).trim();
        if (!text.matches("\\d{4}-\\d{2}-\\d{2}")) return parsed;
        java.time.ZoneId zone = config == null ? java.time.ZoneId.systemDefault() : config.zone();
        return parsed.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1);
    }

    private static boolean presetMatches(Map<String, Object> row, String fieldPath, String preset,
                                         Context context) {
        RqlDates.FieldConfig config = context.configFor(fieldPath);
        Instant instant = RqlDates.parse(field(row, fieldPath.trim()), config);
        if (instant == null) return false;
        RqlDates.Window window = RqlDates.window(preset, config == null
                ? java.time.ZoneId.systemDefault() : config.zone(), context.now());
        return window != null && window.contains(instant);
    }

    private static boolean textMatches(Map<String, Object> row, Matcher matcher,
                                       Map<String, Object> variables) {
        String actual = text(value(row, matcher.group(1), variables));
        String operand = text(value(row, matcher.group(4), variables));
        String op = matcher.group(3).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        boolean result = switch (op) {
            case "contains" -> containsIgnoreCase(actual, operand);
            case "not_contains" -> !containsIgnoreCase(actual, operand);
            case "starts with" -> actual.toLowerCase(Locale.ROOT).startsWith(operand.toLowerCase(Locale.ROOT));
            case "ends with" -> actual.toLowerCase(Locale.ROOT).endsWith(operand.toLowerCase(Locale.ROOT));
            case "regex" -> regexFinds(actual, operand);
            case "ilike" -> actual.toLowerCase(Locale.ROOT).matches(sqlPattern(operand.toLowerCase(Locale.ROOT)));
            default -> actual.matches(sqlPattern(operand));
        };
        return matcher.group(2) == null ? result : !result;
    }

    private static int compareOperands(Object left, Object right, String fieldPath, Context context) {
        RqlDates.FieldConfig config = context.configFor(fieldPath);
        if (config != null || (RqlDates.looksLikeDate(left) && RqlDates.looksLikeDate(right))) {
            Instant leftInstant = RqlDates.parse(left, config);
            Instant rightInstant = RqlDates.parse(right, config);
            if (leftInstant != null && rightInstant != null) return leftInstant.compareTo(rightInstant);
        }
        return compare(left, right);
    }

    private static boolean containsIgnoreCase(String actual, String operand) {
        return actual.toLowerCase(Locale.ROOT).contains(operand.toLowerCase(Locale.ROOT));
    }

    /** find() rather than matches(), so an unanchored pattern behaves as report authors expect. */
    private static boolean regexFinds(String actual, String pattern) {
        try {
            return Pattern.compile(pattern).matcher(actual).find();
        } catch (PatternSyntaxException ignored) {
            return false;
        }
    }

    private static String sqlPattern(String operand) {
        return "(?s)" + Pattern.quote(operand).replace("%", "\\E.*\\Q").replace("_", "\\E.\\Q");
    }
}
