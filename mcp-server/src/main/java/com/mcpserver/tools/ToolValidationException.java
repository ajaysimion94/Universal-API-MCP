package com.mcpserver.tools;

import java.util.List;

/**
 * Arguments violated the tool's generated schema — execution never started (§8 self-correction
 * loop). Violations are returned to the caller (REST 422 / MCP isError) as structured data.
 */
public class ToolValidationException extends RuntimeException {

    private final transient List<SchemaValidator.Violation> violations;

    public ToolValidationException(List<SchemaValidator.Violation> violations) {
        super("Arguments violate the tool's schema: "
                + violations.stream().map(SchemaValidator.Violation::message).toList());
        this.violations = violations;
    }

    public List<SchemaValidator.Violation> violations() {
        return violations;
    }
}
