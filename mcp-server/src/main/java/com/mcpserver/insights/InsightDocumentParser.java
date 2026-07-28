package com.mcpserver.insights;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mcpserver.reports.RqlModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.mcpserver.insights.InsightModel.*;
import static com.mcpserver.reports.RqlModel.*;

/** Parses the intentionally small Markdown + RQL + safe component surface of an .rqd document. */
public class InsightDocumentParser {

    private static final Pattern RQL_BLOCK = Pattern.compile("(?is)```rql\\s*(.*?)```");
    private static final Pattern TAG_START = Pattern.compile("<([A-Z][A-Za-z0-9]*)");
    private static final Pattern PROPERTY = Pattern.compile("([A-Za-z][A-Za-z0-9]*)\\s*=\\s*(?:\\{([^{}]*)}|\\\"([^\\\"]*)\\\")");
    private static final Set<String> SUPPORTED = Set.of(
            "Filter", "KpiRow", "Stat", "BarChart", "DataTable",
            // Summary blocks, ported from the report automation engine's Summary sheet.
            "Text", "KeyValue", "LabelValue", "QuickTable", "LabelTable", "Metrics", "Status");

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public Document parse(String source) {
        String text = source == null ? "" : source;
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, Object> frontmatter = readFrontmatter(text, diagnostics);
        List<Parameter> parameters = parameters(frontmatter.get("params"));
        String title = string(frontmatter.get("title"));
        String connection = string(frontmatter.get("connection"));
        List<Component> components = components(text, diagnostics);
        StringBuilder rql = new StringBuilder();
        int rqlStartOffset = 0;
        boolean firstRqlBlock = true;
        Matcher blocks = RQL_BLOCK.matcher(text);
        while (blocks.find()) {
            if (firstRqlBlock) {
                rqlStartOffset = blocks.start(1);
                firstRqlBlock = false;
            }
            if (!rql.isEmpty()) rql.append('\n');
            rql.append(blocks.group(1));
        }
        return new Document(title, connection, parameters, rql.toString(), rqlStartOffset,
                markdown(text), components, diagnostics);
    }

    private Map<String, Object> readFrontmatter(String source, List<Diagnostic> diagnostics) {
        if (!source.startsWith("---")) return Map.of();
        int firstBreak = source.indexOf('\n');
        int end = firstBreak < 0 ? -1 : source.indexOf("\n---", firstBreak);
        if (end < 0) {
            diagnostics.add(new Diagnostic(Span.of(source, 0, Math.min(source.length(), 3)), Severity.ERROR, "RQI001",
                    "Front matter starts with '---' but has no closing delimiter."));
            return Map.of();
        }
        String raw = source.substring(firstBreak + 1, end);
        try {
            Map<String, Object> parsed = yaml.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() { });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception exception) {
            diagnostics.add(new Diagnostic(Span.of(source, firstBreak + 1, end), Severity.ERROR, "RQI002",
                    "Could not read insight front matter: " + message(exception)));
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Parameter> parameters(Object value) {
        if (!(value instanceof Map<?, ?> map)) return List.of();
        List<Parameter> parameters = new ArrayList<>();
        map.forEach((name, definition) -> {
            if (definition instanceof Map<?, ?> definitionMap) {
                parameters.add(new Parameter(String.valueOf(name), string(definitionMap.get("type")),
                        definitionMap.get("default")));
            } else {
                parameters.add(new Parameter(String.valueOf(name), "string", definition));
            }
        });
        return parameters;
    }

    private List<Component> components(String source, List<Diagnostic> diagnostics) {
        List<Component> components = new ArrayList<>();
        for (Tag tag : tags(source)) {
            String type = tag.type();
            Map<String, String> props = new LinkedHashMap<>();
            Matcher propsMatcher = PROPERTY.matcher(tag.attributes());
            while (propsMatcher.find()) {
                props.put(propsMatcher.group(1), propsMatcher.group(2) == null ? propsMatcher.group(3) : propsMatcher.group(2));
            }
            Span span = Span.of(source, tag.start(), tag.end());
            if (!SUPPORTED.contains(type)) {
                diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQI010",
                        "Unknown insight component <" + type + "/>."));
            }
            if (props.containsKey("y2")) {
                diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQI011",
                        "Insights do not support a dual y-axis. Use two charts instead."));
            }
            if (props.containsKey("color")) {
                diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQI013",
                        "Series colors are assigned by the insight palette; 'color' is not supported."));
            }
            if (type.equals("BarChart") && (!props.containsKey("data") || !props.containsKey("x") || !props.containsKey("y"))) {
                diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQI020",
                        "<BarChart> requires data, x, and y props."));
            }
            if (type.equals("DataTable") && !props.containsKey("data")) {
                diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQI021",
                        "<DataTable> requires a data prop."));
            }
            if (type.equals("Stat") && !props.containsKey("value")) {
                diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQI022",
                        "<Stat> requires a value expression."));
            }
            if ((type.equals("KeyValue") || type.equals("LabelValue")) && !props.containsKey("value")) {
                diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQI023",
                        "<" + type + "> requires label and value props."));
            }
            if (type.equals("Text") && !props.containsKey("value")) {
                diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQI024",
                        "<Text> requires a value expression."));
            }
            if ((type.equals("QuickTable") || type.equals("LabelTable")) && !props.containsKey("rows")) {
                diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQI025",
                        "<" + type + "> requires rows, e.g. rows={[[\"Total\", count(orders)]]}."));
            }
            components.add(new Component(type, props, span));
        }
        return components;
    }

    private String markdown(String source) {
        StringBuilder text = new StringBuilder(source);
        List<Tag> tags = tags(source);
        for (int i = tags.size() - 1; i >= 0; i--) {
            text.replace(tags.get(i).start(), tags.get(i).end(), "");
        }
        String withoutFrontmatter = text.toString().replaceFirst("(?s)^---.*?\\n---\\s*", "");
        return RQL_BLOCK.matcher(withoutFrontmatter).replaceAll("").trim();
    }

    private record Tag(String type, String attributes, int start, int end) {
    }

    /**
     * Scans component tags without a regex, because a prop value can legitimately contain '>' —
     * {@code value={if count(open) > 0 then "yes" else "no"}} is a conditional, not a closing angle.
     */
    private List<Tag> tags(String source) {
        List<Tag> tags = new ArrayList<>();
        Matcher opening = TAG_START.matcher(source);
        while (opening.find()) {
            int cursor = opening.end();
            int depth = 0;
            boolean quote = false;
            char quoteChar = '"';
            while (cursor < source.length()) {
                char c = source.charAt(cursor);
                if (quote) {
                    if (c == quoteChar) quote = false;
                } else if (c == '"' || c == '\'') {
                    quote = true;
                    quoteChar = c;
                } else if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                } else if (c == '>' && depth <= 0) {
                    break;
                }
                cursor++;
            }
            if (cursor >= source.length()) break;
            String attributes = source.substring(opening.end(), cursor);
            if (attributes.endsWith("/")) attributes = attributes.substring(0, attributes.length() - 1);
            tags.add(new Tag(opening.group(1), attributes, opening.start(), cursor + 1));
            opening.region(cursor + 1, source.length());
        }
        return tags;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
