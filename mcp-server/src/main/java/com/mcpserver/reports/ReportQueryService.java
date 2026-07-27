package com.mcpserver.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.cache.CacheService;
import com.mcpserver.connectors.Connection;
import com.mcpserver.connectors.ConnectionService;
import com.mcpserver.tools.ApiTool;
import com.mcpserver.tools.ApiToolExecutor;
import com.mcpserver.tools.ApiToolService;
import com.mcpserver.tools.ToolInvocationResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.mcpserver.reports.RqlModel.*;

/**
 * Analyzes and runs the useful, data-safe RQL subset over imported read tools. HTTP is always
 * delegated to {@link ApiToolExecutor}; reports never acquire credentials or open connections.
 */
@Service
public class ReportQueryService {

    private static final Pattern REQUEST = Pattern.compile("(?is)\\brequest\\s+\\\"([^\\\"]+)\\\"");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> STAGE_KEYWORDS = Set.of(
            "where", "select", "order", "limit", "offset", "distinct", "group", "expand", "join",
            "lookup", "rename", "parse");

    private final ApiToolService apiToolService;
    private final ConnectionService connectionService;
    private final ApiToolExecutor apiToolExecutor;
    private final CacheService cacheService;
    private final RqlParser parser;
    private final ObjectMapper mapper;

    public ReportQueryService(ApiToolService apiToolService, ConnectionService connectionService,
                              ApiToolExecutor apiToolExecutor, CacheService cacheService) {
        this.apiToolService = apiToolService;
        this.connectionService = connectionService;
        this.apiToolExecutor = apiToolExecutor;
        this.cacheService = cacheService;
        this.parser = new RqlParser();
        this.mapper = new ObjectMapper();
    }

    /** Fast, network-free editor analysis. */
    public Analysis analyze(String source, String connectionId, Integer cursorOffset) {
        ParsedProgram program = parser.parse(source);
        List<Diagnostic> diagnostics = new ArrayList<>(program.diagnostics());
        List<ApiTool> tools = toolsForAnalysis(connectionId, diagnostics, source);
        LinkedHashSet<String> bindings = new LinkedHashSet<>();
        List<Symbol> symbols = new ArrayList<>();
        String collection = null;

        for (Statement statement : program.statements()) {
            if (statement instanceof UseStatement use) {
                collection = use.collection();
            } else if (statement instanceof LetStatement let) {
                checkPipeline(let.pipeline(), let.span(), tools, bindings, collection, diagnostics, source);
                bindings.add(let.name());
                symbols.add(new Symbol(let.name(), "LET", let.span(), List.of()));
            } else if (statement instanceof SetStatement set) {
                bindings.add("$" + set.name());
                symbols.add(new Symbol(set.name(), "PARAM", set.span(), List.of()));
            }
        }
        return new Analysis(diagnostics, completions(source, cursorOffset, tools, bindings), symbols);
    }

