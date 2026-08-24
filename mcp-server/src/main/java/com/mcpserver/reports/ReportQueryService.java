package com.mcpserver.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.cache.CacheService;
import com.mcpserver.connectors.Connection;
import com.mcpserver.connectors.ConnectionService;
import com.mcpserver.connectors.ConnectionStatus;
import com.mcpserver.connectors.ConnectionType;
import com.mcpserver.tools.ApiTool;
import com.mcpserver.tools.ApiToolExecutor;
import com.mcpserver.tools.ApiToolService;
import com.mcpserver.tools.ToolInvocationResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
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
import static com.mcpserver.reports.RqlValues.compare;
import static com.mcpserver.reports.RqlValues.field;
import static com.mcpserver.reports.RqlValues.fieldName;
import static com.mcpserver.reports.RqlValues.fingerprint;
import static com.mcpserver.reports.RqlValues.literal;
import static com.mcpserver.reports.RqlValues.number;
import static com.mcpserver.reports.RqlValues.text;
import static com.mcpserver.reports.RqlValues.value;

/**
 * Analyzes and runs RQL over imported read tools. HTTP is always delegated to
 * {@link ApiToolExecutor}; reports never acquire credentials or open connections.
 *
 * <p>The executable surface matches the report automation engine this language was ported from:
 * row filtering with dates and conditionals, shaping, expansion, per-row lookups, dataset joins,
 * set operations with provenance, and column-wise comparison.
 */
@Service
public class ReportQueryService {

    private static final Pattern REQUEST = Pattern.compile("(?is)\\brequest\\s+\\\"([^\\\"]+)\\\"");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SOURCE_REQUEST =
            Pattern.compile("(?is)^request\\s+\\\"([^\\\"]+)\\\"(?:\\s+with\\s+\\{(.*)})?$");
    private static final Pattern COMBINATOR = Pattern.compile(
            "(?is)^(union\\s+all|union|intersect|except|diff|compare)\\s*\\[(.*)]" +
                    "(?:\\s+on\\s+([A-Za-z_][A-Za-z0-9_.\\[\\]\"']*))?$");
    private static final Pattern ELEMENT_LABEL =
            Pattern.compile("(?is)^(.*?)\\s+as\\s+(?:\\\"([^\\\"]+)\\\"|([A-Za-z_][A-Za-z0-9_]*))$");
    private static final Pattern LOOKUP_STAGE = Pattern.compile(
            "(?is)^lookup\\s+request\\s+\\\"([^\\\"]+)\\\"\\s+by\\s+([^\\s]+)" +
                    "(?:\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*))?(?:\\s+prefix\\s+\\\"([^\\\"]+)\\\")?$");
    private static final Pattern JOIN_STAGE = Pattern.compile(
            "(?is)^join\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+on\\s+([^\\s=]+)\\s*=\\s*([^\\s]+)" +
                    "(?:\\s+prefix\\s+\\\"([^\\\"]+)\\\")?$");
    private static final Pattern DATE_STAGE = Pattern.compile(
            "(?is)^(?:parse\\s+date|date\\s+config)\\s+([^\\s]+)(?:\\s+format\\s+\\\"([^\\\"]+)\\\")?" +
                    "(?:\\s+timezone\\s+\\\"?([A-Za-z0-9_+\\-/]+)\\\"?)?$");
    /** {@code "Orders: List orders"} or {@code "Orders:List orders"} — app-qualified request name. */
    private static final Pattern QUALIFIED_REQUEST = Pattern.compile("(?s)^([^:]+):\\s*(.+)$");
    private static final Pattern AGGREGATE = Pattern.compile(
            "(?is)^(count|sum|avg|min|max)\\s*\\(\\s*(\\*|[^)]+)\\s*\\)(?:\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*))?$");

    /**
     * A stage form the language actually has. {@code standalone} marks a stage that may appear with
     * no argument; every other form needs one.
     *
     * <p>Forms are matched in full rather than by first word. Validating on the first word alone let
     * {@code order} (no {@code by}), {@code group} (no {@code by}), and bare {@code date}/{@code parse}
     * — which exist only as the openings of {@code date config} and {@code parse date} — pass analysis
     * clean and then fail at execution with RQL014. That analysis/execution split is exactly what the
     * editor is supposed to prevent, so the two now read the same table.
     */
    private record StageForm(String form, boolean standalone, String snippet) {
        StageForm(String form, boolean standalone) {
            this(form, standalone, form);
        }
    }

    private static final List<StageForm> STAGE_FORMS = List.of(
            new StageForm("where", false),
            new StageForm("select", false),
            new StageForm("group by", false),
            new StageForm("order by", false),
            new StageForm("limit", false),
            new StageForm("offset", false),
            new StageForm("distinct", true),
            new StageForm("expand", false),
            new StageForm("rename", false),
            new StageForm("parse date", false),
            new StageForm("date config", false),
            new StageForm("lookup", false, "lookup request"),
            new StageForm("join", false),
            new StageForm("having", false));

    /** Statement openings offered at the beginning of an RQL statement. */
    private static final List<CompletionSeed> STATEMENT_COMPLETIONS = List.of(
            new CompletionSeed("let", "KEYWORD", "Create a named dataset", "let "),
            new CompletionSeed("set", "KEYWORD", "Set a reusable variable", "set "),
            new CompletionSeed("use collection", "KEYWORD", "Scope requests to an API collection", "use collection \""),
            new CompletionSeed("emit", "KEYWORD", "Return a dataset from this query", "emit "));

    private record CompletionSeed(String label, String kind, String detail, String insertText) {
    }

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

