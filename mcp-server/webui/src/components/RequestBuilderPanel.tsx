import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ApiToolInfo,
  AuditEntry,
  JsonSchemaProperty,
  ManualParam,
  RequestOverrides,
  ToolExecution,
  ToolPreview,
  ToolViolation,
  WorkflowPreview,
  createManualTool,
  deleteManualTool,
  fetchAuditLog,
  invokeTool,
  isWorkflowPreview,
  previewTool,
  updateManualTool,
  updateToolAuth,
} from "../api";
import { ClockIcon, CodeIcon, HashIcon, PlayIcon, PlusIcon, TrashIcon } from "../icons";
import { Toggle } from "./Toggle";
import { ToolConfirmPanel } from "./ToolConfirmPanel";
import { ToolResultPanel } from "./ToolResultPanel";
import { CodeSnippetPanel } from "./CodeSnippetPanel";

type Tab = "params" | "headers" | "body" | "auth" | "history";
type BodyMode = "SCHEMA" | "NONE" | "RAW";
// OAUTH2 is a reserved, unimplemented mode (see AuthMode.java) — included only so a tool's raw
// authMode value type-checks; the dropdown never offers it as a selectable option.
type AuthMode = "INHERIT" | "NONE" | "BASIC" | "BEARER" | "API_KEY_HEADER" | "OAUTH2";

/** Query/header params editable in the manual-request form — path/body params are inferred, not listed here. */
function paramsFromTool(tool: ApiToolInfo): ManualParam[] {
  const props = tool.paramsSchema.properties ?? {};
  const required = new Set(tool.paramsSchema.required ?? []);
  const out: ManualParam[] = [];
  for (const [name, prop] of Object.entries(props)) {
    const loc = tool.paramLocations[name];
    if (loc !== "query" && loc !== "header") continue;
    out.push({
      name,
      in: loc,
      required: required.has(name),
      defaultValue: prop.default !== undefined ? String(prop.default) : "",
      description: prop.description ?? "",
    });
  }
  return out;
}

interface KvRow {
  key: string;
  value: string;
  enabled: boolean;
}

/**
 * Postman-style request panel. `tool === null` renders the compact "New request" builder
 * (saved immediately as a manual tool); otherwise renders the full run experience — resolved
 * URL, Params/Headers/Body/Auth tabs, Send, response with History and Code snippet.
 */
export function RequestBuilderPanel({
  tool,
  connectionId,
  prefill,
  missingRequired,
  violations,
  parseError,
  onSaved,
  onDeleted,
  onToggleEnable,
  onUpdated,
}: {
  tool: ApiToolInfo | null;
  connectionId?: string;
  prefill?: Record<string, unknown>;
  missingRequired?: string[];
  violations?: ToolViolation[];
  parseError?: string;
  onSaved?: (tool: ApiToolInfo) => void;
  onDeleted?: () => void;
  onToggleEnable?: (enabled: boolean) => void;
  onUpdated?: (tool: ApiToolInfo) => void;
}) {
  if (!tool) {
    return <ManualRequestForm connectionId={connectionId!} onSaved={onSaved} />;
  }
  return (
    <RunPanel
      tool={tool}
      prefill={prefill}
      missingRequired={missingRequired}
      violations={violations}
      parseError={parseError}
      onDeleted={onDeleted}
      onToggleEnable={onToggleEnable}
      onUpdated={onUpdated}
    />
  );
}

// ── Create mode ──────────────────────────────────────────────────────────────

const METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE"];

