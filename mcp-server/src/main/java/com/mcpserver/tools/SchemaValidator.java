package com.mcpserver.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates an argument map against a generated params schema. Deliberately covers only the
 * subset our parsers emit (type / required / enum) — we control schema generation, so a full
 * draft-07 validator dependency buys nothing. A violation never executes (§8 self-correction):
 * callers surface {@link Violation}s verbatim as structured errors.
 */
public final class SchemaValidator {

    public record Violation(String param, String expected, String message) {
    }

    private SchemaValidator() {
    }

    public static List<Violation> validate(JsonNode schema, Map<String, Object> args) {
        List<Violation> violations = new ArrayList<>();
        JsonNode properties = schema.path("properties");

        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode name : required) {
                String param = name.asText();
                Object value = args.get(param);
                boolean hasDefault = properties.path(param).has("default");
                if ((value == null || value.toString().isBlank()) && !hasDefault) {
                    violations.add(new Violation(param,
                            properties.path(param).path("type").asText("string"),
                            "Required parameter '" + param + "' is missing"));
                }
            }
        }

        for (Map.Entry<String, Object> arg : args.entrySet()) {
            JsonNode prop = properties.path(arg.getKey());
            if (prop.isMissingNode() || arg.getValue() == null) continue;
            String expected = prop.path("type").asText("string");
            Object value = arg.getValue();

            if (!typeMatches(expected, value)) {
                violations.add(new Violation(arg.getKey(), expected,
                        "Parameter '" + arg.getKey() + "' must be of type " + expected
                                + " (got: " + value + ")"));
                continue;
            }
            JsonNode allowed = prop.path("enum");
            if (allowed.isArray() && !allowed.isEmpty()) {
                boolean ok = false;
                for (JsonNode candidate : allowed) {
                    if (candidate.asText().equals(String.valueOf(value))) {
                        ok = true;
                        break;
                    }
                }
                if (!ok) {
                    violations.add(new Violation(arg.getKey(), "one of " + allowed,
                            "Parameter '" + arg.getKey() + "' must be one of " + allowed
                                    + " (got: " + value + ")"));
                }
            }
        }
        return violations;
    }

    /** Free-text args arrive as strings — a string that parses as the target type is accepted. */
    private static boolean typeMatches(String expected, Object value) {
        return switch (expected) {
            case "integer" -> value instanceof Integer || value instanceof Long
                    || (value instanceof String s && s.matches("-?\\d+"));
            case "number" -> value instanceof Number
                    || (value instanceof String s && s.matches("-?\\d+(\\.\\d+)?"));
            case "boolean" -> value instanceof Boolean
                    || (value instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")));
            case "array" -> value instanceof List;
            case "object" -> value instanceof Map;
            default -> true; // strings accept anything stringifiable
        };
    }
}