    /** Executes valid lets independently where possible; an unavailable request becomes an empty dataset. */
    public Execution execute(String source, String connectionId, Map<String, Object> inputParameters) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("Choose a connected API collection before running a query");
        }
        Connection connection = connectionService.findById(connectionId);
        ParsedProgram program = parser.parse(source);
        List<Diagnostic> diagnostics = new ArrayList<>(program.diagnostics());
        List<ApiTool> tools = apiToolService.findByConnectionId(connectionId);
        Map<String, Object> variables = new LinkedHashMap<>();
        if (inputParameters != null) variables.putAll(inputParameters);
        Map<String, Dataset> datasets = new LinkedHashMap<>();

        for (Statement statement : program.statements()) {
            if (statement instanceof SetStatement set) {
                variables.put(set.name(), literal(set.value(), variables));
            } else if (statement instanceof LetStatement let) {
                Dataset dataset = evaluatePipeline(let.pipeline(), let.name(), connection, tools, datasets,
                        variables, diagnostics, let.span());
                datasets.put(let.name(), dataset);
            } else if (statement instanceof EmitStatement emit) {
                Dataset dataset = evaluatePipeline(emit.pipeline(), emit.label() == null ? "result" : emit.label(),
                        connection, tools, datasets, variables, diagnostics, emit.span());
                String name = emit.label() == null || emit.label().isBlank() ? "result" : emit.label();
                datasets.putIfAbsent(name, dataset);
            }
        }
        return new Execution(datasets, diagnostics);
    }

    private List<ApiTool> toolsForAnalysis(String connectionId, List<Diagnostic> diagnostics, String source) {
        if (connectionId == null || connectionId.isBlank()) return List.of();
        try {
            return apiToolService.findByConnectionId(connectionId);
        } catch (RuntimeException exception) {
            diagnostics.add(new Diagnostic(Span.of(source, 0, 0), Severity.HINT, "RQL105",
                    "Choose a valid API collection to validate request names."));
            return List.of();
        }
    }

    private void checkPipeline(String pipeline, Span span, List<ApiTool> tools, Set<String> bindings,
                               String collection, List<Diagnostic> diagnostics, String source) {
        Matcher request = REQUEST.matcher(pipeline);
        while (request.find()) {
            String name = request.group(1);
            ApiTool tool = findTool(tools, name);
            if (tool == null) {
                String scope = collection == null ? "this collection" : "collection \"" + collection + "\"";
                diagnostics.add(new Diagnostic(Span.of(source, span.startOffset() + request.start(1),
                        span.startOffset() + request.end(1)), Severity.ERROR, "RQL101",
                        "Unknown request \"" + name + "\" in " + scope + "."));
            } else if (!tool.enabled()) {
                diagnostics.add(new Diagnostic(span, Severity.WARNING, "RQL102",
                        "Request \"" + name + "\" is disabled and will return no rows."));
            } else if (!tool.isRead()) {
                diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQL104",
                        "Request \"" + name + "\" changes data and cannot be used in a dashboard."));
            }
        }
        List<RqlParser.Part> parts = RqlParser.splitTopLevel(pipeline, "|>", span.startOffset());
        if (!parts.isEmpty()) {
            String sourcePart = parts.get(0).text().trim();
            if (IDENT.matcher(sourcePart).matches() && !bindings.contains(sourcePart)) {
                diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQL103",
                        "Unknown dataset \"" + sourcePart + "\". Define it with let before using it."));
            }
        }
    }

    private List<Completion> completions(String source, Integer cursorOffset, List<ApiTool> tools,
                                         Set<String> bindings) {
        if (cursorOffset == null) return List.of();
        int cursor = Math.max(0, Math.min(cursorOffset, source == null ? 0 : source.length()));
        String before = source == null ? "" : source.substring(0, cursor);
        Span replacement = Span.of(source, cursor, cursor);
        List<Completion> completions = new ArrayList<>();
        if (before.matches("(?s).*\\|>\\s*[A-Za-z_]*$")) {
            for (String stage : List.of("where", "select", "group by", "order by", "limit", "offset", "distinct",
                    "expand", "rename", "parse date")) {
                completions.add(new Completion(stage, "STAGE", "RQL pipeline stage", stage, replacement));
            }
        } else if (before.matches("(?s).*\\brequest\\s+\\\"[^\\\"]*$")) {
            for (ApiTool tool : tools) {
                if (tool.enabled() && tool.isRead()) {
                    completions.add(new Completion(tool.displayName(), "REQUEST", tool.httpMethod() + " " + tool.urlTemplate(),
                            tool.displayName(), replacement));
                }
            }
        } else {
            for (String binding : bindings) {
                if (!binding.startsWith("$")) {
                    completions.add(new Completion(binding, "DATASET", "RQL dataset", binding, replacement));
                }
            }
        }
        return completions;
    }

    private Dataset evaluatePipeline(String expression, String name, Connection connection, List<ApiTool> tools,
                                     Map<String, Dataset> datasets, Map<String, Object> variables,
                                     List<Diagnostic> diagnostics, Span span) {
        List<RqlParser.Part> parts = RqlParser.splitTopLevel(expression, "|>", span.startOffset());
        Dataset current = evaluateSource(parts.isEmpty() ? "" : parts.get(0).text().trim(), name, connection, tools,
                datasets, variables, diagnostics, span);
        for (int i = 1; i < parts.size(); i++) {
            String stage = parts.get(i).text().trim();
            current = applyStage(current, stage, connection, tools, datasets, variables, diagnostics, span);
        }
        return new Dataset(name, current.rows());
    }

    private Dataset evaluateSource(String source, String name, Connection connection, List<ApiTool> tools,
                                   Map<String, Dataset> datasets, Map<String, Object> variables,
                                   List<Diagnostic> diagnostics, Span span) {
        Matcher request = Pattern.compile("(?is)^request\\s+\\\"([^\\\"]+)\\\"(?:\\s+with\\s+\\{(.*)})?$").matcher(source);
        if (request.matches()) {
            ApiTool tool = findTool(tools, request.group(1));
            if (tool == null) {
                diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQL101", "Unknown request \"" + request.group(1) + "\"."));
                return new Dataset(name, List.of());
            }
            if (!tool.enabled() || !tool.isRead()) {
                diagnostics.add(new Diagnostic(span, tool.isRead() ? Severity.WARNING : Severity.ERROR,
                        tool.isRead() ? "RQL102" : "RQL104", tool.isRead()
                        ? "Request \"" + request.group(1) + "\" is disabled." : "Only read requests can be queried."));
                return new Dataset(name, List.of());
            }
            return runRequest(name, tool, connection, arguments(request.group(2), variables), diagnostics, span);
        }
        Matcher combinator = Pattern.compile("(?is)^(union(?:\\s+all)?|intersect|except|diff)\\s*\\[(.*)]$").matcher(source);
        if (combinator.matches()) {
            List<Dataset> inputs = new ArrayList<>();
            for (RqlParser.Part part : RqlParser.splitTopLevel(combinator.group(2), ",", span.startOffset())) {
                inputs.add(evaluatePipeline(part.text().trim(), name, connection, tools, datasets, variables, diagnostics, span));
            }
            return combine(name, combinator.group(1).toLowerCase(Locale.ROOT), inputs);
        }
        if (IDENT.matcher(source).matches()) {
            Dataset reference = datasets.get(source);
            if (reference != null) return new Dataset(name, reference.rows());
            diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQL103", "Unknown dataset \"" + source + "\"."));
            return new Dataset(name, List.of());
        }
        diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQL005",
                "Expected request \"name\", a prior dataset, or a dataset combinator."));
        return new Dataset(name, List.of());
    }

    private Dataset runRequest(String name, ApiTool tool, Connection connection, Map<String, Object> arguments,
                               List<Diagnostic> diagnostics, Span span) {
        String key = CacheService.toolCacheKey(tool.id(), arguments);
        Object cached = cacheService.getToolResponse(key).orElse(null);
        if (cached instanceof Dataset dataset) return new Dataset(name, dataset.rows());
        try {
            ToolInvocationResult result = apiToolExecutor.execute(tool, connection, arguments);
            if (result.status() >= 400) {
                diagnostics.add(new Diagnostic(span, Severity.WARNING, "RQL201", "Request \"" + tool.displayName()
                        + "\" returned HTTP " + result.status() + "."));
                return new Dataset(name, List.of());
            }
            Dataset dataset = new Dataset(name, rowsFromJson(result.body()));
            cacheService.putToolResponse(key, dataset);
            return dataset;
        } catch (Exception exception) {
            diagnostics.add(new Diagnostic(span, Severity.WARNING, "RQL201", "Request \"" + tool.displayName()
                    + "\" could not run: " + safeMessage(exception)));
            return new Dataset(name, List.of());
        }
    }

    private List<Map<String, Object>> rowsFromJson(String body) throws Exception {
        JsonNode root = mapper.readTree(body == null || body.isBlank() ? "null" : body);
        if (root == null || root.isNull()) return List.of();
        JsonNode rows = root;
        if (root.isObject()) {
            for (String candidate : List.of("items", "data", "results")) {
                if (root.path(candidate).isArray()) {
                    rows = root.path(candidate);
                    break;
                }
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        if (rows.isArray()) {
            for (JsonNode item : rows) result.add(rowFromNode(item));
        } else {
            result.add(rowFromNode(rows));
        }
        return result;
    }

    private Map<String, Object> rowFromNode(JsonNode node) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> row.put(entry.getKey(), javaValue(entry.getValue())));
        } else {
            row.put("value", javaValue(node));
        }
        return row;
    }

    private Object javaValue(JsonNode node) {
        if (node.isNull()) return null;
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber()) return node.longValue();
        if (node.isFloatingPointNumber()) return node.decimalValue();
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(value -> values.add(javaValue(value)));
            return values;
        }
        if (node.isObject()) return rowFromNode(node);
        return node.asText();
    }

    private Dataset applyStage(Dataset input, String stage, Connection connection, List<ApiTool> tools,
                               Map<String, Dataset> datasets, Map<String, Object> variables,
                               List<Diagnostic> diagnostics, Span span) {
        String lower = stage.toLowerCase(Locale.ROOT);
        try {
            if (lower.startsWith("where ")) return where(input, stage.substring(6), variables);
            if (lower.startsWith("select ")) return select(input, stage.substring(7), variables);
            if (lower.startsWith("order by ")) return order(input, stage.substring(9));
            if (lower.startsWith("limit ")) return slice(input, integer(stage.substring(6), variables, input.rows().size()), 0);
            if (lower.startsWith("offset ")) return slice(input, input.rows().size(), integer(stage.substring(7), variables, 0));
            if (lower.equals("distinct") || lower.startsWith("distinct ")) return distinct(input, stage.substring(Math.min(8, stage.length())).trim());
            if (lower.startsWith("group by ")) return group(input, stage.substring(9), variables);
            if (lower.startsWith("expand ")) return expand(input, stage.substring(7));
            if (lower.startsWith("rename ")) return rename(input, stage.substring(7));
            if (lower.startsWith("parse date ")) return parseDate(input, stage.substring(11), diagnostics, span);
            if (lower.startsWith("join ") || lower.startsWith("lookup ")) {
                diagnostics.add(new Diagnostic(span, Severity.WARNING, "RQL207",
                        "Join and lookup are recognized but are not executable in the first dashboard slice."));
                return input;
            }
            diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQL014", "Unknown pipeline stage."));
        } catch (RuntimeException exception) {
            diagnostics.add(new Diagnostic(span, Severity.WARNING, "RQL202",
                    "Could not apply '" + stage.split("\\s+", 2)[0] + "': " + safeMessage(exception)));
        }
        return input;
    }

    private Dataset where(Dataset input, String expression, Map<String, Object> variables) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : input.rows()) if (matches(row, expression.trim(), variables)) rows.add(row);
        return new Dataset(input.name(), rows);
    }

    private boolean matches(Map<String, Object> row, String expression, Map<String, Object> variables) {
        String normalized = trimParens(expression.trim());
        List<String> orParts = splitKeyword(normalized, "or");
        if (orParts.size() > 1) return orParts.stream().anyMatch(part -> matches(row, part, variables));
        Matcher between = Pattern.compile("(?is)^(.+?)\\s+between\\s+(.+?)\\s+and\\s+(.+)$").matcher(normalized);
        if (between.matches()) {
            Object actual = value(row, between.group(1), variables);
            return compare(actual, value(row, between.group(2), variables)) >= 0
                    && compare(actual, value(row, between.group(3), variables)) <= 0;
        }
        List<String> andParts = splitKeyword(normalized, "and");
        if (andParts.size() > 1) return andParts.stream().allMatch(part -> matches(row, part, variables));
        if (normalized.toLowerCase(Locale.ROOT).startsWith("not ")) return !matches(row, normalized.substring(4), variables);

        Matcher is = Pattern.compile("(?is)^(.+?)\\s+is\\s+(not\\s+)?(null|true|false)$").matcher(normalized);
        if (is.matches()) {
            Object actual = value(row, is.group(1), variables);
            Object expected = literal(is.group(3), variables);
            boolean equal = Objects.equals(normalizeScalar(actual), normalizeScalar(expected));
            return is.group(2) == null ? equal : !equal;
        }
        Matcher in = Pattern.compile("(?is)^(.+?)\\s+(not\\s+)?in\\s*\\((.*)\\)$").matcher(normalized);
        if (in.matches()) {
            Object actual = value(row, in.group(1), variables);
            boolean found = RqlParser.splitTopLevel(in.group(3), ",", 0).stream()
                    .map(part -> value(row, part.text(), variables)).anyMatch(item -> compare(actual, item) == 0);
            return in.group(2) == null ? found : !found;
        }
        Matcher text = Pattern.compile("(?is)^(.+?)\\s+(not\\s+)?(like|ilike|contains|starts\\s+with|ends\\s+with|regex)\\s+(.+)$").matcher(normalized);
        if (text.matches()) {
            String actual = String.valueOf(value(row, text.group(1), variables));
            String operand = String.valueOf(value(row, text.group(4), variables));
            String op = text.group(3).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            boolean result = switch (op) {
                case "contains" -> actual.contains(operand);
                case "starts with" -> actual.startsWith(operand);
                case "ends with" -> actual.endsWith(operand);
                case "regex" -> actual.matches(operand);
                case "ilike" -> actual.toLowerCase(Locale.ROOT)
                        .matches(sqlPattern(operand.toLowerCase(Locale.ROOT)));
                default -> actual.matches(sqlPattern(operand));
            };
            return text.group(2) == null ? result : !result;
        }
        Matcher comparison = Pattern.compile("(?is)^(.+?)\\s*(>=|<=|!=|=|>|<)\\s*(.+)$").matcher(normalized);
        if (comparison.matches()) {
            int result = compare(value(row, comparison.group(1), variables), value(row, comparison.group(3), variables));
            return switch (comparison.group(2)) {
                case "=" -> result == 0;
                case "!=" -> result != 0;
                case ">" -> result > 0;
                case ">=" -> result >= 0;
                case "<" -> result < 0;
                case "<=" -> result <= 0;
                default -> false;
            };
        }
        Object bare = value(row, normalized, variables);
        return bare instanceof Boolean bool && bool;
    }

    private Dataset select(Dataset input, String projections, Map<String, Object> variables) {
        List<RqlParser.Part> parts = RqlParser.splitTopLevel(projections, ",", 0);
        List<Map<String, Object>> output = new ArrayList<>();
        for (Map<String, Object> row : input.rows()) {
            LinkedHashMap<String, Object> next = new LinkedHashMap<>();
            for (RqlParser.Part part : parts) {
                String projection = part.text().trim();
                if (projection.equals("*")) {
                    next.putAll(row);
                    continue;
                }
                Matcher alias = Pattern.compile("(?is)^(.+?)\\s+as\\s+(?:\\\"([^\\\"]+)\\\"|([A-Za-z_][A-Za-z0-9_]*))$").matcher(projection);
                String expression = alias.matches() ? alias.group(1).trim() : projection;
                String key = alias.matches() ? (alias.group(2) == null ? alias.group(3) : alias.group(2)) : fieldName(expression);
                next.put(key, value(row, expression, variables));
            }
            output.add(next);
        }
        return new Dataset(input.name(), output);
    }

    private Dataset order(Dataset input, String spec) {
        List<SortKey> keys = new ArrayList<>();
        for (RqlParser.Part part : RqlParser.splitTopLevel(spec, ",", 0)) {
            String[] tokens = part.text().trim().split("\\s+");
            if (tokens.length == 0 || tokens[0].isBlank()) continue;
            keys.add(new SortKey(tokens[0], tokens.length > 1 && tokens[1].equalsIgnoreCase("desc")));
        }
        List<Map<String, Object>> rows = new ArrayList<>(input.rows());
        rows.sort((left, right) -> {
            for (SortKey key : keys) {
                int comparison = compare(field(left, key.field()), field(right, key.field()));
                if (comparison != 0) return key.descending() ? -comparison : comparison;
            }
            return 0;
        });
        return new Dataset(input.name(), rows);
    }

    private Dataset slice(Dataset input, int limit, int offset) {
        int start = Math.max(0, Math.min(offset, input.rows().size()));
        int end = Math.max(start, Math.min(input.rows().size(), start + Math.max(0, limit)));
        return new Dataset(input.name(), input.rows().subList(start, end));
    }

    private Dataset distinct(Dataset input, String fieldSpec) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> fields = fieldSpec.isBlank() ? List.of() : RqlParser.splitTopLevel(fieldSpec.replaceFirst("(?is)^by\\s+", ""), ",", 0)
                .stream().map(part -> part.text().trim()).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : input.rows()) {
            Object marker = fields.isEmpty() ? row : fields.stream().map(field -> field(row, field)).toList();
            if (seen.add(String.valueOf(marker))) rows.add(row);
        }
        return new Dataset(input.name(), rows);
    }

    private Dataset group(Dataset input, String spec, Map<String, Object> variables) {
        int aggIndex = indexOfKeyword(spec, "agg");
        int havingIndex = indexOfKeyword(spec, "having");
        int fieldsEnd = minPositive(aggIndex, havingIndex, spec.length());
        List<String> fields = RqlParser.splitTopLevel(spec.substring(0, fieldsEnd), ",", 0).stream()
                .map(part -> part.text().trim()).filter(field -> !field.isBlank()).toList();
        String aggregates = aggIndex < 0 ? "" : spec.substring(aggIndex + 3, havingIndex > aggIndex ? havingIndex : spec.length()).trim();
        String having = havingIndex < 0 ? null : spec.substring(havingIndex + 6).trim();
        Map<List<Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : input.rows()) {
            List<Object> key = fields.stream().map(field -> field(row, field)).toList();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        List<Aggregate> aggregateList = aggregates.isBlank() ? List.of() : parseAggregates(aggregates);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Map<String, Object>>> entry : groups.entrySet()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < fields.size(); i++) row.put(fieldName(fields.get(i)), entry.getKey().get(i));
            for (Aggregate aggregate : aggregateList) row.put(aggregate.alias(), aggregate(aggregate, entry.getValue()));
            if (having == null || matches(row, having, variables)) rows.add(row);
        }
        return new Dataset(input.name(), rows);
    }

    private List<Aggregate> parseAggregates(String source) {
        List<Aggregate> result = new ArrayList<>();
        Pattern pattern = Pattern.compile("(?is)^(count|sum|avg|min|max)\\s*\\(\\s*(\\*|[^)]+)\\s*\\)(?:\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*))?$");
        for (RqlParser.Part part : RqlParser.splitTopLevel(source, ",", 0)) {
            Matcher matcher = pattern.matcher(part.text().trim());
            if (!matcher.matches()) continue;
            String operation = matcher.group(1).toLowerCase(Locale.ROOT);
            String field = matcher.group(2).trim();
            String alias = matcher.group(3) == null ? operation + ("*".equals(field) ? "" : "_" + fieldName(field)) : matcher.group(3);
            result.add(new Aggregate(operation, field, alias));
        }
        return result;
    }

    private Object aggregate(Aggregate aggregate, List<Map<String, Object>> rows) {
        if (aggregate.operation().equals("count")) {
            if (aggregate.field().equals("*")) return (long) rows.size();
            return rows.stream().map(row -> field(row, aggregate.field())).filter(Objects::nonNull).count();
        }
        List<BigDecimal> values = rows.stream().map(row -> number(field(row, aggregate.field())))
                .filter(Objects::nonNull).toList();
        if (values.isEmpty()) return null;
        return switch (aggregate.operation()) {
            case "sum" -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case "avg" -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(values.size()), java.math.MathContext.DECIMAL64);
            case "min" -> values.stream().min(Comparator.naturalOrder()).orElse(null);
            case "max" -> values.stream().max(Comparator.naturalOrder()).orElse(null);
            default -> null;
        };
    }

    private Dataset expand(Dataset input, String spec) {
        Matcher matcher = Pattern.compile("(?is)^(.+?)(?:\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*))?$").matcher(spec.trim());
        if (!matcher.matches()) return input;
        String field = matcher.group(1).trim();
        String alias = matcher.group(2) == null ? fieldName(field) : matcher.group(2);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : input.rows()) {
            Object value = field(row, field);
            if (value instanceof Collection<?> values) {
                for (Object item : values) {
                    LinkedHashMap<String, Object> expanded = new LinkedHashMap<>(row);
                    expanded.put(alias, item);
                    rows.add(expanded);
                }
            } else {
                rows.add(row);
            }
        }
        return new Dataset(input.name(), rows);
    }

    private Dataset rename(Dataset input, String spec) {
        Map<String, String> names = new LinkedHashMap<>();
        for (RqlParser.Part part : RqlParser.splitTopLevel(spec, ",", 0)) {
            Matcher matcher = Pattern.compile("(?is)^(.+?)\\s+as\\s+\\\"([^\\\"]+)\\\"$").matcher(part.text().trim());
            if (matcher.matches()) names.put(fieldName(matcher.group(1)), matcher.group(2));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : input.rows()) {
            LinkedHashMap<String, Object> renamed = new LinkedHashMap<>();
            row.forEach((key, value) -> renamed.put(names.getOrDefault(key, key), value));
            rows.add(renamed);
        }
        return new Dataset(input.name(), rows);
    }

    /** Dates already arrive in JSON-friendly ISO strings. Preserve the value and flag malformed directives. */
    private Dataset parseDate(Dataset input, String spec, List<Diagnostic> diagnostics, Span span) {
        if (!Pattern.compile("(?is)^.+?\\s+format\\s+\\\"[^\\\"]+\\\"(?:\\s+timezone\\s+\\\"[^\\\"]+\\\")?$").matcher(spec.trim()).matches()) {
            diagnostics.add(new Diagnostic(span, Severity.WARNING, "RQL203", "Expected parse date field format \"pattern\"."));
        }
        return input;
    }

    private Dataset combine(String name, String operation, List<Dataset> inputs) {
        if (inputs.isEmpty()) return new Dataset(name, List.of());
        if (operation.startsWith("union")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            inputs.forEach(dataset -> rows.addAll(dataset.rows()));
            return operation.equals("union") ? distinct(new Dataset(name, rows), "") : new Dataset(name, rows);
        }
        Set<String> candidate = fingerprints(inputs.get(0).rows());
        for (int i = 1; i < inputs.size(); i++) {
            Set<String> next = fingerprints(inputs.get(i).rows());
            if (operation.equals("intersect")) candidate.retainAll(next);
            else candidate.removeAll(next);
        }
        List<Map<String, Object>> rows = inputs.get(0).rows().stream().filter(row -> candidate.contains(String.valueOf(row))).toList();
        return new Dataset(name, rows);
    }

    private Set<String> fingerprints(List<Map<String, Object>> rows) {
        Set<String> set = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) set.add(String.valueOf(row));
        return set;
    }

    private ApiTool findTool(List<ApiTool> tools, String requestName) {
        return tools.stream().filter(tool -> tool.displayName().equalsIgnoreCase(requestName)
                        || tool.name().equalsIgnoreCase(requestName) || tool.requestSlug().equalsIgnoreCase(requestName))
                .findFirst().orElse(null);
    }

    private Map<String, Object> arguments(String raw, Map<String, Object> variables) {
        if (raw == null || raw.isBlank()) return Map.of();
        Map<String, Object> args = new LinkedHashMap<>();
        for (RqlParser.Part part : RqlParser.splitTopLevel(raw, ",", 0)) {
            String[] pair = part.text().split(":|=", 2);
            if (pair.length == 2) args.put(pair[0].trim().replaceAll("^\\\"|\\\"$", ""), literal(pair[1].trim(), variables));
        }
        return args;
    }

    private Object value(Map<String, Object> row, String operand, Map<String, Object> variables) {
        String trimmed = trimParens(operand.trim());
        if (trimmed.startsWith("$")) return variables.get(trimmed.substring(1));
        if (isLiteral(trimmed)) return literal(trimmed, variables);
        return field(row, trimmed);
    }

    private Object literal(String source, Map<String, Object> variables) {
        String value = source == null ? "" : source.trim();
        if (value.startsWith("$")) return variables.get(value.substring(1));
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) return value.substring(1, value.length() - 1).replace("\\\"", "\"");
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) return Boolean.parseBoolean(value);
        if (value.equalsIgnoreCase("null")) return null;
        try { return new BigDecimal(value); } catch (NumberFormatException ignored) { return value; }
    }

    private boolean isLiteral(String value) {
        return value.startsWith("\"") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")
                || value.equalsIgnoreCase("null") || value.startsWith("$") || value.matches("-?\\d+(?:\\.\\d+)?");
    }

    @SuppressWarnings("unchecked")
    private Object field(Map<String, Object> row, String path) {
        String normalized = path.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) normalized = normalized.substring(1, normalized.length() - 1)
                .replaceAll("^\\\"|\\\"$", "");
        Object current = row;
        for (String part : normalized.split("\\.")) {
            if (current instanceof Map<?, ?> map) current = ((Map<String, Object>) map).get(part);
            else return null;
        }
        return current;
    }

    private String fieldName(String field) {
        String normalized = field.trim().replaceAll("^\\[\\\"?|\\\"?]$", "");
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    private static int compare(Object left, Object right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        BigDecimal leftNumber = number(left);
        BigDecimal rightNumber = number(right);
        if (leftNumber != null && rightNumber != null) return leftNumber.compareTo(rightNumber);
        return String.valueOf(normalizeScalar(left)).compareTo(String.valueOf(normalizeScalar(right)));
    }

    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        try { return new BigDecimal(String.valueOf(value)); } catch (NumberFormatException ignored) { return null; }
    }

    private static Object normalizeScalar(Object value) {
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros();
        return value;
    }

    private int integer(String raw, Map<String, Object> variables, int fallback) {
        Object value = literal(raw, variables);
        BigDecimal number = number(value);
        return number == null ? fallback : number.intValue();
    }

    private static List<String> splitKeyword(String source, String keyword) {
        List<String> parts = new ArrayList<>();
        boolean quote = false;
        int depth = 0;
        int start = 0;
        String needle = " " + keyword.toLowerCase(Locale.ROOT) + " ";
        for (int i = 0; i <= source.length() - needle.length(); i++) {
            char c = source.charAt(i);
            if (c == '\"') quote = !quote;
            if (!quote && (c == '(' || c == '[')) depth++;
            if (!quote && (c == ')' || c == ']')) depth--;
            if (!quote && depth == 0 && source.regionMatches(true, i, needle, 0, needle.length())) {
                parts.add(source.substring(start, i));
                start = i + needle.length();
            }
        }
        if (parts.isEmpty()) return List.of(source);
        parts.add(source.substring(start));
        return parts;
    }

    private static int indexOfKeyword(String source, String keyword) {
        String lower = source.toLowerCase(Locale.ROOT);
        String needle = " " + keyword.toLowerCase(Locale.ROOT) + " ";
        int match = lower.indexOf(needle);
        // Return the keyword itself, rather than the preceding separator. Callers can then slice
        // after its actual length without retaining the final character of "agg"/"having".
        return match < 0 ? -1 : match + 1;
    }

    private static int minPositive(int first, int second, int fallback) {
        int out = fallback;
        if (first >= 0) out = Math.min(out, first);
        if (second >= 0) out = Math.min(out, second);
        return out;
    }

    private static String trimParens(String value) {
        String result = value;
        while (result.startsWith("(") && result.endsWith(")")) result = result.substring(1, result.length() - 1).trim();
        return result;
    }

    private static String sqlPattern(String operand) {
        return "(?s)" + Pattern.quote(operand).replace("%", "\\E.*\\Q").replace("_", "\\E.\\Q");
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record SortKey(String field, boolean descending) {
    }

    private record Aggregate(String operation, String field, String alias) {
    }
}