function ManualRequestForm({
  connectionId,
  initial,
  onSaved,
  onCancel,
}: {
  connectionId: string;
  initial?: ApiToolInfo;
  onSaved?: (tool: ApiToolInfo) => void;
  onCancel?: () => void;
}) {
  const [displayName, setDisplayName] = useState(initial?.displayName ?? "");
  const [method, setMethod] = useState(initial?.method ?? "GET");
  const [path, setPath] = useState(initial?.urlTemplate ?? "/");
  const [category, setCategory] = useState(initial?.category ?? "Manual");
  const [description, setDescription] = useState(initial?.description ?? "");
  const [params, setParams] = useState<ManualParam[]>(() => (initial ? paramsFromTool(initial) : []));
  const [bodyTemplate, setBodyTemplate] = useState(initial?.bodyTemplate ?? "");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const addParam = () =>
    setParams((prev) => [...prev, { name: "", in: "query", required: false, defaultValue: "", description: "" }]);
  const updateParam = (i: number, patch: Partial<ManualParam>) =>
    setParams((prev) => prev.map((p, idx) => (idx === i ? { ...p, ...patch } : p)));
  const removeParam = (i: number) => setParams((prev) => prev.filter((_, idx) => idx !== i));

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!displayName.trim() || !path.trim()) return;
    setSaving(true);
    setError(null);
    try {
      const shape = {
        displayName: displayName.trim(),
        method,
        path: path.trim(),
        category: category.trim() || "Manual",
        description: description.trim() || undefined,
        params: params.filter((p) => p.name.trim()),
        bodyTemplate: bodyTemplate.trim() || undefined,
      };
      const tool = initial
        ? await updateManualTool(initial.id, shape)
        : await createManualTool({ connectionId, ...shape });
      onSaved?.(tool);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to save request");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="tool-panel">
      <div className="tool-panel-header">
        <HashIcon size={16} className="tool-result-icon" />
        <span className="tool-panel-name mono">{initial ? "Edit request" : "New request"}</span>
      </div>
      {error && (
        <div className="error-banner" role="alert">
          {error}
        </div>
      )}
      <form className="tool-form" onSubmit={submit}>
        <div className="rb-url-bar">
          <select
            className="form-input rb-method-select"
            value={method}
            onChange={(e) => setMethod(e.target.value)}
            aria-label="HTTP method"
          >
            {METHODS.map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </select>
          <input
            className="form-input rb-url-input"
            value={path}
            onChange={(e) => setPath(e.target.value)}
            placeholder="/users/{id}"
            aria-label="Request path"
            spellCheck={false}
            required
          />
        </div>
        <label className="form-field tool-form-field">
          <span>Name</span>
          <input
            className="form-input"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder="e.g. Get user by ID"
            required
          />
        </label>
        <label className="form-field tool-form-field">
          <span>Category</span>
          <input className="form-input" value={category} onChange={(e) => setCategory(e.target.value)} />
        </label>
        <label className="form-field tool-form-field">
          <span>Description</span>
          <input className="form-input" value={description} onChange={(e) => setDescription(e.target.value)} />
        </label>

        <div className="rb-manual-params">
          <div className="rb-manual-params-head">
            <span>Query / header params</span>
            <button type="button" className="btn btn-ghost" onClick={addParam}>
              <PlusIcon size={12} />
              Add param
            </button>
          </div>
          {params.map((p, i) => (
            <div key={i} className="rb-kv-row rb-manual-param-row">
              <input
                className="form-input"
                value={p.name}
                onChange={(e) => updateParam(i, { name: e.target.value })}
                placeholder="name"
                aria-label={`Parameter ${i + 1} name`}
              />
              <select
                className="form-input"
                value={p.in}
                onChange={(e) => updateParam(i, { in: e.target.value as "query" | "header" })}
                aria-label={`Parameter ${i + 1} location`}
              >
                <option value="query">query</option>
                <option value="header">header</option>
              </select>
              <input
                className="form-input"
                value={p.defaultValue ?? ""}
                onChange={(e) => updateParam(i, { defaultValue: e.target.value })}
                placeholder="default (optional)"
                aria-label={`Parameter ${i + 1} default value`}
              />
              <label className="rb-required-check">
                <input
                  type="checkbox"
                  checked={p.required}
                  onChange={(e) => updateParam(i, { required: e.target.checked })}
                />
                required
              </label>
              <button
                type="button"
                className="btn btn-ghost rb-icon-btn"
                onClick={() => removeParam(i)}
                aria-label="Remove param"
              >
                <TrashIcon size={13} />
              </button>
            </div>
          ))}
        </div>

        <label className="form-field tool-form-field">
          <span>Body template (raw JSON, optional)</span>
          <textarea
            className="form-input tool-form-textarea"
            value={bodyTemplate}
            onChange={(e) => setBodyTemplate(e.target.value)}
            rows={4}
            spellCheck={false}
            placeholder='{"name": ""}'
          />
        </label>

        <div className="form-actions">
          {onCancel && (
            <button type="button" className="btn btn-ghost" onClick={onCancel} disabled={saving}>
              Cancel
            </button>
          )}
          <button type="submit" className="btn btn-primary" disabled={saving}>
            {saving ? "Saving…" : initial ? "Save changes" : "Save request"}
          </button>
        </div>
      </form>
    </div>
  );
}

