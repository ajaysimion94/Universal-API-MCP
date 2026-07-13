import { useState } from "react";
import { ToolExecution, ToolPreview, ToolSummary, invokeTool } from "../api";
import { AlertIcon, PlayIcon } from "../icons";
import { ToolResultPanel } from "./ToolResultPanel";

/**
 * Preview→approve step for state-changing tools invoked from search (§5.8/§7.2): shows exactly
 * what would be sent; nothing executes until Run. Inline panel, not a modal.
 */
export function ToolConfirmPanel({
  tool,
  preview,
  args,
  onCancel,
}: {
  tool: ToolSummary;
  preview: ToolPreview;
  args: Record<string, unknown>;
  onCancel: () => void;
}) {
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ToolExecution | null>(null);

  const run = async () => {
    setRunning(true);
    setError(null);
    try {
      setResult(await invokeTool(tool.id, args));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Tool execution failed");
    } finally {
      setRunning(false);
    }
  };

  if (result) {
    return <ToolResultPanel toolName={tool.name} result={result} />;
  }

  return (
    <div className="tool-panel tool-confirm">
      <div className="tool-panel-header">
        <AlertIcon size={16} className="tool-confirm-icon" />
        <span className="tool-panel-name mono">{tool.name}</span>
        <span className="method-badge mono method-write">{preview.method}</span>
      </div>
      <p className="tool-panel-note">
        This tool changes state. Review the request, then run it — nothing has been sent yet.
      </p>
      <div className="tool-panel-request mono">
        {preview.method} {preview.url}
      </div>
      {preview.body && <pre className="tool-result-json">{prettyJson(preview.body)}</pre>}
      {error && (
        <div className="error-banner" role="alert">
          {error}
        </div>
      )}
      <div className="form-actions">
        <button type="button" className="btn btn-ghost" onClick={onCancel} disabled={running}>
          Cancel
        </button>
        <button type="button" className="btn btn-primary" onClick={run} disabled={running}>
          <PlayIcon size={13} />
          {running ? "Running…" : `Run ${preview.method}`}
        </button>
      </div>
    </div>
  );
}

function prettyJson(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}
