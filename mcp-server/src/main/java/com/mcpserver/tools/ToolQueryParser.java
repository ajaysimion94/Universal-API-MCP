package com.mcpserver.tools;

import java.util.Optional;

/**
 * Parses the search bar's tool-invocation grammar (docs §5.8 plus app scoping):
 * <pre>
 *   #app_tool free text …           full tool id
 *   @app #tool free text …          app-scoped keyword
 *   @app                            app alone → browse that app's tools
 * </pre>
 * Only queries <em>starting</em> with {@code @} or {@code #} are tool queries — anything else
 * (including {@code #} mid-string) falls through to normal RAG search untouched.
 */
public final class ToolQueryParser {

    public record ParsedToolQuery(String appSlug, String toolKeyword, String remainder) {
    }

    private ToolQueryParser() {
    }

    public static Optional<ParsedToolQuery> parse(String query) {
        if (query == null) return Optional.empty();
        String q = query.trim();
        if (q.startsWith("@")) {
            String[] parts = splitFirstToken(q.substring(1));
            String appSlug = Slugifier.slug(parts[0]);
            String rest = parts[1];
            if (rest.startsWith("#")) {
                String[] toolParts = splitFirstToken(rest.substring(1));
                return Optional.of(new ParsedToolQuery(appSlug, normalizeKeyword(toolParts[0]), toolParts[1]));
            }
            return Optional.of(new ParsedToolQuery(appSlug, "", rest));
        }
        if (q.startsWith("#")) {
            String[] parts = splitFirstToken(q.substring(1));
            return Optional.of(new ParsedToolQuery(null, normalizeKeyword(parts[0]), parts[1]));
        }
        return Optional.empty();
    }

    /** Keywords are slugs; typing "Create-Todo" or "createTodo" after # still resolves. */
    private static String normalizeKeyword(String raw) {
        return raw.isBlank() ? "" : Slugifier.slug(raw);
    }

    private static String[] splitFirstToken(String s) {
        String trimmed = s.stripLeading();
        int ws = indexOfWhitespace(trimmed);
        if (ws < 0) return new String[]{trimmed, ""};
        return new String[]{trimmed.substring(0, ws), trimmed.substring(ws).trim()};
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }
}
