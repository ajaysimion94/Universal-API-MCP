import { useState, useEffect, useCallback } from "react";
import { ToolExecution, ToolPreview, ToolSummary, confirmTool, rejectTool } from "../api";
import { AlertIcon, CheckIcon, XIcon, ClockIcon } from "../icons";
import { ToolResultPanel } from "./ToolResultPanel";

/**
 * Preview→approve step for state-changing tools invoked from search (§5.8/§7.2): shows exactly
 * what would be sent. The workflow engine has already generated a single-use confirmation token;
 * Approve calls /api/tools/confirm/{token}, Reject calls /api/tools/reject/{token}.
 * Nothing executes until Approve. Token expiry countdown is displayed.
 */
export function ToolConfirmPanel({
  tool,
  preview,
  args,
  confirmationToken,
  tokenExpiresAt,
  onCancel,
}: {
  tool: ToolSummary;
  preview: ToolPreview;
  args: Record<string, unknown>;
  confirmationToken?: string;
  tokenExpiresAt?: string;
  onCancel: () => void;
}) {
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ToolExecution | null>(null);
  const [rejected, setRejected] = useState(false);
  const [secondsLeft, setSecondsLeft] = useState<number | null>(null);

  // Token expiry countdown
  useEffect(() => {
    if (!tokenExpiresAt) return;
    const expiresMs = new Date(tokenExpiresAt).getTime();
    const tick = () => {
      const remaining = Math.max(0, Math.floor((expiresMs - Date.now()) / 1000));
      setSecondsLeft(remaining);
    };
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [tokenExpiresAt]);

  const expired = secondsLeft !== null && secondsLeft <= 0;

  const approve = useCallback(async () => {
    if (!confirmationToken || expired) return;
    setRunning(true);
    setError(null);
    try {
      const res = await confirmTool(confirmationToken);
      if (res.state === "CONFIRMED" && res.result) {
        setResult(res.result);
      } else if (res.state === "FAILED") {
        setError(res.error ?? "Tool execution failed");
      } else {
        setError("Unexpected state: " + res.state);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Approval failed");
    } finally {
      setRunning(false);
    }
  }, [confirmationToken, expired]);

  const reject = useCallback(async () => {
    if (!confirmationToken) return;
    setRunning(true);
    setError(null);
    try {
      await rejectTool(confirmationToken);
      setRejected(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Rejection failed");
    } finally {
      setRunning(false);
    }
  }, [confirmationToken]);

  if (result) {
    return <ToolResultPanel toolName={tool.name} result={result} />;
  }

  if (rejected) {
    return (
      <div className="tool-panel tool-confirm">
        <div className="tool-panel-header">
          <XIcon size={16} className="tool-confirm-icon" />
          <span className="tool-panel-name mono">{tool.name}</span>
        </div>
        <p className="tool-panel-note" style={{ color: "var(--text-muted)" }}>
          Rejected — no action was taken. This has been recorded in the audit log.
        </p>
        <div className="form-actions" style={{ marginTop: "var(--space-sm)" }}>
          <button type="button" className="btn btn-ghost" onClick={onCancel}>
            Dismiss
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="tool-panel tool-confirm">
      <div className="tool-panel-header">
        <AlertIcon size={16} className="tool-confirm-icon" />
        <span className="tool-panel-name mono">{tool.name}</span>
        <span className="method-badge mono method-write">{preview.method}</span>
        {secondsLeft !== null && (
          <span
            className="mono"
            style={{
              marginLeft: "auto",
              fontSize: "0.75rem",
              color: expired ? "var(--danger)" : "var(--text-muted)",
              display: "flex",
              alignItems: "center",
              gap: "4px",
            }}
          >
            <ClockIcon size={12} />
            {expired ? "Expired" : `${Math.floor(secondsLeft / 60)}:${String(secondsLeft % 60).padStart(2, "0")}`}
          </span>
        )}
      </div>
      <p className="tool-panel-note">
        This tool changes state. Review the request, then approve it — nothing has been sent yet.
      </p>
      <div className="tool-panel-request mono">
        {preview.method} {preview.url}
      </div>
      {preview.headers && Object.keys(preview.headers).length > 0 && (
        <details style={{ marginTop: "var(--space-xs)" }}>
          <summary className="mono" style={{ fontSize: "0.75rem", color: "var(--text-muted)", cursor: "pointer" }}>
            Headers ({Object.keys(preview.headers).length})
          </summary>
          <div className="rb-kv-table rb-kv-table-readonly" style={{ marginTop: "var(--space-2xs)" }}>
            {Object.entries(preview.headers).map(([name, value]) => (
              <div key={name} className="rb-kv-row">
                <span className="rb-kv-key mono">{name}</span>
                <span className="rb-kv-value mono">{value}</span>
              </div>
            ))}
          </div>
        </details>
      )}
      {preview.body && <pre className="tool-result-json">{prettyJson(preview.body)}</pre>}
      {Object.keys(args).length > 0 && (
        <details style={{ marginTop: "var(--space-xs)" }}>
          <summary className="mono" style={{ fontSize: "0.75rem", color: "var(--text-muted)", cursor: "pointer" }}>
            Arguments
          </summary>
          <pre className="tool-result-json" style={{ marginTop: "var(--space-2xs)" }}>
            {JSON.stringify(args, null, 2)}
          </pre>
        </details>
      )}
      {error && (
        <div className="error-banner" role="alert">
          {error}
        </div>
      )}
      <div className="form-actions">
        <button type="button" className="btn btn-ghost" onClick={onCancel} disabled={running}>
          Cancel
        </button>
        <button type="button" className="btn btn-ghost" onClick={reject} disabled={running || expired}>
          <XIcon size={13} />
          Reject
        </button>
        <button type="button" className="btn btn-primary" onClick={approve} disabled={running || expired}>
          <CheckIcon size={13} />
          {running ? "Approving…" : expired ? "Token expired" : "Approve"}
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
