package com.mcpserver.insights;

import com.mcpserver.reports.RqlModel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Servlet-free values for the .rqd document and insight data boundary. */
public final class InsightModel {

    private InsightModel() {
    }

    public record Parameter(String name, String type, Object defaultValue) {
    }

    public record Component(String type, Map<String, String> props, RqlModel.Span span) {
        public Component {
            props = props == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(props));
        }
    }

    public record Document(String title, String connection, List<Parameter> params, String rql, int rqlStartOffset,
                           String markdown, List<Component> components, List<RqlModel.Diagnostic> diagnostics) {
        public Document {
            title = title == null || title.isBlank() ? "Untitled insight" : title;
            params = params == null ? List.of() : List.copyOf(params);
            rql = rql == null ? "" : rql;
            markdown = markdown == null ? "" : markdown;
            components = components == null ? List.of() : List.copyOf(components);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    public record Analysis(List<RqlModel.Diagnostic> diagnostics, List<RqlModel.Completion> completions,
                           List<Parameter> params, List<Component> outline) {
        public Analysis {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            completions = completions == null ? List.of() : List.copyOf(completions);
            params = params == null ? List.of() : List.copyOf(params);
            outline = outline == null ? List.of() : List.copyOf(outline);
        }
    }

    public record DatasetData(List<String> columns, List<Map<String, Object>> rows, Map<String, String> schema) {
        public DatasetData {
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = rows == null ? List.of() : List.copyOf(rows);
            schema = schema == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(schema));
        }
    }

    public record Data(Map<String, DatasetData> datasets, List<RqlModel.Diagnostic> diagnostics,
                       List<Parameter> params, List<Component> outline,
                       List<RqlModel.RequestExecution> requests) {
        public Data {
            datasets = datasets == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(datasets));
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            params = params == null ? List.of() : List.copyOf(params);
            outline = outline == null ? List.of() : List.copyOf(outline);
            requests = requests == null ? List.of() : List.copyOf(requests);
        }
    }
}
