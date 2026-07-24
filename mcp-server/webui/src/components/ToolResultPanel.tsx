import { useState } from "react";
import { ToolExecution } from "../api";
import { CheckCircleIcon, AlertIcon } from "../icons";

/** Rendered outcome of a tool execution: status, latency, size, and a Body/Headers tab split. */
export function ToolResultPanel({ toolName, result }: { toolName: string; result: ToolExecution }) {
  const [tab, setTab] = useState<"body" | "headers">("body");
  const ok = result.status >= 200 && result.status < 300;
  const headerEntries = Object.entries(result.headers ?? {});
  const sizeLabel = formatBytes(new Blob([result.body ?? ""]).size);

  return (
    <div className="tool-panel">
      <div className="tool-panel-header">
        <span className={`status-pill ${ok ? "status-active" : "status-error"}`}>
          {ok ? <CheckCircleIcon size={12} /> : <AlertIcon size={12} />}
          HTTP {result.status}
        </span>
        <span className="tool-panel-name mono">{toolName}</span>
        <span className="tool-panel-meta mono">
          {result.latencyMs} ms · {sizeLabel}
        </span>
      </div>
      <div className="tool-panel-request mono">{result.request}</div>

      <div className="rb-tabs rb-tabs-compact">
        <button
          type="button"
          className={`rb-tab ${tab === "body" ? "is-active" : ""}`}
          onClick={() => setTab("body")}
        >
          Body
        </button>
        <button
          type="button"
          className={`rb-tab ${tab === "headers" ? "is-active" : ""}`}
          onClick={() => setTab("headers")}
        >
          Headers{headerEntries.length > 0 ? ` (${headerEntries.length})` : ""}
        </button>
      </div>

      {tab === "body" ? (
        result.body ? (
          <pre className="tool-result-json">{result.body}</pre>
        ) : (
          <p className="tool-panel-note">Empty response body.</p>
        )
      ) : headerEntries.length > 0 ? (
        <div className="rb-kv-table rb-kv-table-readonly">
          {headerEntries.map(([name, value]) => (
            <div key={name} className="rb-kv-row">
              <span className="rb-kv-key mono">{name}</span>
              <span className="rb-kv-value mono">{value}</span>
            </div>
          ))}
        </div>
      ) : (
        <p className="tool-panel-note">No response headers captured.</p>
      )}

      {result.truncated && <p className="tool-panel-note">Response truncated for display.</p>}
    </div>
  );
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}