// ── Run mode ──────────────────────────────────────────────────────────────────

function RunPanel({
  tool,
  prefill,
  missingRequired,
  violations,
  parseError,
  onDeleted,
  onToggleEnable,
  onUpdated,
}: {
  tool: ApiToolInfo;
  prefill?: Record<string, unknown>;
  missingRequired?: string[];
  violations?: ToolViolation[];
  parseError?: string;
  onDeleted?: () => void;
  onToggleEnable?: (enabled: boolean) => void;
  onUpdated?: (tool: ApiToolInfo) => void;
}) {
  const properties = tool.paramsSchema.properties ?? {};
  const required = new Set(tool.paramsSchema.required ?? []);
  const isRead = tool.method === "GET";

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

  const [tab, setTab] = useState<Tab>("params");
  const [extraQuery, setExtraQuery] = useState<KvRow[]>([{ key: "", value: "", enabled: true }]);
  const [extraHeaders, setExtraHeaders] = useState<KvRow[]>([{ key: "", value: "", enabled: true }]);
  const [bodyMode, setBodyMode] = useState<BodyMode>("SCHEMA");
  const [rawBody, setRawBody] = useState("");
  const [rawContentType, setRawContentType] = useState("application/json");

  // Persisted per-request auth override (Save button below) — inherited (null) by default.
  const [authMode, setAuthMode] = useState<AuthMode>(tool.authMode ?? "INHERIT");
  const [authUsername, setAuthUsername] = useState(tool.authUsername ?? "");
  const [authSecret, setAuthSecret] = useState("");
  const [authSaving, setAuthSaving] = useState(false);
  const [authNotice, setAuthNotice] = useState<string | null>(null);

  const [editingShape, setEditingShape] = useState(false);

  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(parseError ?? null);
  const [result, setResult] = useState<ToolExecution | null>(null);
  const [preview, setPreview] = useState<WorkflowPreview | null>(null);
  const [showCode, setShowCode] = useState(false);
  const [resolved, setResolved] = useState<ToolPreview | null>(null);

  const [history, setHistory] = useState<AuditEntry[] | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);

  const violationFor = (name: string) => violations?.find((v) => v.param === name)?.message;

  const overrides: RequestOverrides = useMemo(() => {
    const extraHeadersMap: Record<string, string> = {};
    for (const row of extraHeaders) if (row.enabled && row.key.trim()) extraHeadersMap[row.key.trim()] = row.value;
    const extraQueryMap: Record<string, string> = {};
    for (const row of extraQuery) if (row.enabled && row.key.trim()) extraQueryMap[row.key.trim()] = row.value;
    return {
      extraHeaders: extraHeadersMap,
      extraQueryParams: extraQueryMap,
      bodyMode,
      rawBody: bodyMode === "RAW" ? rawBody : undefined,
      rawContentType: bodyMode === "RAW" ? rawContentType : undefined,
    };
  }, [extraHeaders, extraQuery, bodyMode, rawBody, rawContentType]);

  const saveAuth = async () => {
    setAuthSaving(true);
    setError(null);
    setAuthNotice(null);
    try {
      const updated = await updateToolAuth(tool.id, {
        mode: authMode === "INHERIT" ? undefined : authMode,
        username: authUsername || undefined,
        secret: authSecret || undefined,
      });
      setAuthSecret("");
      setAuthNotice(authMode === "INHERIT" ? "Cleared — inheriting from the app" : "Saved");
      onUpdated?.(updated);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to save auth");
    } finally {
      setAuthSaving(false);
    }
  };

  const buildArgs = useCallback((): Record<string, unknown> => {
    const args: Record<string, unknown> = {};
    for (const [name, raw] of Object.entries(values)) {
      if (raw === "") continue;
      args[name] = coerce(raw, properties[name]);
    }
    return args;
  }, [values, properties]);

  // Live "resolved request" readout — debounced dry-run preview, never executes.
  const previewTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (previewTimer.current) clearTimeout(previewTimer.current);
    previewTimer.current = setTimeout(() => {
      previewTool(tool.id, buildArgs(), overrides)
        .then(setResolved)
        .catch(() => setResolved(null));
    }, 350);
    return () => {
      if (previewTimer.current) clearTimeout(previewTimer.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tool.id, values, overrides]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setRunning(true);
    setError(null);
    try {
      const res = await invokeTool(tool.id, buildArgs(), overrides);
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

  const loadHistory = () => {
    if (history !== null || historyLoading) return;
    setHistoryLoading(true);
    fetchAuditLog({ toolName: tool.name, size: 20 })
      .then((res) => setHistory(res.items))
      .catch(() => setHistory([]))
      .finally(() => setHistoryLoading(false));
  };

  const rerunFromHistory = (entry: AuditEntry) => {
    if (!entry.arguments) return;
    try {
      const parsed = JSON.parse(entry.arguments) as Record<string, unknown>;
      setValues((prev) => {
        const next = { ...prev };
        for (const [k, v] of Object.entries(parsed)) {
          if (k in next) next[k] = typeof v === "string" ? v : JSON.stringify(v);
        }
        return next;
      });
      setTab("params");
    } catch {
      // stored args weren't valid JSON — nothing to replay
    }
  };

  const deleteTool = async () => {
    if (!window.confirm(`Delete "${tool.displayName}"? This can't be undone.`)) return;
    try {
      await deleteManualTool(tool.id);
      onDeleted?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to delete request");
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

  if (editingShape) {
    return (
      <ManualRequestForm
        connectionId={tool.connectionId}
        initial={tool}
        onSaved={(updated) => {
          setEditingShape(false);
          onUpdated?.(updated);
        }}
        onCancel={() => setEditingShape(false)}
      />
    );
  }

  return (
    <div className="tool-panel-stack">
    <div className="tool-panel rb-panel">
      <div className="tool-panel-header">
        <HashIcon size={16} className="tool-result-icon" />
        <span className="tool-panel-name mono">{tool.name}</span>
        <span className={`method-badge mono ${tool.method === "GET" ? "" : "method-write"} ${tool.method === "DELETE" ? "method-danger" : ""}`}>
          {tool.method}
        </span>
        {tool.pending && <span className="tool-pending-badge mono">pending</span>}
        {tool.origin === "MANUAL" && <span className="tool-pending-badge mono">manual</span>}
        <div className="rb-header-actions">
          {onToggleEnable && (
            <Toggle
              checked={tool.enabled}
              onChange={onToggleEnable}
              label={tool.enabled ? "Enabled" : "Off"}
            />
          )}
          {tool.origin === "MANUAL" && (
            <>
              <button type="button" className="btn btn-ghost" onClick={() => setEditingShape(true)} title="Edit this request">
                Edit
              </button>
              <button type="button" className="btn btn-ghost rb-icon-btn" onClick={deleteTool} title="Delete this request">
                <TrashIcon size={13} />
              </button>
            </>
          )}
        </div>
      </div>
      {tool.description && <p className="tool-panel-description">{tool.description}</p>}
      <p className="tool-panel-note">
        {missingRequired && missingRequired.length > 0
          ? `Missing: ${missingRequired.join(", ")} — fill in the inputs to run.`
          : "Resolved request updates live as you edit."}
      </p>

      <div className="rb-url-bar rb-url-bar-readout">
        <span className={`method-badge mono ${isRead ? "" : "method-write"}`}>{tool.method}</span>
        <span className="rb-url-readout mono">{resolved?.url ?? tool.urlTemplate}</span>
        <button type="button" className="btn btn-ghost" onClick={() => setShowCode((v) => !v)} title="View as code">
          <CodeIcon size={13} />
          Code
        </button>
      </div>

      {showCode && resolved && <CodeSnippetPanel preview={resolved} onClose={() => setShowCode(false)} />}

      {error && (
        <div className="error-banner" role="alert">
          {error}
        </div>
      )}

      <div className="rb-tabs">
        <button type="button" className={`rb-tab ${tab === "params" ? "is-active" : ""}`} onClick={() => setTab("params")}>
          Params
        </button>
        <button type="button" className={`rb-tab ${tab === "headers" ? "is-active" : ""}`} onClick={() => setTab("headers")}>
          Headers
        </button>
        <button type="button" className={`rb-tab ${tab === "body" ? "is-active" : ""}`} onClick={() => setTab("body")}>
          Body
        </button>
        <button
          type="button"
          className={`rb-tab ${tab === "auth" ? "is-active" : ""}`}
          onClick={() => setTab("auth")}
        >
          Auth
        </button>
        <button
          type="button"
          className={`rb-tab ${tab === "history" ? "is-active" : ""}`}
          onClick={() => {
            setTab("history");
            loadHistory();
          }}
        >
          <ClockIcon size={12} /> History
        </button>
      </div>

      <form className="tool-form" onSubmit={submit}>
        {tab === "params" && (
          <div className="rb-tab-body">
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
                {violationFor(name) && <span className="tool-form-violation">{violationFor(name)}</span>}
              </label>
            ))}
            {Object.keys(properties).length === 0 && (
              <p className="tool-panel-note">No schema params — add ad-hoc query params below.</p>
            )}
            <div className="rb-manual-params-head">
              <span>Ad-hoc query params</span>
            </div>
            <KvEditor rows={extraQuery} onChange={setExtraQuery} placeholder="param name" />
          </div>
        )}

        {tab === "headers" && (
          <div className="rb-tab-body">
            <p className="tool-panel-note">Custom headers layered on top of this tool's normal request.</p>
            <KvEditor rows={extraHeaders} onChange={setExtraHeaders} placeholder="Header-Name" />
          </div>
        )}

        {tab === "body" && (
          <div className="rb-tab-body">
            <div className="rb-body-mode">
              {(["SCHEMA", "NONE", "RAW"] as BodyMode[]).map((m) => (
                <label key={m} className="rb-body-mode-option">
                  <input type="radio" name="bodyMode" checked={bodyMode === m} onChange={() => setBodyMode(m)} />
                  {m === "SCHEMA" ? "Default (JSON from params)" : m === "NONE" ? "None" : "Raw"}
                </label>
              ))}
            </div>
            {bodyMode === "RAW" && (
              <>
                <select
                  className="form-input"
                  value={rawContentType}
                  onChange={(e) => setRawContentType(e.target.value)}
                  aria-label="Raw body content type"
                >
                  <option value="application/json">application/json</option>
                  <option value="application/xml">application/xml</option>
                  <option value="text/plain">text/plain</option>
                  <option value="application/x-www-form-urlencoded">application/x-www-form-urlencoded</option>
                </select>
                <textarea
                  className="form-input tool-form-textarea"
                  value={rawBody}
                  onChange={(e) => setRawBody(e.target.value)}
                  rows={8}
                  spellCheck={false}
                  placeholder="Raw request body"
                  aria-label="Raw request body"
                />
              </>
            )}
          </div>
        )}

        {tab === "auth" && (
          <div className="rb-tab-body">
            <p className="tool-panel-note">
              Saved auth for this request only — overrides the app's default for every invocation
              (GET and write alike). Leave as "Inherit" to use the app's auth.
            </p>
            <select
              className="form-input"
              value={authMode}
              onChange={(e) => setAuthMode(e.target.value as AuthMode)}
              aria-label="Request authentication mode"
            >
              <option value="INHERIT">Inherit from connection</option>
              <option value="NONE">None</option>
              <option value="BASIC">Basic</option>
              <option value="BEARER">Bearer</option>
              <option value="API_KEY_HEADER">API Key (header)</option>
            </select>
            {authMode === "BASIC" && (
              <>
                <input
                  className="form-input"
                  placeholder="Username"
                  value={authUsername}
                  onChange={(e) => setAuthUsername(e.target.value)}
                  aria-label="Basic authentication username"
                />
                <input
                  className="form-input"
                  type="password"
                  placeholder={tool.authMode === "BASIC" ? "Password (leave blank to keep existing)" : "Password"}
                  value={authSecret}
                  onChange={(e) => setAuthSecret(e.target.value)}
                  aria-label="Basic authentication password"
                />
              </>
            )}
            {authMode === "BEARER" && (
              <input
                className="form-input"
                type="password"
                placeholder={tool.authMode === "BEARER" ? "Token (leave blank to keep existing)" : "Token"}
                value={authSecret}
                onChange={(e) => setAuthSecret(e.target.value)}
                aria-label="Bearer token"
              />
            )}
            {authMode === "API_KEY_HEADER" && (
              <>
                <input
                  className="form-input"
                  placeholder="Header name (e.g. X-Api-Key)"
                  value={authUsername}
                  onChange={(e) => setAuthUsername(e.target.value)}
                  aria-label="API key header name"
                />
                <input
                  className="form-input"
                  type="password"
                  placeholder={tool.authMode === "API_KEY_HEADER" ? "Key (leave blank to keep existing)" : "Key"}
                  value={authSecret}
                  onChange={(e) => setAuthSecret(e.target.value)}
                  aria-label="API key value"
                />
              </>
            )}
            <div className="form-actions">
              {authNotice && <span className="tool-panel-note rb-auth-notice">{authNotice}</span>}
              <button type="button" className="btn btn-primary" onClick={saveAuth} disabled={authSaving}>
                {authSaving ? "Saving…" : "Save auth"}
              </button>
            </div>
          </div>
        )}

        {tab === "history" && (
          <div className="rb-tab-body">
            {historyLoading ? (
              <p className="tool-panel-note">Loading…</p>
            ) : !history || history.length === 0 ? (
              <p className="tool-panel-note">No prior invocations recorded yet.</p>
            ) : (
              <div className="rb-history-list">
                {history.map((entry) => (
                  <button
                    type="button"
                    key={entry.id}
                    className="rb-history-row"
                    onClick={() => rerunFromHistory(entry)}
                    disabled={!entry.arguments}
                  >
                    <span className="mono rb-history-event">{entry.eventType}</span>
                    <span className="rb-history-summary">{entry.resultSummary ?? entry.error ?? "—"}</span>
                    <span className="mono rb-history-time">{new Date(entry.createdAt).toLocaleString()}</span>
                  </button>
                ))}
              </div>
            )}
          </div>
        )}

        <div className="form-actions">
          {result && (
            <button type="button" className="btn btn-ghost" onClick={() => setResult(null)}>
              Clear response
            </button>
          )}
          <button type="submit" className="btn btn-primary" disabled={running || !tool.enabled}>
            <PlayIcon size={13} />
            {running ? "Sending…" : `Send ${tool.method}`}
          </button>
        </div>
      </form>
    </div>
    {result && <ToolResultPanel toolName={tool.name} result={result} />}
    </div>
  );
}

