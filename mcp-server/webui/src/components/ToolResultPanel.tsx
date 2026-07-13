import { ToolExecution } from "../api";
import { CheckCircleIcon, AlertIcon } from "../icons";

/** Rendered outcome of a tool execution: status, latency, and the (pretty-printed) response. */
export function ToolResultPanel({ toolName, result }: { toolName: string; result: ToolExecution }) {
  const ok = result.status >= 200 && result.status < 300;
  return (
    <div className="tool-panel">
      <div className="tool-panel-header">
        <span className={`status-pill ${ok ? "status-active" : "status-error"}`}>
          {ok ? <CheckCircleIcon size={12} /> : <AlertIcon size={12} />}
          HTTP {result.status}
        </span>
        <span className="tool-panel-name mono">{toolName}</span>
        <span className="tool-panel-meta mono">{result.latencyMs} ms</span>
      </div>
      <div className="tool-panel-request mono">{result.request}</div>
      {result.body && <pre className="tool-result-json">{result.body}</pre>}
      {result.truncated && (
        <p className="tool-panel-note">Response truncated for display.</p>
      )}
    </div>
  );
}
