package com.mcpserver.insights;

import com.mcpserver.connectors.Connection;
import com.mcpserver.connectors.ConnectionService;
import com.mcpserver.reports.ReportQueryService;
import com.mcpserver.reports.RqlModel;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.mcpserver.insights.InsightModel.*;
import static com.mcpserver.reports.RqlModel.*;

/** Bridges parsed .rqd documents to RQL analysis/execution and validates insight bindings. */
@Service
public class InsightService {

    private final InsightDocumentParser parser = new InsightDocumentParser();
    private final ReportQueryService reportQueryService;
    private final ConnectionService connectionService;
    private final InsightRepository insightRepository;

    public InsightService(ReportQueryService reportQueryService, ConnectionService connectionService,
                          InsightRepository insightRepository) {
        this.reportQueryService = reportQueryService;
        this.connectionService = connectionService;
        this.insightRepository = insightRepository;
    }

    // ── saved insights ────────────────────────────────────────────────────────────

    public List<SavedInsight> findAll() {
        return insightRepository.findAll();
    }

    public SavedInsight findById(String id) {
        return insightRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Insight not found: " + id));
    }

    public SavedInsight create(String name, String description, String source, String connectionId) {
        Instant now = Instant.now();
        SavedInsight insight = new SavedInsight(UUID.randomUUID().toString(), requireName(name),
                description == null ? "" : description, source == null ? "" : source,
                blankToNull(connectionId), now, now);
        insightRepository.insert(insight);
        return insight;
    }

    public SavedInsight update(String id, String name, String description, String source, String connectionId) {
        SavedInsight existing = findById(id);
        SavedInsight updated = new SavedInsight(existing.id(), requireName(name),
                description == null ? existing.description() : description,
                source == null ? existing.source() : source,
                blankToNull(connectionId), existing.createdAt(), Instant.now());
        insightRepository.update(updated);
        return updated;
    }

    public void delete(String id) {
        findById(id);
        insightRepository.delete(id);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("An insight needs a name");
        return name.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public InsightModel.Analysis analyze(String source, String connectionId, Integer cursorOffset) {
        Document document = parser.parse(source);
        String resolvedConnection = resolveConnection(connectionId, document.connection());
        RqlModel.Analysis query = reportQueryService.analyze(document.rql(), resolvedConnection, cursorOffset);
        List<Diagnostic> diagnostics = new ArrayList<>(document.diagnostics());
        diagnostics.addAll(query.diagnostics().stream().map(diagnostic -> remap(diagnostic, source, document.rqlStartOffset())).toList());
        validateComponents(document.components(), query.symbols(), diagnostics);
        return new InsightModel.Analysis(diagnostics,
                query.completions().stream().map(completion -> new RqlModel.Completion(completion.label(), completion.kind(),
                        completion.detail(), completion.insertText(), remap(completion.replaceSpan(), source, document.rqlStartOffset()))).toList(),
                document.params(), document.components());
    }

    public Data data(String source, String connectionId, Map<String, Object> parameters) {
        Document document = parser.parse(source);
        // A connection is a preference, not a requirement: an insight may pull from several apps,
        // and a fully qualified request ("App: Request") names its own.
        String resolvedConnection = resolveConnection(connectionId, document.connection());
        Map<String, Object> values = new LinkedHashMap<>();
        for (Parameter parameter : document.params()) values.put(parameter.name(), parameter.defaultValue());
        if (parameters != null) values.putAll(parameters);
        RqlModel.Execution execution = reportQueryService.execute(document.rql(), resolvedConnection, values);
        List<Diagnostic> diagnostics = new ArrayList<>(document.diagnostics());
        diagnostics.addAll(execution.diagnostics().stream().map(diagnostic -> remap(diagnostic, source, document.rqlStartOffset())).toList());
        validateComponents(document.components(), execution.datasets().entrySet().stream()
                .map(entry -> new Symbol(entry.getKey(), "LET", null, entry.getValue().columns())).toList(), diagnostics);
        Map<String, DatasetData> datasets = new LinkedHashMap<>();
        execution.datasets().forEach((name, dataset) -> datasets.put(name,
                new DatasetData(dataset.columns(), dataset.rows(), dataset.schema())));
        for (Component component : document.components()) {
            if (component.type().equals("BarChart")) {
                DatasetData data = datasets.get(datasetName(component));
                if (data != null && data.rows().size() == 1) {
                    diagnostics.add(new Diagnostic(component.span(), Severity.INFO, "RQI310",
                            "One bar — use <Stat> when a single number is the point."));
                }
            }
        }
        return new Data(datasets, diagnostics, document.params(), document.components(), execution.requests());
    }

    private String resolveConnection(String supplied, String declared) {
        if (supplied != null && !supplied.isBlank()) return supplied;
        if (declared == null || declared.isBlank()) return null;
        return connectionService.findAll().stream()
                .filter(connection -> connection.id().equals(declared)
                        || connection.name().equalsIgnoreCase(declared)
                        || slug(connection.name()).equalsIgnoreCase(declared))
                .map(Connection::id).findFirst().orElse(declared);
    }

    private void validateComponents(List<Component> components, List<Symbol> symbols, List<Diagnostic> diagnostics) {
        Map<String, Symbol> datasets = new LinkedHashMap<>();
        for (Symbol symbol : symbols) if (symbol.kind().equals("LET")) datasets.put(symbol.name(), symbol);
        for (Component component : components) {
            String data = datasetName(component);
            if (data != null && !datasets.containsKey(data)) {
                diagnostics.add(new Diagnostic(component.span(), Severity.ERROR, "RQI101",
                        "Component references unknown dataset \"" + data + "\"."));
            }
        }
    }

    private static String datasetName(Component component) {
        String data = component.props().get("data");
        if (data == null) return null;
        return data.trim().replaceAll("^\\{|}$", "");
    }

    private static String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static Diagnostic remap(Diagnostic diagnostic, String source, int sourceOffset) {
        return new Diagnostic(remap(diagnostic.span(), source, sourceOffset), diagnostic.severity(), diagnostic.code(), diagnostic.message());
    }

    private static RqlModel.Span remap(RqlModel.Span span, String source, int sourceOffset) {
        if (span == null) return null;
        return RqlModel.Span.of(source, span.startOffset() + sourceOffset, span.endOffset() + sourceOffset);
    }
}