    /** Everything one execution shares: bindings, date configuration, and collected diagnostics. */
    private static final class Run {
        private final Scope scope;
        private final Map<String, Object> variables = new LinkedHashMap<>();
        private final Map<String, Dataset> datasets = new LinkedHashMap<>();
        private final Map<String, RqlDates.FieldConfig> dateConfig = new LinkedHashMap<>();
        private final List<Diagnostic> diagnostics;
        private final Instant now = Instant.now();
        /** One entry per request issued, newest wins — a request called per row reports once. */
        private final Map<String, RequestExecution> requests = new LinkedHashMap<>();

        private Run(Scope scope, List<Diagnostic> diagnostics) {
            this.scope = scope;
            this.diagnostics = diagnostics;
        }

        private RqlPredicate.Context predicateContext() {
            return new RqlPredicate.Context(dateConfig, now);
        }
    }

    /**
     * The apps one document can reach. An insight is not tied to a single collection: a request may
     * be qualified with its app ({@code request "Orders:List orders"} or {@code … from "Orders"}),
     * scoped by a preceding {@code use collection}, or left bare — in which case the preferred
     * connection is tried first and then every other connected collection.
     */
    private static final class Scope {
        private final List<Connection> connections;
        private final Map<String, List<ApiTool>> toolsByConnection = new LinkedHashMap<>();
        private final String preferredConnectionId;
        /** Set by `use collection "X"` while walking statements; overrides the preferred app. */
        private String activeConnectionId;

        private Scope(List<Connection> connections, String preferredConnectionId) {
            this.connections = connections;
            this.preferredConnectionId = preferredConnectionId;
        }

        private Connection connection(String id) {
            return connections.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
        }

        /** Matches an app by id, exact name, or slug — the same handles used elsewhere in the UI. */
        private Connection resolveApp(String reference) {
            if (reference == null || reference.isBlank()) return null;
            String needle = reference.trim();
            return connections.stream()
                    .filter(c -> c.id().equals(needle) || c.name().equalsIgnoreCase(needle)
                            || slug(c.name()).equalsIgnoreCase(slug(needle)))
                    .findFirst().orElse(null);
        }

        private List<String> searchOrder(String qualifiedApp) {
            if (qualifiedApp != null) {
                Connection app = resolveApp(qualifiedApp);
                return app == null ? List.of() : List.of(app.id());
            }
            List<String> order = new ArrayList<>();
            if (activeConnectionId != null) order.add(activeConnectionId);
            if (preferredConnectionId != null && !order.contains(preferredConnectionId)) {
                order.add(preferredConnectionId);
            }
            connections.forEach(c -> {
                if (!order.contains(c.id())) order.add(c.id());
            });
            return order;
        }

        private static String slug(String value) {
            return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        }
    }

    /** A request name resolved to one tool in one app, or an explanation of why it could not be. */
    private record Resolution(ApiTool tool, Connection connection, String error, String code) {

        private static Resolution found(ApiTool tool, Connection connection) {
            return new Resolution(tool, connection, null, null);
        }

        private static Resolution failed(String code, String error) {
            return new Resolution(null, null, error, code);
        }
    }

    /** Fast, network-free editor analysis. */
    public Analysis analyze(String source, String connectionId, Integer cursorOffset) {
        return analyze(source, connectionId, cursorOffset, Set.of());
    }

    /**
     * Fast, network-free editor analysis with variables supplied by the containing document.
     * Insight front matter, for example, supplies run-time values without needing a redundant
     * {@code set} statement in every RQL block.
     */
    public Analysis analyze(String source, String connectionId, Integer cursorOffset,
                            Set<String> initialBindings) {
        ParsedProgram program = parser.parse(source);
        List<Diagnostic> diagnostics = new ArrayList<>(program.diagnostics());
        Scope scope = scope(connectionId, diagnostics, source);
        LinkedHashSet<String> bindings = new LinkedHashSet<>();
        if (initialBindings != null) {
            initialBindings.stream()
                    .filter(Objects::nonNull)
                    .filter(binding -> binding.matches("\\$[A-Za-z_][A-Za-z0-9_]*"))
                    .forEach(bindings::add);
        }
        List<Symbol> symbols = new ArrayList<>();

        for (Statement statement : program.statements()) {
            if (statement instanceof UseStatement use) {
                applyUse(use, scope, diagnostics);
            } else if (statement instanceof LetStatement let) {
                checkPipeline(let.pipeline(), let.span(), scope, bindings, diagnostics, source);
                bindings.add(let.name());
                symbols.add(new Symbol(let.name(), "LET", let.span(), List.of()));
            } else if (statement instanceof SetStatement set) {
                bindings.add("$" + set.name());
                symbols.add(new Symbol(set.name(), "PARAM", set.span(), List.of()));
            } else if (statement instanceof EmitStatement emit) {
                checkPipeline(emit.pipeline(), emit.span(), scope, bindings, diagnostics, source);
            }
        }
        return new Analysis(diagnostics, completions(source, cursorOffset, scope, bindings), symbols);
    }

    /** Executes valid lets independently where possible; an unavailable request becomes an empty dataset. */
    public Execution execute(String source, String connectionId, Map<String, Object> inputParameters) {
        ParsedProgram program = parser.parse(source);
        List<Diagnostic> diagnostics = new ArrayList<>(program.diagnostics());
        Scope scope = scope(connectionId, diagnostics, source);
        if (scope.connections.isEmpty()) {
            throw new IllegalArgumentException(
                    "No connected API collection is available — import one on the Connections page");
        }
        Run run = new Run(scope, diagnostics);
        if (inputParameters != null) run.variables.putAll(inputParameters);

        for (Statement statement : program.statements()) {
            if (statement instanceof UseStatement use) {
                applyUse(use, scope, diagnostics);
            } else if (statement instanceof SetStatement set) {
                run.variables.put(set.name(), literal(set.value(), run.variables));
            } else if (statement instanceof LetStatement let) {
                run.datasets.put(let.name(), evaluatePipeline(let.pipeline(), let.name(), run, let.span()));
            } else if (statement instanceof EmitStatement emit) {
                String name = emit.label() == null || emit.label().isBlank() ? "result" : emit.label();
                Dataset dataset = evaluatePipeline(emit.pipeline(), name, run, emit.span());
                run.datasets.putIfAbsent(name, dataset);
            }
        }
        return new Execution(run.datasets, diagnostics, List.copyOf(run.requests.values()));
    }

