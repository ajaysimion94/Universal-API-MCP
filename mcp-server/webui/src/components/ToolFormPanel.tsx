import { useState } from "react";
import {
  JsonSchemaProperty,
  ToolExecution,
  ToolSummary,
  ToolViolation,
  WorkflowPreview,
  invokeTool,
  isWorkflowPreview,
} from "../api";
import { HashIcon, PlayIcon } from "../icons";
import { Toggle } from "./Toggle";
import { ToolConfirmPanel } from "./ToolConfirmPanel";
import { ToolResultPanel } from "./ToolResultPanel";

/**
 * Auto-generated input form for a tool whose query didn't satisfy every required parameter.
 * Fields come straight from the tool's generated JSON Schema; on submit the tool executes and
 * the panel swaps to the result in place. Inline panel, not a modal (.impeccable.md).
 */
export function ToolFormPanel({
  tool,
  prefill,
  missingRequired,
  violations,
  parseError,
}: {
  tool: ToolSummary;
  prefill?: Record<string, unknown>;
  missingRequired?: string[];
  violations?: ToolViolation[];
  parseError?: string;
}) {
  const properties = tool.paramsSchema.properties ?? {};
  const required = new Set(tool.paramsSchema.required ?? []);
  const [values, setValues] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {};
    for (const [name, prop] of Object.entries(properties)) {
      const pre = prefill?.[name];
      if (pre !== undefined && pre !== null) {
        initial[name] = typeof pre === "string" ? pre : JSON.stringify(pre);
      } else if (prop.default !== undefined) {
        initial[name] = String(prop.default);
      } else {
        initial[name] = prop.type === "boolean" ? "false" : "";
      }
    }
    return initial;
  });
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(parseError ?? null);
  const [result, setResult] = useState<ToolExecution | null>(null);
  const [preview, setPreview] = useState<WorkflowPreview | null>(null);

  const violationFor = (name: string) =>
    violations?.find((v) => v.param === name)?.message;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setRunning(true);
    setError(null);
    try {
      const args: Record<string, unknown> = {};
      for (const [name, raw] of Object.entries(values)) {
        if (raw === "") continue;
        args[name] = coerce(raw, properties[name]);
      }
      const res = await invokeTool(tool.id, args);
      if (isWorkflowPreview(res)) {
        setPreview(res);
      } else {
        setResult(res);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Tool execution failed");
    } finally {
      setRunning(false);
    }
  };

  if (preview) {
    return (
      <ToolConfirmPanel
        tool={tool}
        preview={preview.preview}
        args={preview.args}
        confirmationToken={preview.confirmationToken}
        tokenExpiresAt={preview.tokenExpiresAt}
        onCancel={() => setPreview(null)}
      />
    );
  }

  if (result) {
    return <ToolResultPanel toolName={tool.name} result={result} />;
  }

  return (
    <div className="tool-panel">
      <div className="tool-panel-header">
        <HashIcon size={16} className="tool-result-icon" />
        <span className="tool-panel-name mono">{tool.name}</span>
        <span className={`method-badge mono ${tool.method === "GET" ? "" : "method-write"}`}>
          {tool.method}
        </span>
      </div>
      {tool.description && <p className="tool-panel-description">{tool.description}</p>}
      <p className="tool-panel-note">
        {missingRequired && missingRequired.length > 0
          ? `Missing: ${missingRequired.join(", ")} — fill in the inputs to run.`
          : "Fill in the inputs to run this tool."}
      </p>

      {error && (
        <div className="error-banner" role="alert">
          {error}
        </div>
      )}

      <form className="tool-form" onSubmit={submit}>
        {Object.entries(properties).map(([name, prop]) => (
          <label key={name} className="form-field tool-form-field">
            <span>
              {name}
              {required.has(name) && <span className="tool-form-required"> *</span>}
              <span className="tool-form-type mono"> {prop.type}</span>
            </span>
            <Field
              name={name}
              prop={prop}
              value={values[name] ?? ""}
              onChange={(v) => setValues((prev) => ({ ...prev, [name]: v }))}
            />
            {prop.description && <span className="tool-form-hint">{prop.description}</span>}
            {violationFor(name) && (
              <span className="tool-form-violation">{violationFor(name)}</span>
            )}
          </label>
        ))}
        {Object.keys(properties).length === 0 && (
          <p className="tool-panel-note">This tool takes no inputs.</p>
        )}
        <div className="form-actions">
          <button type="submit" className="btn btn-primary" disabled={running}>
            <PlayIcon size={13} />
            {running ? "Running…" : `Run ${tool.method}`}
          </button>
        </div>
      </form>
    </div>
  );
}

function Field({
  name,
  prop,
  value,
  onChange,
}: {
  name: string;
  prop: JsonSchemaProperty;
  value: string;
  onChange: (v: string) => void;
}) {
  if (prop.enum && prop.enum.length > 0) {
    return (
      <select className="form-input" value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">— select —</option>
        {prop.enum.map((option) => (
          <option key={String(option)} value={String(option)}>
            {String(option)}
          </option>
        ))}
      </select>
    );
  }
  switch (prop.type) {
    case "boolean":
      return (
        <Toggle
          checked={value === "true"}
          onChange={(checked) => onChange(String(checked))}
          label={value === "true" ? "true" : "false"}
        />
      );
    case "integer":
    case "number":
      return (
        <input
          className="form-input"
          type="number"
          step={prop.type === "integer" ? 1 : "any"}
          value={value}
          onChange={(e) => onChange(e.target.value)}
        />
      );
    case "object":
    case "array":
      return (
        <textarea
          className="form-input tool-form-textarea"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={prop.type === "array" ? '["…"]' : '{"…": "…"}'}
          rows={3}
          spellCheck={false}
        />
      );
    default:
      return (
        <input
          className="form-input"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={prop.description ?? name}
        />
      );
  }
}

/** Form values are strings; send typed values matching the schema where unambiguous. */
function coerce(raw: string, prop?: JsonSchemaProperty): unknown {
  if (!prop) return raw;
  switch (prop.type) {
    case "integer": {
      const n = parseInt(raw, 10);
      return Number.isNaN(n) ? raw : n;
    }
    case "number": {
      const n = parseFloat(raw);
      return Number.isNaN(n) ? raw : n;
    }
    case "boolean":
      return raw === "true";
    case "object":
    case "array":
      try {
        return JSON.parse(raw);
      } catch {
        return raw;
      }
    default:
      return raw;
  }
}