function KvEditor({
  rows,
  onChange,
  placeholder,
}: {
  rows: KvRow[];
  onChange: (rows: KvRow[]) => void;
  placeholder: string;
}) {
  const update = (i: number, patch: Partial<KvRow>) => {
    const next = rows.map((r, idx) => (idx === i ? { ...r, ...patch } : r));
    const last = next[next.length - 1];
    if (last && (last.key || last.value)) next.push({ key: "", value: "", enabled: true });
    onChange(next);
  };
  const remove = (i: number) => {
    const next = rows.filter((_, idx) => idx !== i);
    onChange(next.length > 0 ? next : [{ key: "", value: "", enabled: true }]);
  };

  return (
    <div className="rb-kv-table">
      {rows.map((row, i) => (
        <div key={i} className="rb-kv-row rb-kv-row-editable">
          <input
            type="checkbox"
            checked={row.enabled}
            onChange={(e) => update(i, { enabled: e.target.checked })}
            aria-label={`Row ${i + 1} enabled`}
          />
          <input
            className="form-input"
            value={row.key}
            onChange={(e) => update(i, { key: e.target.value })}
            placeholder={placeholder}
            aria-label={`Row ${i + 1} name`}
          />
          <input
            className="form-input"
            value={row.value}
            onChange={(e) => update(i, { value: e.target.value })}
            placeholder="value"
            aria-label={`Row ${i + 1} value`}
          />
          {(row.key || row.value) && (
            <button type="button" className="btn btn-ghost rb-icon-btn" onClick={() => remove(i)} aria-label="Remove">
              <TrashIcon size={13} />
            </button>
          )}
        </div>
      ))}
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
      return <Toggle checked={value === "true"} onChange={(checked) => onChange(String(checked))} label={value === "true" ? "true" : "false"} />;
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
      return <input className="form-input" value={value} onChange={(e) => onChange(e.target.value)} placeholder={prop.description ?? name} />;
  }
}

/** Form values are strings; send "123" as 123 and "true" as true where unambiguous. */
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