    /** Every connected API collection, with the page's pick (if any) preferred for bare names. */
    private Scope scope(String preferredConnectionId, List<Diagnostic> diagnostics, String source) {
        List<Connection> collections = new ArrayList<>();
        try {
            connectionService.findAll().stream()
                    .filter(connection -> connection.type() == ConnectionType.API_COLLECTION)
                    .filter(connection -> connection.status() == ConnectionStatus.CONNECTED)
                    .forEach(collections::add);
        } catch (RuntimeException exception) {
            diagnostics.add(new Diagnostic(Span.of(source, 0, 0), Severity.HINT, "RQL105",
                    "Could not read the connected API collections."));
        }
        String preferred = preferredConnectionId == null || preferredConnectionId.isBlank()
                ? null : preferredConnectionId;
        Scope scope = new Scope(collections, preferred);
        for (Connection connection : collections) {
            scope.toolsByConnection.put(connection.id(), safeTools(connection.id()));
        }
        return scope;
    }

    private List<ApiTool> safeTools(String connectionId) {
        try {
            return apiToolService.findByConnectionId(connectionId);
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private void applyUse(UseStatement use, Scope scope, List<Diagnostic> diagnostics) {
        Connection app = scope.resolveApp(use.collection());
        if (app == null) {
            diagnostics.add(new Diagnostic(use.span(), Severity.WARNING, "RQL107",
                    "Unknown collection \"" + use.collection() + "\". Available: " + appNames(scope) + "."));
            return;
        }
        scope.activeConnectionId = app.id();
    }

    private static String appNames(Scope scope) {
        return scope.connections.isEmpty() ? "none"
                : scope.connections.stream().map(Connection::name).collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Resolves a request name to one tool. Qualified names ({@code "App:Request"}) pin the app;
     * bare names search the active/preferred app first and then the rest, and an equally good match
     * in two apps is reported rather than guessed.
     */
    private Resolution resolve(String rawName, Scope scope) {
        String name = rawName == null ? "" : rawName.trim();
        String qualifier = null;
        Matcher qualified = QUALIFIED_REQUEST.matcher(name);
        if (qualified.matches()) {
            qualifier = qualified.group(1).trim();
            name = qualified.group(2).trim();
            if (scope.resolveApp(qualifier) == null) {
                return Resolution.failed("RQL107",
                        "Unknown collection \"" + qualifier + "\". Available: " + appNames(scope) + ".");
            }
        }
        List<String> order = scope.searchOrder(qualifier);
        List<Resolution> matches = new ArrayList<>();
        for (String connectionId : order) {
            ApiTool tool = matchTool(scope.toolsByConnection.getOrDefault(connectionId, List.of()), name);
            if (tool != null) matches.add(Resolution.found(tool, scope.connection(connectionId)));
        }
        if (matches.isEmpty()) {
            return Resolution.failed("RQL101", "Unknown request \"" + rawName + "\" in " + appNames(scope) + ".");
        }
        // The active/preferred app leads the search order, so a deliberate scope wins outright.
        boolean scoped = qualifier != null || scope.activeConnectionId != null
                || scope.preferredConnectionId != null;
        if (matches.size() > 1 && !scoped) {
            String apps = matches.stream().map(match -> match.connection().name())
                    .collect(java.util.stream.Collectors.joining(", "));
            return Resolution.failed("RQL106", "Request \"" + rawName + "\" exists in several collections ("
                    + apps + "). Qualify it as \"App: " + rawName + "\".");
        }
        return matches.get(0);
    }

    // ── analysis ──────────────────────────────────────────────────────────────────

    private void checkPipeline(String pipeline, Span span, Scope scope, Set<String> bindings,
                               List<Diagnostic> diagnostics, String source) {
        Matcher request = REQUEST.matcher(pipeline);
        while (request.find()) {
            String name = request.group(1);
            Resolution resolution = resolve(name, scope);
            if (resolution.tool() == null) {
                diagnostics.add(new Diagnostic(Span.of(source, span.startOffset() + request.start(1),
                        span.startOffset() + request.end(1)), Severity.ERROR, resolution.code(),
                        resolution.error()));
            } else if (!resolution.tool().enabled()) {
                diagnostics.add(new Diagnostic(span, Severity.WARNING, "RQL102",
                        "Request \"" + name + "\" is disabled and will return no rows."));
            } else if (!resolution.tool().isRead()) {
                diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQL104",
                        "Request \"" + name + "\" changes data and cannot be used in an insight."));
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
        for (int i = 1; i < parts.size(); i++) {
            String stage = parts.get(i).text().trim();
            Matcher join = JOIN_STAGE.matcher(stage);
            if (join.matches() && !bindings.contains(join.group(1))) {
                diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQL103",
                        "Unknown dataset \"" + join.group(1) + "\" in join. Define it with let first."));
            }
        }
    }

    private List<Completion> completions(String source, Integer cursorOffset, Scope scope,
                                         Set<String> bindings) {
        if (cursorOffset == null) return List.of();
        String text = source == null ? "" : source;
        int cursor = Math.max(0, Math.min(cursorOffset, text.length()));
        String before = text.substring(0, cursor);
        int replacementStart = wordStart(text, cursor);
        Span replacement = Span.of(text, replacementStart, cursor);
        List<Completion> completions = new ArrayList<>();
        if (before.matches("(?s).*\\|>\\s*[A-Za-z_]*$")) {
            String prefix = text.substring(replacementStart, cursor);
            for (String stage : stageSnippets()) {
                addCompletion(completions, stage, "STAGE", "RQL pipeline stage", stage, replacement, prefix);
            }
        } else if (before.matches("(?s).*\\buse\\s+collection\\s+\\\"[^\\\"]*$")) {
            int stringStart = before.lastIndexOf('"') + 1;
            Span stringReplacement = Span.of(text, stringStart, cursor);
            String prefix = text.substring(stringStart, cursor);
            for (Connection connection : scope.connections) {
                addCompletion(completions, connection.name(), "COLLECTION", "API collection", connection.name(),
                        stringReplacement, prefix);
            }
        } else if (before.matches("(?s).*\\brequest\\s+\\\"[^\\\"]*$")) {
            int stringStart = before.lastIndexOf('"') + 1;
            Span stringReplacement = Span.of(text, stringStart, cursor);
            String prefix = text.substring(stringStart, cursor);
            // Offer every reachable request, qualified by app when more than one app is connected.
            boolean multipleApps = scope.connections.size() > 1;
            for (Connection connection : scope.connections) {
                for (ApiTool tool : scope.toolsByConnection.getOrDefault(connection.id(), List.of())) {
                    if (!tool.enabled() || !tool.isRead()) continue;
                    boolean qualify = multipleApps && !connection.id().equals(scope.preferredConnectionId);
                    String insert = qualify ? connection.name() + ": " + tool.displayName() : tool.displayName();
                    addCompletion(completions, insert, "REQUEST",
                            connection.name() + " · " + tool.httpMethod() + " " + tool.urlTemplate(),
                            insert, stringReplacement, prefix);
                }
            }
        } else {
            String prefix = text.substring(replacementStart, cursor);
            if (atStatementStart(before)) {
                for (CompletionSeed keyword : STATEMENT_COMPLETIONS) {
                    addCompletion(completions, keyword.label(), keyword.kind(), keyword.detail(), keyword.insertText(),
                            replacement, prefix);
                }
            }
            if (expectsPipelineSource(before)) {
                addCompletion(completions, "request", "KEYWORD", "Start a dataset from an API request",
                        "request \"", replacement, prefix);
            }
            for (String binding : bindings) {
                String kind = binding.startsWith("$") ? "VARIABLE" : "DATASET";
                String detail = binding.startsWith("$") ? "RQL variable" : "RQL dataset";
                addCompletion(completions, binding, kind, detail, binding, replacement, prefix);
            }
        }
        return completions;
    }

    /** The identifier at the caret is what a selected suggestion replaces, not just an empty span. */
    private static int wordStart(String source, int cursor) {
        int start = cursor;
        while (start > 0) {
            char character = source.charAt(start - 1);
            if (!(Character.isLetterOrDigit(character) || character == '_' || character == '$')) break;
            start--;
        }
        return start;
    }

    private static boolean atStatementStart(String before) {
        int separator = before.lastIndexOf(';');
        String fragment = before.substring(separator + 1).stripLeading();
        return fragment.matches("[A-Za-z_]*");
    }

    private static boolean expectsPipelineSource(String before) {
        return before.matches("(?is).*\\blet\\s+[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*[A-Za-z_$]*$")
                || before.matches("(?is).*\\bemit\\s+[A-Za-z_$]*$");
    }

    private static void addCompletion(List<Completion> completions, String label, String kind, String detail,
                                      String insertText, Span replacement, String prefix) {
        if (!label.regionMatches(true, 0, prefix, 0, prefix.length())) return;
        completions.add(new Completion(label, kind, detail, insertText, replacement));
    }

    // ── evaluation ────────────────────────────────────────────────────────────────

    private Dataset evaluatePipeline(String expression, String name, Run run, Span span) {
        List<RqlParser.Part> parts = RqlParser.splitTopLevel(expression, "|>", span.startOffset());
        Dataset current = evaluateSource(parts.isEmpty() ? "" : parts.get(0).text().trim(), name, run, span);
        for (int i = 1; i < parts.size(); i++) {
            current = applyStage(current, parts.get(i).text().trim(), run, span);
        }
        return new Dataset(name, current.rows());
    }

    private Dataset evaluateSource(String source, String name, Run run, Span span) {
        Matcher request = SOURCE_REQUEST.matcher(source);
        if (request.matches()) {
            return requestDataset(request.group(1), name, arguments(request.group(2), run.variables), run, span);
        }
        Matcher combinator = COMBINATOR.matcher(source);
        if (combinator.matches()) {
            String operation = combinator.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            List<LabelledDataset> inputs = new ArrayList<>();
            for (RqlParser.Part part : RqlParser.splitTopLevel(combinator.group(2), ",", span.startOffset())) {
                inputs.add(labelled(part.text().trim(), run, span));
            }
            if (operation.equals("compare")) {
                String onField = combinator.group(3);
                if (onField == null || onField.isBlank()) {
                    run.diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQL006",
                            "compare needs a field: compare [a, b] on <field>."));
                    return new Dataset(name, List.of());
                }
                return compareMatrix(name, inputs, onField.trim());
            }
            return combine(name, operation, inputs);
        }
        if (IDENT.matcher(source).matches()) {
            Dataset reference = run.datasets.get(source);
            if (reference != null) return new Dataset(name, reference.rows());
            run.diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQL103", "Unknown dataset \"" + source + "\"."));
            return new Dataset(name, List.of());
        }
        run.diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQL005",
                "Expected request \"name\", a prior dataset, or a dataset combinator."));
        return new Dataset(name, List.of());
    }

    /** A combinator element: its rows plus the label used for provenance columns. */
    private record LabelledDataset(String label, Dataset dataset) {
    }

    private LabelledDataset labelled(String element, Run run, Span span) {
        String pipeline = element;
        String label = null;
        Matcher aliased = ELEMENT_LABEL.matcher(element);
        if (aliased.matches()) {
            pipeline = aliased.group(1).trim();
            label = aliased.group(2) == null ? aliased.group(3) : aliased.group(2);
        }
        if (label == null) {
            Matcher request = REQUEST.matcher(pipeline);
            label = request.find() ? request.group(1) : pipeline.split("\\s|\\|>", 2)[0].trim();
        }
        return new LabelledDataset(label, evaluatePipeline(pipeline, label, run, span));
    }

    private Dataset requestDataset(String requestName, String name, Map<String, Object> arguments,
                                   Run run, Span span) {
        Resolution resolution = resolve(requestName, run.scope);
        if (resolution.tool() == null) {
            run.diagnostics.add(new Diagnostic(span, Severity.ERROR, resolution.code(), resolution.error()));
            return new Dataset(name, List.of());
        }
        ApiTool tool = resolution.tool();
        if (!tool.enabled() || !tool.isRead()) {
            run.diagnostics.add(new Diagnostic(span, tool.isRead() ? Severity.WARNING : Severity.ERROR,
                    tool.isRead() ? "RQL102" : "RQL104", tool.isRead()
                    ? "Request \"" + requestName + "\" is disabled." : "Only read requests can be queried."));
            return new Dataset(name, List.of());
        }
        return runRequest(name, tool, resolution.connection(), arguments, run, span);
    }

    private Dataset runRequest(String name, ApiTool tool, Connection connection,
                               Map<String, Object> arguments, Run run, Span span) {
        String key = CacheService.toolCacheKey(tool.id(), arguments);
        Object cached = cacheService.getToolResponse(key).orElse(null);
        if (cached instanceof Dataset dataset) {
            run.requests.putIfAbsent(tool.displayName(), new RequestExecution(tool.displayName(),
                    tool.httpMethod(), 200, true, 0, true));
            return new Dataset(name, dataset.rows());
        }
        try {
            ToolInvocationResult result = apiToolExecutor.execute(tool, connection, arguments);
            run.requests.put(tool.displayName(), new RequestExecution(tool.displayName(), tool.httpMethod(),
                    result.status(), result.status() < 400, result.latencyMs(), false));
            if (result.status() >= 400) {
                run.diagnostics.add(new Diagnostic(span, Severity.WARNING, "RQL201", "Request \"" + tool.displayName()
                        + "\" returned HTTP " + result.status() + "."));
                return new Dataset(name, List.of());
            }
            Dataset dataset = new Dataset(name, rowsFromJson(result.body()));
            cacheService.putToolResponse(key, dataset);
            return dataset;
        } catch (Exception exception) {
            run.diagnostics.add(new Diagnostic(span, Severity.WARNING, "RQL201", "Request \"" + tool.displayName()
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

    // ── stages ────────────────────────────────────────────────────────────────────

    private Dataset applyStage(Dataset input, String stage, Run run, Span span) {
        String lower = stage.toLowerCase(Locale.ROOT);
        try {
            if (lower.startsWith("where ")) return where(input, stage.substring(6), run);
            if (lower.startsWith("having ")) return where(input, stage.substring(7), run);
            if (lower.startsWith("select ")) return select(input, stage.substring(7), run.variables);
            if (lower.startsWith("order by ")) return order(input, stage.substring(9));
            if (lower.startsWith("limit ")) {
                return slice(input, integer(stage.substring(6), run.variables, input.rows().size()), 0);
            }
            if (lower.startsWith("offset ")) {
                return slice(input, input.rows().size(), integer(stage.substring(7), run.variables, 0));
            }
            if (lower.equals("distinct") || lower.startsWith("distinct ")) {
                return distinct(input, stage.substring(Math.min(8, stage.length())).trim());
            }
            if (lower.startsWith("group by ")) return group(input, stage.substring(9), run);
            if (lower.startsWith("expand ")) return expand(input, stage.substring(7));
            if (lower.startsWith("rename ")) return rename(input, stage.substring(7));
            if (lower.startsWith("parse date ") || lower.startsWith("date config ")) {
                return dateConfig(input, stage, run, span);
            }
            if (lower.startsWith("lookup ")) return lookup(input, stage, run, span);
            if (lower.startsWith("join ")) return join(input, stage, run, span);
            run.diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQL014", "Unknown pipeline stage."));
        } catch (RuntimeException exception) {
            run.diagnostics.add(new Diagnostic(span, Severity.WARNING, "RQL202",
                    "Could not apply '" + stage.split("\\s+", 2)[0] + "': " + safeMessage(exception)));
        }
        return input;
    }

    private Dataset where(Dataset input, String expression, Run run) {
        List<Map<String, Object>> rows = new ArrayList<>();
        RqlPredicate.Context context = run.predicateContext();
        for (Map<String, Object> row : input.rows()) {
            if (RqlPredicate.matches(row, expression.trim(), run.variables, context)) rows.add(row);
        }
        return new Dataset(input.name(), rows);
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
                Matcher alias = ELEMENT_LABEL.matcher(projection);
                String expression = alias.matches() ? alias.group(1).trim() : projection;
                String key = alias.matches()
                        ? (alias.group(2) == null ? alias.group(3) : alias.group(2)) : fieldName(expression);
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
        int end = (int) Math.max(start,
                Math.min((long) input.rows().size(), (long) start + Math.max(0L, limit)));
        return new Dataset(input.name(), input.rows().subList(start, end));
    }

    private Dataset distinct(Dataset input, String fieldSpec) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> fields = fieldSpec.isBlank() ? List.of()
                : RqlParser.splitTopLevel(fieldSpec.replaceFirst("(?is)^by\\s+", ""), ",", 0)
                .stream().map(part -> part.text().trim()).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : input.rows()) {
            String marker = fields.isEmpty() ? fingerprint(row)
                    : fields.stream().map(f -> text(field(row, f))).toList().toString();
            if (seen.add(marker)) rows.add(row);
        }
        return new Dataset(input.name(), rows);
    }

    private Dataset group(Dataset input, String spec, Run run) {
        int aggIndex = indexOfKeyword(spec, "agg");
        int havingIndex = indexOfKeyword(spec, "having");
        int fieldsEnd = minPositive(aggIndex, havingIndex, spec.length());
        List<String> fields = RqlParser.splitTopLevel(spec.substring(0, fieldsEnd), ",", 0).stream()
                .map(part -> part.text().trim()).filter(f -> !f.isBlank()).toList();
        String aggregates = aggIndex < 0 ? ""
                : spec.substring(aggIndex + 3, havingIndex > aggIndex ? havingIndex : spec.length()).trim();
        String having = havingIndex < 0 ? null : spec.substring(havingIndex + 6).trim();
        Map<List<Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : input.rows()) {
            List<Object> key = fields.stream().map(f -> field(row, f)).toList();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        List<Aggregate> aggregateList = aggregates.isBlank() ? List.of() : parseAggregates(aggregates);
        List<Map<String, Object>> rows = new ArrayList<>();
        RqlPredicate.Context context = run.predicateContext();
        for (Map.Entry<List<Object>, List<Map<String, Object>>> entry : groups.entrySet()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < fields.size(); i++) row.put(fieldName(fields.get(i)), entry.getKey().get(i));
            for (Aggregate aggregate : aggregateList) row.put(aggregate.alias(), aggregate(aggregate, entry.getValue()));
            if (having == null || RqlPredicate.matches(row, having, run.variables, context)) rows.add(row);
        }
        return new Dataset(input.name(), rows);
    }

    private List<Aggregate> parseAggregates(String source) {
        List<Aggregate> result = new ArrayList<>();
        for (RqlParser.Part part : RqlParser.splitTopLevel(source, ",", 0)) {
            Matcher matcher = AGGREGATE.matcher(part.text().trim());
            if (!matcher.matches()) continue;
            String operation = matcher.group(1).toLowerCase(Locale.ROOT);
            String field = matcher.group(2).trim();
            String alias = matcher.group(3) == null
                    ? operation + ("*".equals(field) ? "" : "_" + fieldName(field)) : matcher.group(3);
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

    /**
     * Array fan-out. Object elements are flattened as {@code field.child}; a child present in only
     * some elements moves under the exception label ("exceptions" unless {@code as} renames it), so
     * a sparse field never silently shifts the meaning of a column. Scalar elements keep the simple
     * shape: one column named after the field, or after {@code as}.
     */
    private Dataset expand(Dataset input, String spec) {
        Matcher matcher = Pattern.compile("(?is)^(.+?)(?:\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*))?$").matcher(spec.trim());
        if (!matcher.matches()) return input;
        String fieldPath = matcher.group(1).trim();
        String alias = matcher.group(2);
        String prefix = fieldName(fieldPath);
        String exceptionLabel = alias == null ? "exceptions" : alias;

        Set<String> commonChildren = commonChildFields(input, fieldPath);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : input.rows()) {
            Object value = field(row, fieldPath);
            if (!(value instanceof Collection<?> values)) {
                rows.add(row);
                continue;
            }
            for (Object item : values) {
                LinkedHashMap<String, Object> expanded = new LinkedHashMap<>(row);
                expanded.remove(prefix);
                if (item instanceof Map<?, ?> child) {
                    child.forEach((key, childValue) -> {
                        String name = String.valueOf(key);
                        String column = commonChildren.contains(name)
                                ? prefix + "." + name : exceptionLabel + "." + name;
                        expanded.put(column, childValue);
                    });
                } else {
                    expanded.put(alias == null ? prefix : alias, item);
                }
                rows.add(expanded);
            }
        }
        return new Dataset(input.name(), rows);
    }

    /** Child keys present in every object element — the rest are sparse. */
    private Set<String> commonChildFields(Dataset input, String fieldPath) {
        Set<String> common = null;
        for (Map<String, Object> row : input.rows()) {
            if (!(field(row, fieldPath) instanceof Collection<?> values)) continue;
            for (Object item : values) {
                if (!(item instanceof Map<?, ?> child)) continue;
                Set<String> keys = new LinkedHashSet<>();
                child.keySet().forEach(key -> keys.add(String.valueOf(key)));
                if (common == null) {
                    common = keys;
                } else {
                    common.retainAll(keys);
                }
            }
        }
        return common == null ? Set.of() : common;
    }

    private Dataset rename(Dataset input, String spec) {
        Map<String, String> names = new LinkedHashMap<>();
        for (RqlParser.Part part : RqlParser.splitTopLevel(spec, ",", 0)) {
            Matcher matcher = ELEMENT_LABEL.matcher(part.text().trim());
            if (matcher.matches()) {
                names.put(fieldName(matcher.group(1)),
                        matcher.group(2) == null ? matcher.group(3) : matcher.group(2));
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : input.rows()) {
            LinkedHashMap<String, Object> renamed = new LinkedHashMap<>();
            row.forEach((key, value) -> renamed.put(names.getOrDefault(key, key), value));
            rows.add(renamed);
        }
        return new Dataset(input.name(), rows);
    }

    /**
     * Registers how a field's dates are read, for the whole run rather than this pipeline alone —
     * a date format is a property of the field, not of the query that first mentioned it.
     */
    private Dataset dateConfig(Dataset input, String stage, Run run, Span span) {
        Matcher matcher = DATE_STAGE.matcher(stage.trim());
        if (!matcher.matches()) {
            run.diagnostics.add(new Diagnostic(span, Severity.WARNING, "RQL203",
                    "Expected parse date <field> format \"pattern\" [timezone \"zone\"]."));
            return input;
        }
        String fieldPath = matcher.group(1).trim();
        RqlDates.FieldConfig config = new RqlDates.FieldConfig(matcher.group(2), matcher.group(3));
        run.dateConfig.put(fieldPath, config);
        run.dateConfig.put(fieldName(fieldPath), config);
        return input;
    }

    /**
     * Per-row detail request: takes {@code by} from each row, runs the detail request with that
     * value, and merges its fields as {@code prefix.field} — plus unprefixed when the name does not
     * clash with a source field, matching the lookup tables this is ported from.
     */
    private Dataset lookup(Dataset input, String stage, Run run, Span span) {
        Matcher matcher = LOOKUP_STAGE.matcher(stage.trim());
        if (!matcher.matches()) {
            run.diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQL204",
                    "Expected lookup request \"name\" by <field> [as <param>] [prefix \"detail\"]."));
            return input;
        }
        String requestName = matcher.group(1);
        String byField = matcher.group(2).trim();
        String parameter = matcher.group(3) == null ? fieldName(byField) : matcher.group(3);
        String prefix = matcher.group(4) == null ? "detail" : matcher.group(4);

        Resolution resolution = resolve(requestName, run.scope);
        if (resolution.tool() == null) {
            run.diagnostics.add(new Diagnostic(span, Severity.ERROR, resolution.code(), resolution.error()));
            return input;
        }
        ApiTool tool = resolution.tool();
        if (!tool.enabled() || !tool.isRead()) {
            run.diagnostics.add(new Diagnostic(span, tool.isRead() ? Severity.WARNING : Severity.ERROR,
                    tool.isRead() ? "RQL102" : "RQL104",
                    tool.isRead() ? "Lookup request \"" + requestName + "\" is disabled."
                            : "Only read requests can be used for a lookup."));
            return input;
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : input.rows()) {
            Object key = field(row, byField);
            LinkedHashMap<String, Object> merged = new LinkedHashMap<>(row);
            if (key != null) {
                Dataset detail = runRequest(input.name(), tool, resolution.connection(),
                        Map.of(parameter, key), run, span);
                Map<String, Object> detailRow = detail.rows().isEmpty() ? Map.of() : detail.rows().get(0);
                detailRow.forEach((name, value) -> {
                    merged.put(prefix + "." + name, value);
                    if (!row.containsKey(name)) merged.put(name, value);
                });
            }
            rows.add(merged);
        }
        return new Dataset(input.name(), rows);
    }

    /** Left join against another dataset: unmatched left rows are kept with no right-hand fields. */
    private Dataset join(Dataset input, String stage, Run run, Span span) {
        Matcher matcher = JOIN_STAGE.matcher(stage.trim());
        if (!matcher.matches()) {
            run.diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQL205",
                    "Expected join <dataset> on <leftField> = <rightField> [prefix \"name\"]."));
            return input;
        }
        Dataset right = run.datasets.get(matcher.group(1));
        if (right == null) {
            run.diagnostics.add(new Diagnostic(span, Severity.ERROR, "RQL103",
                    "Unknown dataset \"" + matcher.group(1) + "\" in join."));
            return input;
        }
        String leftField = matcher.group(2).trim();
        String rightField = matcher.group(3).trim();
        String prefix = matcher.group(4) == null ? matcher.group(1) : matcher.group(4);

        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        for (Map<String, Object> row : right.rows()) {
            Object key = field(row, rightField);
            if (key != null) {
                index.putIfAbsent(text(key).toLowerCase(Locale.ROOT), row);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : input.rows()) {
            LinkedHashMap<String, Object> merged = new LinkedHashMap<>(row);
            Object key = field(row, leftField);
            Map<String, Object> match = key == null
                    ? null : index.get(text(key).toLowerCase(Locale.ROOT));
            if (match != null) {
                match.forEach((name, value) -> {
                    merged.put(prefix + "." + name, value);
                    if (!row.containsKey(name)) merged.put(name, value);
                });
            }
            rows.add(merged);
        }
        return new Dataset(input.name(), rows);
    }

    // ── set operations ────────────────────────────────────────────────────────────

    /**
     * Union keeps rows as they are; the set operations add provenance ({@code _source} and one
     * {@code _in_<label>} per input) so a reader can see which sources a row came from.
     */
    private Dataset combine(String name, String operation, List<LabelledDataset> inputs) {
        if (inputs.isEmpty()) return new Dataset(name, List.of());
        if (operation.startsWith("union")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            inputs.forEach(input -> rows.addAll(input.dataset().rows()));
            return operation.equals("union") ? distinct(new Dataset(name, rows), "") : new Dataset(name, rows);
        }
        Map<String, Set<String>> byLabel = new LinkedHashMap<>();
        for (LabelledDataset input : inputs) {
            Set<String> signatures = new LinkedHashSet<>();
            input.dataset().rows().forEach(row -> signatures.add(fingerprint(row)));
            byLabel.put(input.label(), signatures);
        }
        List<String> labels = List.copyOf(byLabel.keySet());
        List<Map<String, Object>> rows = new ArrayList<>();

        if (operation.equals("diff")) {
            // Symmetric difference: rows unique to one source, grouped by that source in order.
            for (LabelledDataset input : inputs) {
                for (Map<String, Object> row : input.dataset().rows()) {
                    String signature = fingerprint(row);
                    boolean elsewhere = byLabel.entrySet().stream()
                            .anyMatch(entry -> !entry.getKey().equals(input.label())
                                    && entry.getValue().contains(signature));
                    if (!elsewhere) rows.add(withProvenance(row, input.label(), labels, byLabel, signature));
                }
            }
            return new Dataset(name, rows);
        }

        LabelledDataset first = inputs.get(0);
        for (Map<String, Object> row : first.dataset().rows()) {
            String signature = fingerprint(row);
            boolean inEveryOther = byLabel.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(first.label()))
                    .allMatch(entry -> entry.getValue().contains(signature));
            boolean inAnyOther = byLabel.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(first.label()))
                    .anyMatch(entry -> entry.getValue().contains(signature));
            boolean keep = operation.equals("intersect") ? inEveryOther : !inAnyOther;
            if (!keep) continue;
            String source = operation.equals("intersect") ? "ALL" : first.label();
            rows.add(withProvenance(row, source, labels, byLabel, signature));
        }
        return new Dataset(name, distinct(new Dataset(name, rows), "").rows());
    }

    private Map<String, Object> withProvenance(Map<String, Object> row, String source, List<String> labels,
                                               Map<String, Set<String>> byLabel, String signature) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>(row);
        out.put("_source", source);
        for (String label : labels) out.put("_in_" + label, byLabel.get(label).contains(signature));
        return out;
    }

    /**
     * Column-wise comparison: one row per distinct value of {@code onField}, with a boolean column
     * per source and a {@code _count} of how many sources hold it.
     */
    private Dataset compareMatrix(String name, List<LabelledDataset> inputs, String onField) {
        String column = fieldName(onField);
        Map<String, Map<String, Object>> matrix = new LinkedHashMap<>();
        Map<String, Set<String>> valuesByLabel = new LinkedHashMap<>();
        for (LabelledDataset input : inputs) {
            Set<String> values = new LinkedHashSet<>();
            for (Map<String, Object> row : input.dataset().rows()) {
                Object raw = field(row, onField);
                if (raw == null) continue;
                String key = text(RqlValues.normalizeScalar(raw));
                values.add(key);
                matrix.computeIfAbsent(key, ignored -> {
                    LinkedHashMap<String, Object> created = new LinkedHashMap<>();
                    created.put(column, raw);
                    return created;
                });
            }
            valuesByLabel.put(input.label(), values);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        matrix.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> {
                    LinkedHashMap<String, Object> row = new LinkedHashMap<>(entry.getValue());
                    long count = 0;
                    for (Map.Entry<String, Set<String>> source : valuesByLabel.entrySet()) {
                        boolean present = source.getValue().contains(entry.getKey());
                        row.put("_in_" + source.getKey(), present);
                        if (present) count++;
                    }
                    row.put("_count", count);
                    rows.add(row);
                });
        return new Dataset(name, rows);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private ApiTool matchTool(List<ApiTool> tools, String requestName) {
        return tools.stream().filter(tool -> tool.displayName().equalsIgnoreCase(requestName)
                        || tool.name().equalsIgnoreCase(requestName) || tool.requestSlug().equalsIgnoreCase(requestName))
                .findFirst().orElse(null);
    }

    private Map<String, Object> arguments(String raw, Map<String, Object> variables) {
        if (raw == null || raw.isBlank()) return Map.of();
        Map<String, Object> args = new LinkedHashMap<>();
        for (RqlParser.Part part : RqlParser.splitTopLevel(raw, ",", 0)) {
            String[] pair = part.text().split(":|=", 2);
            if (pair.length == 2) {
                args.put(pair[0].trim().replaceAll("^\\\"|\\\"$", ""), literal(pair[1].trim(), variables));
            }
        }
        return args;
    }

    private int integer(String raw, Map<String, Object> variables, int fallback) {
        BigDecimal number = number(literal(raw, variables));
        if (number == null) return fallback;
        if (number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) return Integer.MAX_VALUE;
        if (number.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0) return Integer.MIN_VALUE;
        return number.intValue();
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

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    /**
     * True when the stage names a real form, carrying an argument where one is required. Matching is
     * deliberately as whitespace-strict as {@link #applyStage} — a stage is never re-spaced before
     * comparison, because collapsing runs of spaces would also rewrite string literals
     * ({@code where name = "hello  world"}), so the two would disagree again in a subtler way.
     */
    static boolean isKnownStage(String stage) {
        String lower = stage == null ? "" : stage.trim().toLowerCase(Locale.ROOT);
        for (StageForm form : STAGE_FORMS) {
            if (form.standalone() && lower.equals(form.form())) return true;
            if (lower.startsWith(form.form() + " ") && lower.length() > form.form().length() + 1) return true;
        }
        return false;
    }

    /**
     * The form an unknown stage was probably reaching for — {@code order id} suggests
     * {@code order by}. Compares on the first word so the common "forgot `by`" slip is named.
     */
    static String suggestedStage(String stage) {
        String first = (stage == null ? "" : stage.trim().toLowerCase(Locale.ROOT)).split("\\s+", 2)[0];
        if (first.isEmpty()) return null;
        for (StageForm form : STAGE_FORMS) {
            if (!form.form().equals(first) && form.form().split(" ")[0].equals(first)) return form.form();
        }
        return null;
    }

    /** Stage forms offered after {@code |>}; kept here so grammar, editor, and executor cannot drift. */
    static List<String> stageSnippets() {
        return STAGE_FORMS.stream().map(StageForm::snippet).toList();
    }

    private record SortKey(String field, boolean descending) {
    }

    private record Aggregate(String operation, String field, String alias) {
    }
}
