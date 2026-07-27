package com.mcpserver.reports;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Shared, servlet-free values for the report query language. */
public final class RqlModel {

    private RqlModel() {
    }

    public enum Severity { ERROR, WARNING, INFO, HINT }

    /** Half-open source range; offsets are used by the live editor. */
    public record Span(int startOffset, int endOffset, int startLine, int startCol,
                       int endLine, int endCol) {
        public static Span of(String source, int start, int end) {
            String text = source == null ? "" : source;
            int safeStart = Math.max(0, Math.min(start, text.length()));
            int safeEnd = Math.max(safeStart, Math.min(end, text.length()));
            int startLine = 1;
            int startCol = 1;
            for (int i = 0; i < safeStart; i++) {
                if (text.charAt(i) == '\n') {
                    startLine++;
                    startCol = 1;
                } else {
                    startCol++;
                }
            }
            int endLine = startLine;
            int endCol = startCol;
            for (int i = safeStart; i < safeEnd; i++) {
                if (text.charAt(i) == '\n') {
                    endLine++;
                    endCol = 1;
                } else {
                    endCol++;
                }
            }
            return new Span(safeStart, safeEnd, startLine, startCol, endLine, endCol);
        }
    }

    public record Diagnostic(Span span, Severity severity, String code, String message) {
    }

    public record Completion(String label, String kind, String detail, String insertText, Span replaceSpan) {
    }

    public record Symbol(String name, String kind, Span span, List<String> schema) {
        public Symbol {
            schema = schema == null ? List.of() : List.copyOf(schema);
        }
    }

    /** Uniform dataset model: every source and every transform has the same row shape. */
    public record Dataset(String name, List<Map<String, Object>> rows) {
        public Dataset {
            List<Map<String, Object>> copiedRows = new ArrayList<>();
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    copiedRows.add(row == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(row)));
                }
            }
            rows = List.copyOf(copiedRows);
        }

        public List<String> columns() {
            LinkedHashSet<String> columns = new LinkedHashSet<>();
            for (Map<String, Object> row : rows) columns.addAll(row.keySet());
            return List.copyOf(columns);
        }

        public Map<String, String> schema() {
            LinkedHashMap<String, String> schema = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if (entry.getValue() != null && !schema.containsKey(entry.getKey())) {
                        schema.put(entry.getKey(), typeOf(entry.getValue()));
                    }
                }
            }
            for (String column : columns()) schema.putIfAbsent(column, "unknown");
            return schema;
        }

        private static String typeOf(Object value) {
            if (value instanceof Number) return "number";
            if (value instanceof Boolean) return "boolean";
            if (value instanceof Map<?, ?>) return "object";
            if (value instanceof List<?>) return "array";
            return "string";
        }
    }

    public record Analysis(List<Diagnostic> diagnostics, List<Completion> completions, List<Symbol> symbols) {
        public Analysis {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            completions = completions == null ? List.of() : List.copyOf(completions);
            symbols = symbols == null ? List.of() : List.copyOf(symbols);
        }
    }

    public record Execution(Map<String, Dataset> datasets, List<Diagnostic> diagnostics) {
        public Execution {
            datasets = datasets == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(datasets));
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        public boolean partial() {
            return diagnostics.stream().anyMatch(d -> d.severity() == Severity.WARNING || d.severity() == Severity.ERROR);
        }
    }

    public sealed interface Statement permits UseStatement, SetStatement, LetStatement, EmitStatement, ErrorStatement {
        Span span();
    }

    public record UseStatement(String collection, Span span) implements Statement {
    }

    public record SetStatement(String name, String value, Span span) implements Statement {
    }

    public record LetStatement(String name, String pipeline, Span span) implements Statement {
    }

    public record EmitStatement(String pipeline, String label, Span span) implements Statement {
    }

    public record ErrorStatement(Span span) implements Statement {
    }

    public record ParsedProgram(List<Statement> statements, List<Diagnostic> diagnostics) {
        public ParsedProgram {
            statements = statements == null ? List.of() : List.copyOf(statements);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }
}
