import React, { useState } from "react";
import {
  connectChatCredentials,
  clearChatCredentials,
  ChatCredentialStatus,
} from "../api";
import { AlertIcon, CheckCircleIcon, XIcon } from "../icons";

/**
 * "Connect Copilot" modal: paste signed-in credentials (accessToken and/or raw Cookie
 * header), validate them live against the Copilot chat socket, and surface the result.
 * The server keeps them in memory only — the dialog says so, and points at the env-var
 * alternative for durability. Secrets stay in component state and are cleared on close.
 */
export function ConnectCopilotDialog({
  status,
  onClose,
  onStatusChange,
}: {
  status: ChatCredentialStatus | null;
  onClose: () => void;
  onStatusChange: (s: ChatCredentialStatus) => void;
}) {
  const [accessToken, setAccessToken] = useState("");
  const [identityType, setIdentityType] = useState("");
  const [cookies, setCookies] = useState("");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ChatCredentialStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  const canSubmit = !busy && (accessToken.trim() !== "" || cookies.trim() !== "");

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const next = await connectChatCredentials({
        accessToken: accessToken.trim() || undefined,
        identityType: identityType.trim() || undefined,
        cookies: cookies.trim() || undefined,
      });
      setResult(next);
      onStatusChange(next);
      if (next.ok) {
        // Leave the success visible briefly via the result banner; secrets wiped immediately.
        setAccessToken("");
        setCookies("");
        setIdentityType("");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Request failed");
    } finally {
      setBusy(false);
    }
  };

  const clear = async () => {
    setBusy(true);
    setError(null);
    try {
      const next = await clearChatCredentials();
      setResult(next);
      onStatusChange(next);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Request failed");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose} role="presentation">
      <div
        className="modal-panel"
        role="dialog"
        aria-modal="true"
        aria-label="Connect Copilot"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal-header">
          <span className="modal-title">Connect Copilot</span>
          <button type="button" className="btn btn-sm modal-close" onClick={onClose} aria-label="Close">
            <XIcon size={14} />
          </button>
        </div>

        <p className="modal-text">
          Answer generation rides your signed-in <code>copilot.microsoft.com</code> session
          (Microsoft blocks anonymous chat). Paste credentials from your browser: DevTools →
          Network → send a Copilot message → the <code>wss://…/c/api/chat</code> request → copy
          its <code>accessToken</code> query param — or copy the raw <code>Cookie</code> header of
          any <code>/c/api/</code> request.
        </p>

        <form onSubmit={submit} className="modal-form">
          <label className="modal-field">
            <span className="modal-label">Access token</span>
            <textarea
              className="modal-textarea mono"
              rows={3}
              value={accessToken}
              onChange={(e) => setAccessToken(e.target.value)}
              placeholder="eyJ…"
              autoComplete="off"
              spellCheck={false}
            />
          </label>
          <label className="modal-field">
            <span className="modal-label">Identity type (optional, e.g. msa)</span>
            <input
              className="modal-input mono"
              type="text"
              value={identityType}
              onChange={(e) => setIdentityType(e.target.value)}
              autoComplete="off"
              spellCheck={false}
            />
          </label>
          <label className="modal-field">
            <span className="modal-label">…or raw Cookie header</span>
            <textarea
              className="modal-textarea mono"
              rows={3}
              value={cookies}
              onChange={(e) => setCookies(e.target.value)}
              placeholder="MUID=…; _C_Auth=…; …"
              autoComplete="off"
              spellCheck={false}
            />
          </label>

          {error && (
            <div className="error-banner" role="alert">
              <AlertIcon size={14} /> {error}
            </div>
          )}
          {result && (
            <div className={result.ok ? "success-banner" : "error-banner"} role="status">
              {result.ok ? <CheckCircleIcon size={14} /> : <AlertIcon size={14} />} {result.message}
            </div>
          )}

          <div className="modal-actions">
            {status?.configured && status.source === "runtime" && (
              <button type="button" className="btn btn-sm" onClick={clear} disabled={busy}>
                Disconnect
              </button>
            )}
            <span className="modal-note">
              Kept in memory until restart. Use env vars for permanence.
            </span>
            <button type="submit" className="btn btn-primary" disabled={!canSubmit}>
              {busy ? "Validating…" : "Validate & connect"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
