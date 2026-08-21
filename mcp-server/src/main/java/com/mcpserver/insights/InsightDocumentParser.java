package com.mcpserver.insights;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mcpserver.reports.RqlModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
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
    private static final Pattern TAG_START = Pattern.compile("<(/?)([A-Z][A-Za-z0-9]*)");
    private static final Pattern PROPERTY = Pattern.compile("([A-Za-z][A-Za-z0-9]*)\\s*=\\s*(?:\\{([^{}]*)}|\\\"([^\\\"]*)\\\")");
    private static final Set<String> SUPPORTED = Set.of(
            "Filter", "KpiRow", "Stat", "BarChart", "LineChart", "PieChart", "DataTable",
            // Summary blocks, ported from the report automation engine's Summary sheet.
            "Text", "KeyValue", "LabelValue", "QuickTable", "LabelTable", "Metrics", "Status");

    /**
     * Components that own a plotted axis; a filter nested inside one is rejected (RQI012). Every chart
     * takes the same {@code data}/{@code x}/{@code y} triple — a pie reads {@code x} as the slice label
     * rather than an axis, but keeping one prop vocabulary is what lets the design surface swap a
     * chart's type without rewriting its field bindings.
     */
    private static final Set<String> CHARTS = Set.of("BarChart", "LineChart", "PieChart");

    /**
     * The props each component reads. Anything else is silently inert, so it is reported (RQI014)
     * rather than accepted — {@code delta} and {@code format} appear in the design document's own
     * examples and did nothing, and a typo like {@code titel} was indistinguishable from a title.
     */
    private static final Map<String, Set<String>> KNOWN_PROPS = Map.ofEntries(
            Map.entry("Stat", Set.of("value", "label")),
            Map.entry("BarChart", Set.of("data", "x", "y", "title")),
            Map.entry("LineChart", Set.of("data", "x", "y", "title")),
            Map.entry("PieChart", Set.of("data", "x", "y", "title")),
            Map.entry("DataTable", Set.of("data", "title", "columns")),
            Map.entry("Text", Set.of("value")),
            Map.entry("KeyValue", Set.of("label", "value")),
            Map.entry("LabelValue", Set.of("label", "value")),
            Map.entry("QuickTable", Set.of("title", "headers", "rows")),
            Map.entry("LabelTable", Set.of("title", "headers", "rows")),
            Map.entry("Metrics", Set.of()),
            Map.entry("Status", Set.of()),
            Map.entry("KpiRow", Set.of()),
            Map.entry("Filter", Set.of("param")));

    /** Reported on their own, so they must not also be counted as unknown props. */
    private static final Set<String> REJECTED_PROPS = Set.of("y2", "color");

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public Document parse(String source) {
        String text = source == null ? "" : source;
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, Object> frontmatter = readFrontmatter(text, diagnostics);
        List<Parameter> parameters = parameters(frontmatter.get("params"));
        String title = string(frontmatter.get("title"));
        String connection = string(frontmatter.get("connection"));
        Scan scan = scan(text);
        List<Component> components = withProse(text, components(text, scan, diagnostics), scan.spans());
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
                markdown(text, scan.spans()), components, diagnostics);
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

    private List<Component> components(String source, Scan scan, List<Diagnostic> diagnostics) {
        List<Component> components = new ArrayList<>();
        for (Tag tag : scan.components()) {
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
            if (type.equals("Filter") && tag.parent() != null && CHARTS.contains(tag.parent())) {
                diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQI012",
                        "<Filter> cannot sit inside <" + tag.parent() + ">. One filter row scopes the "
                                + "whole document; per-chart filters are not supported."));
            } else if (type.equals("Filter")) {
                diagnostics.add(new Diagnostic(span, Severity.INFO, "RQI311",
                        "<Filter> renders nothing — parameter controls come from front-matter 'params'."));
            }
            Set<String> known = KNOWN_PROPS.get(type);
            if (known != null) {
                for (String prop : props.keySet()) {
                    if (known.contains(prop) || REJECTED_PROPS.contains(prop)) continue;
                    diagnostics.add(new Diagnostic(span, Severity.WARNING, "RQI014",
                            "<" + type + "> does not read '" + prop + "'; it has no effect."));
                }
            }
            if (CHARTS.contains(type) && (!props.containsKey("data") || !props.containsKey("x") || !props.containsKey("y"))) {
                diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQI020",
                        "<" + type + "> requires data, x, and y props."));
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

    private String markdown(String source, List<int[]> tagSpans) {
        boolean[] hidden = hiddenRegions(source, tagSpans);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            if (!hidden[i]) text.append(source.charAt(i));
        }
        return text.toString().trim();
    }

    /**
     * Everything that is not prose: front matter, every {@code ```rql} block, and every tag — opening
     * and closing alike, so a container's closer never leaks into the text around it.
     */
    private boolean[] hiddenRegions(String source, List<int[]> tagSpans) {
        boolean[] hidden = new boolean[source.length()];
        if (source.startsWith("---")) {
            int firstBreak = source.indexOf('\n');
            int end = firstBreak < 0 ? -1 : source.indexOf("\n---", firstBreak);
            if (end >= 0) {
                int close = source.indexOf('\n', end + 1);
                Arrays.fill(hidden, 0, close < 0 ? source.length() : Math.min(close + 1, source.length()), true);
            }
        }
        Matcher blocks = RQL_BLOCK.matcher(source);
        while (blocks.find()) Arrays.fill(hidden, blocks.start(), blocks.end(), true);
        for (int[] span : tagSpans) {
            Arrays.fill(hidden, Math.max(0, span[0]), Math.min(span[1], source.length()), true);
        }
        return hidden;
    }

    /**
     * Interleaves prose with components in document order, as {@code Prose} blocks. The renderer walks
     * this one ordered list, which is what lets a paragraph sit between two charts and stay there —
     * prose was previously parsed into a field nothing ever read, so every heading and sentence in a
     * document was silently dropped.
     */
    private List<Component> withProse(String source, List<Component> components, List<int[]> tagSpans) {
        boolean[] hidden = hiddenRegions(source, tagSpans);
        List<Component> ordered = new ArrayList<>();
        int cursor = 0;
        for (Component component : components) {
            addProse(source, hidden, cursor, component.span().startOffset(), ordered);
            ordered.add(component);
            cursor = Math.max(cursor, component.span().endOffset());
        }
        addProse(source, hidden, cursor, source.length(), ordered);
        return ordered;
    }

    private void addProse(String source, boolean[] hidden, int from, int to, List<Component> into) {
        StringBuilder text = new StringBuilder();
        for (int i = Math.max(0, from); i < Math.min(to, source.length()); i++) {
            if (!hidden[i]) text.append(source.charAt(i));
        }
        String prose = text.toString().strip();
        if (prose.isEmpty()) return;
        into.add(new Component("Prose", Map.of("value", prose), Span.of(source, from, Math.min(to, source.length()))));
    }

    /** {@code parent} is the enclosing component's type, or null at document level. */
    private record Tag(String type, String attributes, int start, int end, String parent) {
    }

    /**
     * Component tags plus every tag span — including closing tags, which are not components but must
     * still be cut out of the prose.
     */
    private record Scan(List<Tag> components, List<int[]> spans) {
    }

    /**
     * Scans component tags without a regex, because a prop value can legitimately contain '>' —
     * {@code value={if count(open) > 0 then "yes" else "no"}} is a conditional, not a closing angle.
     *
     * <p>Open/close tags are tracked on a stack so each component knows what encloses it. Without
     * that, containment rules cannot be expressed at all: nesting was previously invisible, so
     * {@code <BarChart>…<Filter/></BarChart>} parsed as two unrelated siblings.
     */
    private Scan scan(String source) {
        List<Tag> components = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        Deque<String> open = new ArrayDeque<>();
        Matcher matcher = TAG_START.matcher(source);
        while (matcher.find()) {
            boolean closing = !matcher.group(1).isEmpty();
            String type = matcher.group(2);
            int cursor = matcher.end();
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
            spans.add(new int[] { matcher.start(), cursor + 1 });
            if (closing) {
                // Guarded so a stray closer cannot unwind the whole stack.
                if (open.contains(type)) {
                    while (!open.isEmpty() && !open.pop().equals(type)) {
                        // discard unclosed inner tags
                    }
                }
            } else {
                String attributes = source.substring(matcher.end(), cursor);
                boolean selfClosing = attributes.endsWith("/");
                if (selfClosing) attributes = attributes.substring(0, attributes.length() - 1);
                components.add(new Tag(type, attributes, matcher.start(), cursor + 1, open.peek()));
                if (!selfClosing) open.push(type);
            }
            matcher.region(cursor + 1, source.length());
        }
        return new Scan(components, spans);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
