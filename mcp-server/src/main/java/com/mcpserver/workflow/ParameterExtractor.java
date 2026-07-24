package com.mcpserver.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.tools.ApiTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parameter extraction engine (§7.1): extracts parameters from raw user inputs
 * using regex or JSONPath extraction templates configured on the tool.
 *
 * <p>If no extraction template is defined, it falls back to parsing JSON/XML
 * payloads or maps the text directly to the tool's primary parameter.
 */
@Component
public class ParameterExtractor {

    private static final Logger log = LoggerFactory.getLogger(ParameterExtractor.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public record ExtractedArgs(Map<String, Object> args, List<String> missingRequired, String parseError) {
    }

    /**
     * Extracts arguments for a tool given the free-text remainder using the tool's
     * paramsSchema and extractionTemplate.
     */
    public ExtractedArgs extract(ApiTool tool, String remainder) {
        Map<String, Object> args = new HashMap<>();
        String text = remainder == null ? "" : remainder.trim();

        String template = tool.extractionTemplate();
        if (template != null && !template.isBlank()) {
            try {
                JsonNode templateJson = mapper.readTree(template);
                if (templateJson.isObject()) {
                    templateJson.properties().forEach(e -> {
                        String paramName = e.getKey();
                        String rule = e.getValue().asText();
                        Object val = extractValue(text, rule);
                        if (val != null) {
                            args.put(paramName, val);
                        }
                    });
                }
            } catch (Exception e) {
                log.warn("Failed to parse extraction template for tool {}: {}", tool.name(), e.getMessage());
                return new ExtractedArgs(args, List.of(), "Invalid extraction template configuration: " + e.getMessage());
            }
        }

        // If no custom template extracted anything, fallback to standard parsing logic
        if (args.isEmpty()) {
            if (text.startsWith("{")) {
                try {
                    JsonNode node = mapper.readTree(text);
                    if (!node.isObject()) {
                        return new ExtractedArgs(args, List.of(), "Inline JSON must be an object of arguments");
                    }
                    node.properties().forEach(e -> args.put(e.getKey(),
                            mapper.convertValue(e.getValue(), Object.class)));
                } catch (Exception e) {
                    return new ExtractedArgs(args, List.of(), "Inline JSON didn't parse: " + e.getMessage());
                }
            } else if (text.startsWith("<")) {
                try {
                    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    var doc = factory.newDocumentBuilder()
                            .parse(new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    var children = doc.getDocumentElement().getChildNodes();
                    for (int i = 0; i < children.getLength(); i++) {
                        var child = children.item(i);
                        if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                            args.put(child.getNodeName(), child.getTextContent().trim());
                        }
                    }
                } catch (Exception e) {
                    return new ExtractedArgs(args, List.of(), "Inline XML didn't parse: " + e.getMessage());
                }
            } else if (!text.isBlank()) {
                if (tool.primaryParam() != null) {
                    args.put(tool.primaryParam(), stripQuotes(text));
                } else {
                    return new ExtractedArgs(args, List.of(),
                            "Tool " + tool.name() + " has no primary argument — pass inline JSON like {\"param\": \"value\"}");
                }
            }
        }

        return new ExtractedArgs(args, missingRequired(tool, args), null);
    }

    private Object extractValue(String text, String rule) {
        if (rule.startsWith("regex:")) {
            String patternStr = rule.substring(6);
            try {
                Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(text);
                if (matcher.find() && matcher.groupCount() >= 1) {
                    return matcher.group(1).trim();
                }
            } catch (Exception e) {
                log.warn("Invalid regex pattern: {}", patternStr);
            }
        } else {
            // Default rule is regex search without "regex:" prefix
            try {
                Pattern pattern = Pattern.compile(rule, Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(text);
                if (matcher.find() && matcher.groupCount() >= 1) {
                    return matcher.group(1).trim();
                }
            } catch (Exception e) {
                log.warn("Invalid regex pattern: {}", rule);
            }
        }
        return null;
    }

    private List<String> missingRequired(ApiTool tool, Map<String, Object> args) {
        List<String> missing = new ArrayList<>();
        try {
            JsonNode schema = mapper.readTree(tool.paramsSchema());
            JsonNode required = schema.path("required");
            if (required.isArray()) {
                for (JsonNode reqNode : required) {
                    String reqParam = reqNode.asText();
                    if (!args.containsKey(reqParam) || args.get(reqParam) == null || String.valueOf(args.get(reqParam)).isBlank()) {
                        // Check if it has a default value in schema
                        JsonNode prop = schema.path("properties").path(reqParam);
                        if (prop.path("default").isMissingNode()) {
                            missing.add(reqParam);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to check missing required params for tool {}: {}", tool.name(), e.getMessage());
        }
        return missing;
    }

    private String stripQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
