package com.mcpserver.reports;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Field access, literal parsing, and comparison for RQL — shared by the predicate evaluator and
 * every pipeline stage so that "what does this operand mean" is answered in exactly one place.
 *
 * <p>Comparison follows the report semantics ported from the .filter engine: numeric first, then
 * <em>case-insensitive</em> string comparison. Two values that differ only in case are equal here,
 * which is what report authors expect of API data ("Open" and "open" are one status).
 */
public final class RqlValues {

    /** Row-fingerprint field separator — a control character no API value contains. */
    private static final char SEPARATOR = '\u0001';

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private RqlValues() {
    }

    /** Resolves an operand against a row: {@code $variable}, a literal, or a field path. */
    public static Object value(Map<String, Object> row, String operand, Map<String, Object> variables) {
        String trimmed = trimParens(operand == null ? "" : operand.trim());
        if (trimmed.startsWith("$")) return variables.get(trimmed.substring(1));
        if (isLiteral(trimmed)) return literal(trimmed, variables);
        return field(row, trimmed);
    }

    /** Parses a literal: quoted string, number, boolean, null, or {@code $variable}. */
    public static Object literal(String source, Map<String, Object> variables) {
        String value = source == null ? "" : source.trim();
        if (value.startsWith("$")) return variables.get(value.substring(1));
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"");
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) return Boolean.parseBoolean(value);
        if (value.equalsIgnoreCase("null")) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    public static boolean isLiteral(String value) {
        return value.startsWith("\"") || value.startsWith("'") || value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("false") || value.equalsIgnoreCase("null")
                || value.startsWith("$") || NUMBER.matcher(value).matches();
    }

    /** Reads a dotted path ({@code fields.status.name}) or a bracketed key ({@code ["Due Date"]}). */
    @SuppressWarnings("unchecked")
    public static Object field(Map<String, Object> row, String path) {
        String normalized = path == null ? "" : path.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1).replaceAll("^[\"']|[\"']$", "");
        }
        // A flattened key wins over path traversal: expand and lookup write literal "items.name" keys.
        if (row.containsKey(normalized)) return row.get(normalized);
        Object current = row;
        for (String part : normalized.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                Map<String, Object> typed = (Map<String, Object>) map;
                if (!typed.containsKey(part)) return null;
                current = typed.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    /** The output column name for an expression — the last dotted segment. */
    public static String fieldName(String field) {
        String normalized = field.trim().replaceAll("^\\[[\"']?|[\"']?]$", "");
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    /** Numeric when both sides are numeric, then case-insensitive string comparison. */
    public static int compare(Object left, Object right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        BigDecimal leftNumber = number(left);
        BigDecimal rightNumber = number(right);
        if (leftNumber != null && rightNumber != null) return leftNumber.compareTo(rightNumber);
        if (left instanceof Boolean || right instanceof Boolean) {
            return Boolean.compare(truthy(left), truthy(right));
        }
        return text(left).compareToIgnoreCase(text(right));
    }

    public static BigDecimal number(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        if (value instanceof Boolean) return null;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static boolean truthy(Object value) {
        if (value instanceof Boolean bool) return bool;
        return "true".equalsIgnoreCase(text(value)) || "yes".equalsIgnoreCase(text(value));
    }

    public static String text(Object value) {
        if (value == null) return "";
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        return String.valueOf(value);
    }

    public static Object normalizeScalar(Object value) {
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros();
        return value;
    }

    /** A stable identity for a row, used by distinct and the set operations. */
    public static String fingerprint(Map<String, Object> row) {
        StringBuilder builder = new StringBuilder();
        row.forEach((key, value) -> builder.append(key).append('=').append(text(normalizeScalar(value))).append(SEPARATOR));
        return builder.toString();
    }

    public static String trimParens(String value) {
        String result = value;
        while (result.startsWith("(") && result.endsWith(")") && balanced(result)) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    /**
     * Splits a conditional's tail into then-branch and else-branch (null when there is none).
     * A nested {@code if} claims the next {@code else}, so
     * {@code if a then if b then x else y else z} reads y as b's alternative and z as a's.
     */
    public static String[] splitAtMatchingElse(String text) {
        boolean quote = false;
        char quoteChar = '"';
        int depth = 0;
        int nestedIf = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote) {
                if (c == quoteChar) quote = false;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = true;
                quoteChar = c;
                continue;
            }
            if (c == '(' || c == '[') depth++;
            if (c == ')' || c == ']') depth--;
            if (depth != 0) continue;
            if (isWordAt(text, i, "if")) {
                nestedIf++;
                i++;
                continue;
            }
            if (isWordAt(text, i, "else")) {
                if (nestedIf > 0) {
                    nestedIf--;
                    i += 3;
                    continue;
                }
                return new String[]{text.substring(0, i), text.substring(i + 4)};
            }
        }
        return new String[]{text, null};
    }

    private static boolean isWordAt(String text, int index, String word) {
        if (!text.regionMatches(true, index, word, 0, word.length())) return false;
        char before = index == 0 ? ' ' : text.charAt(index - 1);
        char after = index + word.length() >= text.length() ? ' ' : text.charAt(index + word.length());
        return Character.isWhitespace(before) && Character.isWhitespace(after);
    }

    /** True when the outer parentheses of {@code value} wrap the whole expression. */
    private static boolean balanced(String value) {
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '(') depth++;
            if (c == ')') {
                depth--;
                if (depth == 0 && i < value.length() - 1) return false;
            }
        }
        return depth == 0;
    }

    /**
     * Splits on a bare keyword ({@code and}, {@code or}, {@code then}) outside quotes and brackets.
     * An {@code and} that belongs to a preceding {@code between} is not a split point — the range
     * owns that word.
     */
    public static List<String> splitKeyword(String source, String keyword) {
        List<String> parts = new java.util.ArrayList<>();
        boolean quote = false;
        char quoteChar = '"';
        int depth = 0;
        int start = 0;
        int pendingBetween = 0;
        boolean skipBetweenAnd = keyword.equalsIgnoreCase("and");
        String needle = " " + keyword.toLowerCase(Locale.ROOT) + " ";
        for (int i = 0; i <= source.length() - needle.length(); i++) {
            char c = source.charAt(i);
            if (quote) {
                if (c == quoteChar) quote = false;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = true;
                quoteChar = c;
                continue;
            }
            if (c == '(' || c == '[') depth++;
            if (c == ')' || c == ']') depth--;
            if (depth == 0 && skipBetweenAnd && source.regionMatches(true, i, " between ", 0, 9)) {
                pendingBetween++;
                i += 8;
                continue;
            }
            if (depth == 0 && source.regionMatches(true, i, needle, 0, needle.length())) {
                if (pendingBetween > 0) {
                    pendingBetween--;
                    i += needle.length() - 1;
                    continue;
                }
                parts.add(source.substring(start, i));
                start = i + needle.length();
                i = start - 1;
            }
        }
        if (parts.isEmpty()) return List.of(source);
        parts.add(source.substring(start));
        return parts;
    }
}
