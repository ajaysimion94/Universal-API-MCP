package com.mcpserver.reports;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.mcpserver.reports.RqlModel.*;

/**
 * Small, recovery-oriented RQL parser. It deliberately represents a malformed statement rather
 * than throwing: editor analysis can continue over every following statement.
 */
public class RqlParser {

    private static final Pattern USE = Pattern.compile("(?is)^\\s*use\\s+collection\\s+\\\"([^\\\"]+)\\\"\\s*$");
    private static final Pattern SET = Pattern.compile("(?is)^\\s*set\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+?)\\s*$");
    private static final Pattern LET = Pattern.compile("(?is)^\\s*let\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+?)\\s*$");
    private static final Pattern EMIT = Pattern.compile("(?is)^\\s*emit\\s+(.+?)(?:\\s+as\\s+\\\"([^\\\"]+)\\\")?\\s*$");

    public ParsedProgram parse(String source) {
        String text = source == null ? "" : source;
        List<Statement> statements = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        try {
            String clean = maskComments(text);
            int statementStart = 0;
            boolean endedWithDelimiter = false;
            for (int index = 0; index < clean.length(); index++) {
                if (clean.charAt(index) != ';' || !atTopLevel(clean, index)) continue;
                parseStatement(text, clean.substring(statementStart, index), statementStart, index,
                        statements, diagnostics);
                statementStart = index + 1;
                endedWithDelimiter = true;
            }
            if (!clean.substring(statementStart).trim().isEmpty()) {
                parseStatement(text, clean.substring(statementStart), statementStart, clean.length(),
                        statements, diagnostics);
                diagnostics.add(new Diagnostic(Span.of(text, clean.length(), clean.length()), Severity.ERROR,
                        "RQL001", "Expected ';' to terminate this statement."));
            } else if (!clean.trim().isEmpty() && !endedWithDelimiter) {
                diagnostics.add(new Diagnostic(Span.of(text, clean.length(), clean.length()), Severity.ERROR,
                        "RQL001", "Expected ';' to terminate this statement."));
            }
        } catch (RuntimeException exception) {
            // This is a final safety net, not normal control flow. The parser contract is never-throw.
            diagnostics.add(new Diagnostic(Span.of(text, 0, text.length()), Severity.ERROR, "RQL099",
                    "Could not read this RQL document: " + safeMessage(exception)));
        }
        return new ParsedProgram(statements, diagnostics);
    }

    private void parseStatement(String source, String raw, int start, int end,
                                List<Statement> statements, List<Diagnostic> diagnostics) {
        String statement = raw.trim();
        if (statement.isEmpty()) return;
        Span span = Span.of(source, start, end);
        Matcher matcher = USE.matcher(statement);
        if (matcher.matches()) {
            statements.add(new UseStatement(matcher.group(1), span));
            return;
        }
        matcher = SET.matcher(statement);
        if (matcher.matches()) {
            statements.add(new SetStatement(matcher.group(1), matcher.group(2).trim(), span));
            return;
        }
        matcher = LET.matcher(statement);
        if (matcher.matches()) {
            String pipeline = matcher.group(2).trim();
            statements.add(new LetStatement(matcher.group(1), pipeline, span));
            validatePipeline(source, pipeline, start + raw.indexOf(pipeline), diagnostics);
            return;
        }
        matcher = EMIT.matcher(statement);
        if (matcher.matches()) {
            String pipeline = matcher.group(1).trim();
            statements.add(new EmitStatement(pipeline, matcher.group(2), span));
            validatePipeline(source, pipeline, start + raw.indexOf(pipeline), diagnostics);
            return;
        }
        String firstWord = statement.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        String message = switch (firstWord) {
            case "let" -> "A let statement needs a name, '=' and a pipeline.";
            case "use" -> "Expected use collection \"name\".";
            case "set" -> "A set statement needs a variable name and a value.";
            case "emit" -> "An emit statement needs a pipeline.";
            default -> "Expected use, set, let, or emit statement.";
        };
        diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQL002", message));
        statements.add(new ErrorStatement(span));
    }

    private void validatePipeline(String source, String pipeline, int offset, List<Diagnostic> diagnostics) {
        List<Part> parts = splitTopLevel(pipeline, "|>", offset);
        if (parts.isEmpty() || parts.get(0).text().trim().isEmpty()) {
            diagnostics.add(new Diagnostic(Span.of(source, offset, offset + pipeline.length()), Severity.ERROR,
                    "RQL003", "A pipeline needs a source before '|>'."));
            return;
        }
        for (int i = 1; i < parts.size(); i++) {
            String stage = parts.get(i).text().trim().toLowerCase(Locale.ROOT);
            if (stage.isEmpty()) {
                diagnostics.add(new Diagnostic(Span.of(source, parts.get(i).offset(), parts.get(i).offset()),
                        Severity.ERROR, "RQL004", "Expected a stage after '|>'."));
                continue;
            }
            String keyword = stage.split("\\s+", 2)[0];
            if (!ReportQueryService.stageKeywords().contains(keyword)) {
                diagnostics.add(new Diagnostic(Span.of(source, parts.get(i).offset(),
                        parts.get(i).offset() + parts.get(i).text().length()), Severity.ERROR, "RQL014",
                        "Unknown pipeline stage '" + keyword + "'."));
            }
        }
    }

    /** Splits on a delimiter only outside quoted strings and brackets. */
    public static List<Part> splitTopLevel(String text, String delimiter, int initialOffset) {
        List<Part> parts = new ArrayList<>();
        int start = 0;
        boolean quote = false;
        boolean escape = false;
        int depth = 0;
        for (int i = 0; i <= text.length() - delimiter.length(); i++) {
            char c = text.charAt(i);
            if (quote) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '\"') quote = false;
                continue;
            }
            if (c == '\"') {
                quote = true;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') depth++;
            if (c == ')' || c == ']' || c == '}') depth = Math.max(0, depth - 1);
            if (depth == 0 && text.startsWith(delimiter, i)) {
                parts.add(new Part(text.substring(start, i), initialOffset + start));
                i += delimiter.length() - 1;
                start = i + 1;
            }
        }
        parts.add(new Part(text.substring(start), initialOffset + start));
        return parts;
    }

    public record Part(String text, int offset) {
    }

    private static boolean atTopLevel(String text, int target) {
        boolean quote = false;
        boolean escape = false;
        int depth = 0;
        for (int i = 0; i < target; i++) {
            char c = text.charAt(i);
            if (quote) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '\"') quote = false;
            } else if (c == '\"') {
                quote = true;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth = Math.max(0, depth - 1);
            }
        }
        return !quote && depth == 0;
    }

    /** Replace line comments with spaces so source offsets remain correct. */
    private static String maskComments(String source) {
        StringBuilder clean = new StringBuilder(source);
        boolean quote = false;
        boolean escape = false;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (quote) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '\"') quote = false;
                continue;
            }
            if (c == '\"') {
                quote = true;
            } else if (c == '-' && i + 1 < clean.length() && clean.charAt(i + 1) == '-') {
                while (i < clean.length() && clean.charAt(i) != '\n') {
                    clean.setCharAt(i, ' ');
                    i++;
                }
            }
        }
        return clean.toString();
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
