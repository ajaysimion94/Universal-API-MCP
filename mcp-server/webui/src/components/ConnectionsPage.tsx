import { useCallback, useEffect, useRef, useState } from "react";
import {
  ApiToolInfo,
  AuthMode,
  ConnectionInfo,
  ConnectionType,
  CreateConnectionInput,
  UpdateConnectionInput,
  listConnections,
  createConnection,
  deleteConnection,
  triggerBackfill,
  enableConnection,
  disableConnection,
  getConnectionJob,
  importSpecFile,
  listTools,
  enableTool,
  disableTool,
  setToolKnowledgeSource,
  updateConnection,
  detectImportAuth,
} from "../api";
import {
  LinkIcon,
  PlusIcon,
  TrashIcon,
  CheckCircleIcon,
  AlertIcon,
  ChevronRightIcon,
  HashIcon,
  BookIcon,
  UploadIcon,
} from "../icons";
import { Toggle } from "./Toggle";

const CONNECTABLE_TYPES: ConnectionType[] = ["CONFLUENCE", "JIRA", "API_COLLECTION", "GITHUB"];

function authModeLabel(mode: AuthMode): string {
  switch (mode) {
    case "BASIC":
      return "Basic";
    case "BEARER":
      return "Bearer token";
    case "API_KEY_HEADER":
      return "API key header";
    default:
      return mode;
  }
}

function typeLabel(type: ConnectionType): string {
  switch (type) {
    case "CONFLUENCE":
      return "Confluence";
    case "JIRA":
      return "Jira";
    case "API_COLLECTION":
      return "API (Postman/OpenAPI)";
    case "GITHUB":
      return "GitHub";
    default:
      return "SharePoint";
  }
}

export function ConnectionsPage() {
  const [connections, setConnections] = useState<ConnectionInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);

  // connectionId -> jobId, for both verify-on-create/update and backfill jobs
  const [runningJobs, setRunningJobs] = useState<Record<string, string>>({});
  const [backfillProgress, setBackfillProgress] = useState<Record<string, { done: number; total: number }>>({});

  const load = useCallback(async () => {
    try {
      const data = await listConnections();
      setConnections(data);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load connections");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    const intervals: Record<string, ReturnType<typeof setInterval>> = {};
    for (const [connectionId, jobId] of Object.entries(runningJobs)) {
      intervals[connectionId] = setInterval(async () => {
        try {
          const job = await getConnectionJob(jobId);
          if (job.kind === "BACKFILL" && job.status === "running") {
            setBackfillProgress((prev) => ({
              ...prev,
              [connectionId]: { done: job.itemsProcessed ?? 0, total: job.itemsTotal ?? 0 },
            }));
          }
          if (job.status === "completed" || job.status === "failed") {
            clearInterval(intervals[connectionId]);
            setRunningJobs((prev) => {
              const next = { ...prev };
              delete next[connectionId];
              return next;
            });
            setBackfillProgress((prev) => {
              const next = { ...prev };
              delete next[connectionId];
              return next;
            });
            await load();
          }
        } catch {
          clearInterval(intervals[connectionId]);
        }
      }, 1500);
    }
    return () => {
      for (const interval of Object.values(intervals)) clearInterval(interval);
    };
  }, [runningJobs, load]);

  const registerJob = async (id: string, jobId: string) => {
    setRunningJobs((prev) => ({ ...prev, [id]: jobId }));
    setShowForm(false);
    await load();
  };

  const handleCreate = async (input: CreateConnectionInput) => {
    try {
      const { id, jobId } = await createConnection(input);
      await registerJob(id, jobId);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to create connection");
    }
  };

  const handleImportFile = async (input: Parameters<typeof importSpecFile>[0]) => {
    try {
      const { id, jobId } = await importSpecFile(input);
      await registerJob(id, jobId);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to import spec");
    }
  };

  const handleBackfill = async (connection: ConnectionInfo) => {
    try {
      const { jobId } = await triggerBackfill(connection.id);
      setRunningJobs((prev) => ({ ...prev, [connection.id]: jobId }));
      setBackfillProgress((prev) => ({ ...prev, [connection.id]: { done: 0, total: 0 } }));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to start backfill");
    }
  };

  const handleToggle = async (connection: ConnectionInfo, enabled: boolean) => {
    try {
      if (enabled) {
        await enableConnection(connection.id);
      } else {
        await disableConnection(connection.id);
      }
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to toggle connection");
    }
  };

  const handleUpdateAuth = async (connection: ConnectionInfo, input: UpdateConnectionInput) => {
    try {
      const { jobId } = await updateConnection(connection.id, input);
      setRunningJobs((prev) => ({ ...prev, [connection.id]: jobId }));
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to update auth");
    }
  };

  const handleDelete = async (connection: ConnectionInfo) => {
    const extra = connection.type === "API_COLLECTION" ? " and removes its tools" : "";
    if (!window.confirm(`Delete "${connection.name}"? This purges every chunk it ingested${extra}.`)) return;
    try {
      await deleteConnection(connection.id);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to delete connection");
    }
  };

  return (
    <div className="plugins-page">
      <div className="plugins-header connections-header">
        <div>
          <h1 className="plugins-title">
            <LinkIcon size={20} /> Connections
          </h1>
          <p className="plugins-subtitle">
            Ingest content from Confluence and Jira, or wire an application by importing its
            Postman collection / OpenAPI spec — every request becomes a callable tool.
          </p>
        </div>
        <button className="btn btn-primary" onClick={() => setShowForm((v) => !v)}>
          <PlusIcon size={14} />
          New connection
        </button>
      </div>

      {error && (
        <div className="error-banner" role="alert">
          {error}
          <button className="error-dismiss" onClick={() => setError(null)} aria-label="Dismiss">
            ×
          </button>
        </div>
      )}

      {showForm && (
        <ConnectionForm
          onCancel={() => setShowForm(false)}
          onSubmit={handleCreate}
          onImportFile={handleImportFile}
        />
      )}

      {loading ? (
        <div className="plugins-skeleton">
          {[0, 1].map((i) => (
            <div key={i} className="plugin-row-skeleton">
              <div className="skel-line skel-title" />
              <div className="skel-line skel-desc" />
            </div>
          ))}
        </div>
      ) : connections.length === 0 && !showForm ? (
        <p className="plugins-subtitle">
          No connections yet — add Confluence/Jira, or import a Postman collection / OpenAPI spec.
        </p>
      ) : (
        <div className="plugins-list">
          {connections.map((c) => (
            <div key={c.id}>
              <ConnectionRow
                connection={c}
                busy={!!runningJobs[c.id]}
                backfillProgress={backfillProgress[c.id]}
                onBackfill={() => handleBackfill(c)}
                onToggle={(enabled) => handleToggle(c, enabled)}
                onDelete={() => handleDelete(c)}
                onUpdateAuth={
                  c.type === "API_COLLECTION" ? (input) => handleUpdateAuth(c, input) : undefined
                }
              />
              {c.type === "API_COLLECTION" && (
                <ToolList connectionId={c.id} busy={!!runningJobs[c.id]} onError={setError} />
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function ConnectionForm({
  onCancel,
  onSubmit,
  onImportFile,
}: {
  onCancel: () => void;
  onSubmit: (input: CreateConnectionInput) => void;
  onImportFile: (input: Parameters<typeof importSpecFile>[0]) => void;
}) {
  const [type, setType] = useState<ConnectionType>("CONFLUENCE");
  const [name, setName] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  // API_COLLECTION fields
  const [specSource, setSpecSource] = useState<"url" | "file">("url");
  const [specUrl, setSpecUrl] = useState("");
  const [specFile, setSpecFile] = useState<File | null>(null);
  const [authMode, setAuthMode] = useState<AuthMode>("NONE");
  const [apiKeyHeader, setApiKeyHeader] = useState("X-Api-Key");
  const [detectingAuth, setDetectingAuth] = useState(false);
  const [authNotice, setAuthNotice] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const isApi = type === "API_COLLECTION";

  const runDetectAuth = async (input: { file?: File; specUrl?: string }) => {
    setDetectingAuth(true);
    setAuthNotice(null);
    try {
      const detected = await detectImportAuth(input);
      if (detected.authMode !== "NONE") {
        setAuthMode(detected.authMode);
        if (detected.authMode === "API_KEY_HEADER" && detected.username) {
          setApiKeyHeader(detected.username);
        } else if (detected.authMode === "BASIC" && detected.username) {
          setUsername(detected.username);
        }
        setAuthNotice(`Detected ${authModeLabel(detected.authMode)} auth from the collection — enter the secret below.`);
      } else {
        setAuthNotice("No auth detected in the collection — set it manually if needed.");
      }
    } catch {
      // best-effort only — detection failures never block manual setup
      setAuthNotice(null);
    } finally {
      setDetectingAuth(false);
    }
  };

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!isApi) {
      onSubmit({ type, name, baseUrl, username, password });
      return;
    }
    if (specSource === "file") {
      if (!specFile) return;
      onImportFile({
        file: specFile,
        name,
        baseUrl: baseUrl || undefined,
        authMode,
        username: authMode === "BASIC" ? username : undefined,
        password: authMode === "NONE" ? undefined : password,
        apiKeyHeader: authMode === "API_KEY_HEADER" ? apiKeyHeader : undefined,
      });
    } else {
      onSubmit({
        type,
        name,
        baseUrl,
        username: authMode === "BASIC" ? username : "",
        password: authMode === "NONE" ? "" : password,
        specUrl,
        authMode,
        apiKeyHeader: authMode === "API_KEY_HEADER" ? apiKeyHeader : undefined,
      });
    }
  };

  return (
    <form className="connection-form" onSubmit={submit}>
      <div className="form-row">
        <label className="form-field">
          <span>Type</span>
          <select className="form-input" value={type} onChange={(e) => setType(e.target.value as ConnectionType)}>
            {CONNECTABLE_TYPES.map((t) => (
              <option key={t} value={t}>
                {typeLabel(t)}
              </option>
            ))}
          </select>
        </label>
        <label className="form-field">
          <span>Name</span>
          <input
            className="form-input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={isApi ? "e.g. Todo App — becomes the @app slug" : "e.g. Engineering Confluence"}
            required
          />
        </label>
      </div>

      {isApi && (
        <>
          <div className="form-row">
            <label className="form-field">
              <span>Spec source</span>
              <select
                className="form-input"
                value={specSource}
                onChange={(e) => setSpecSource(e.target.value as "url" | "file")}
              >
                <option value="url">URL (spec or Swagger UI page)</option>
                <option value="file">Upload file (JSON or YAML)</option>
              </select>
            </label>
            {specSource === "url" ? (
              <label className="form-field">
                <span>Spec URL</span>
                <div className="spec-url-row">
                  <input
                    className="form-input"
                    value={specUrl}
                    onChange={(e) => setSpecUrl(e.target.value)}
                    placeholder="https://api.example.com/swagger-ui/index.html"
                    required
                  />
                  <button
                    type="button"
                    className="btn btn-ghost"
                    onClick={() => runDetectAuth({ specUrl })}
                    disabled={!specUrl.trim() || detectingAuth}
                    title="Fetch the spec and suggest an auth mode from it"
                  >
                    {detectingAuth ? "Detecting…" : "Detect auth"}
                  </button>
                </div>
              </label>
            ) : (
              <label className="form-field">
                <span>Spec file</span>
                <input
                  ref={fileInputRef}
                  type="file"
                  className="file-input-hidden"
                  accept=".json,.yaml,.yml,application/json"
                  onChange={(e) => {
                    const file = e.target.files?.[0] ?? null;
                    setSpecFile(file);
                    if (file) runDetectAuth({ file });
                  }}
                />
                <button
                  type="button"
                  className="btn btn-ghost spec-file-btn"
                  onClick={() => fileInputRef.current?.click()}
                >
                  <UploadIcon size={14} />
                  {detectingAuth
                    ? "Detecting auth…"
                    : specFile
                      ? specFile.name
                      : "Choose Postman collection / OpenAPI spec"}
                </button>
              </label>
            )}
          </div>
          {authNotice && <p className="connection-auth-notice">{authNotice}</p>}
          <label className="form-field">
            <span>API base URL (optional — derived from the spec when blank)</span>
            <input
              className="form-input"
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              placeholder="https://api.example.com"
            />
          </label>
          <div className="form-row">
            <label className="form-field">
              <span>Authentication</span>
              <select
                className="form-input"
                value={authMode}
                onChange={(e) => setAuthMode(e.target.value as AuthMode)}
              >
                <option value="NONE">None</option>
                <option value="BASIC">Basic (username + password)</option>
                <option value="BEARER">Bearer token</option>
                <option value="API_KEY_HEADER">API key header</option>
              </select>
            </label>
            {authMode === "BASIC" && (
              <label className="form-field">
                <span>Username</span>
                <input className="form-input" value={username} onChange={(e) => setUsername(e.target.value)} required />
              </label>
            )}
            {authMode === "API_KEY_HEADER" && (
              <label className="form-field">
                <span>Header name</span>
                <input
                  className="form-input"
                  value={apiKeyHeader}
                  onChange={(e) => setApiKeyHeader(e.target.value)}
                  required
                />
              </label>
            )}
          </div>
          {authMode !== "NONE" && (
            <label className="form-field">
              <span>{authMode === "BASIC" ? "Password" : authMode === "BEARER" ? "Token" : "API key"}</span>
              <input
                className="form-input"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            </label>
          )}
        </>
      )}

      {!isApi && (
        <>
          <label className="form-field">
            <span>Base URL</span>
            <input
              className="form-input"
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              placeholder="https://your-team.atlassian.net"
              required
            />
          </label>
          <div className="form-row">
            <label className="form-field">
              <span>Username</span>
              <input className="form-input" value={username} onChange={(e) => setUsername(e.target.value)} required />
            </label>
            <label className="form-field">
              <span>Password</span>
              <input
                className="form-input"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="Cloud: API token. Server/DC: password or PAT."
                required
              />
            </label>
          </div>
        </>
      )}

      <div className="form-actions">
        <button type="button" className="btn btn-ghost" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={isApi && specSource === "file" && !specFile}>
          {isApi ? "Import" : "Connect"}
        </button>
      </div>
    </form>
  );
}

/** Expandable, category-grouped list of the tools an API connection imported. */
function ToolList({
  connectionId,
  busy,
  onError,
}: {
  connectionId: string;
  busy: boolean;
  onError: (message: string) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [tools, setTools] = useState<ApiToolInfo[]>([]);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async () => {
    try {
      setTools(await listTools(undefined, connectionId));
      setLoaded(true);
    } catch (e) {
      onError(e instanceof Error ? e.message : "Failed to load tools");
    }
  }, [connectionId, onError]);

  useEffect(() => {
    // (re)load when expanded, and after the connection's import/backfill job finishes
    if (expanded && !busy) load();
  }, [expanded, busy, load]);

  const handleEnable = async (tool: ApiToolInfo, enabled: boolean) => {
    try {
      await (enabled ? enableTool(tool.id) : disableTool(tool.id));
      await load();
    } catch (e) {
      onError(e instanceof Error ? e.message : "Failed to update tool");
    }
  };

  const handleKnowledge = async (tool: ApiToolInfo, enabled: boolean) => {
    try {
      await setToolKnowledgeSource(tool.id, enabled);
      await load();
    } catch (e) {
      onError(e instanceof Error ? e.message : "Failed to update knowledge source");
    }
  };

  const categories = new Map<string, ApiToolInfo[]>();
  for (const tool of tools) {
    const list = categories.get(tool.category) ?? [];
    list.push(tool);
    categories.set(tool.category, list);
  }

  return (
    <div className="tool-list">
      <button
        className="tool-list-header"
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
      >
        <ChevronRightIcon size={14} className={`file-group-chevron ${expanded ? "chev-open" : ""}`} />
        <HashIcon size={14} />
        <span>
          {loaded
            ? `${tools.length} ${tools.length === 1 ? "tool" : "tools"} — ${tools.filter((t) => t.enabled).length} enabled, ${tools.filter((t) => t.pending).length} pending review`
            : "Tools"}
        </span>
      </button>

      {expanded &&
        Array.from(categories.entries()).map(([category, categoryTools]) => (
          <div key={category} className="tool-category">
            <div className="tool-category-name mono">{category}</div>
            {categoryTools.map((tool) => (
              <div key={tool.id} className="tool-row">
                <span className={`method-badge mono ${tool.method === "GET" ? "" : "method-write"}`}>
                  {tool.method}
                </span>
                <div className="tool-row-info">
                  <span className="tool-row-name mono">{tool.name}</span>
                  <span className="tool-row-desc">
                    {tool.displayName}
                    {tool.description && tool.description !== tool.displayName
                      ? ` — ${tool.description}`
                      : ""}
                  </span>
                </div>
                {tool.pending && <span className="tool-pending-badge mono">pending</span>}
                {tool.method === "GET" && tool.enabled && (
                  <span
                    className="tool-knowledge-toggle"
                    title="Knowledge source: invoke on a schedule and ingest the response into search"
                  >
                    <BookIcon size={13} />
                    <Toggle
                      checked={tool.knowledgeSource}
                      onChange={(v) => handleKnowledge(tool, v)}
                    />
                  </span>
                )}
                <Toggle
                  checked={tool.enabled}
                  onChange={(v) => handleEnable(tool, v)}
                  label={tool.enabled ? "Enabled" : "Off"}
                />
              </div>
            ))}
          </div>
        ))}
      {expanded && loaded && tools.length === 0 && (
        <p className="tool-list-empty">No tools imported yet — check the connection status.</p>
      )}
    </div>
  );
}

function ConnectionRow({
  connection,
  busy,
  backfillProgress,
  onBackfill,
  onToggle,
  onDelete,
  onUpdateAuth,
}: {
  connection: ConnectionInfo;
  busy: boolean;
  backfillProgress?: { done: number; total: number };
  onBackfill: () => void;
  onToggle: (enabled: boolean) => void;
  onDelete: () => void;
  onUpdateAuth?: (input: UpdateConnectionInput) => Promise<void>;
}) {
  const isApi = connection.type === "API_COLLECTION";
  const [editingAuth, setEditingAuth] = useState(false);
  return (
    <>
      <div className="plugin-row">
        <div className="plugin-info">
          <div className="plugin-name-row">
            <span className="plugin-name">{connection.name}</span>
            <span className="plugin-category mono optional">{typeLabel(connection.type)}</span>
            {isApi && connection.specFormat && (
              <span className="plugin-category mono optional">{connection.specFormat}</span>
            )}
            {!isApi && connection.deploymentType !== "UNKNOWN" && (
              <span className="plugin-category mono optional">
                {connection.deploymentType === "CLOUD" ? "Cloud" : "Server/DC"}
              </span>
            )}
          </div>
          <p className="plugin-description">{connection.baseUrl || connection.specSourceUrl}</p>
          <div className="plugin-health mono">
            {connection.status === "ERROR" && connection.lastError
              ? connection.lastError
              : connection.lastSyncedAt
                ? `Last synced ${new Date(connection.lastSyncedAt).toLocaleString()}`
                : isApi
                  ? "Knowledge index: not refreshed yet"
                  : "Content sync: not started yet"}
            {backfillProgress && backfillProgress.total > 0
              ? ` — ${isApi ? "refreshing" : "backfilling"} ${backfillProgress.done}/${backfillProgress.total}`
              : busy && !backfillProgress
                ? " — verifying…"
                : ""}
          </div>
        </div>

        <div className="plugin-status">
          <span
            className={`status-pill ${statusClass(connection.status)}`}
            aria-label={`Connection status: ${statusLabel(connection.status, busy)}`}
            title="Connection verification status"
          >
            {statusIcon(connection.status)}
            {statusLabel(connection.status, busy)}
          </span>
        </div>

        <div className="plugin-actions">
          {busy ? (
            <div className="install-progress">
              <div className="install-progress-bar" />
            </div>
          ) : (
            <button
              className="btn btn-ghost"
              onClick={onBackfill}
              disabled={connection.status !== "CONNECTED"}
              title={isApi ? "Invoke every knowledge-source tool now and refresh the index" : undefined}
            >
              {isApi ? "Refresh knowledge" : "Backfill"}
            </button>
          )}
          {onUpdateAuth && (
            <button
              className={`btn btn-ghost ${editingAuth ? "is-active" : ""}`}
              onClick={() => setEditingAuth((v) => !v)}
            >
              Edit auth
            </button>
          )}
          <Toggle
            checked={connection.status !== "DISABLED"}
            onChange={onToggle}
            label={connection.status === "DISABLED" ? "Disabled" : "Enabled"}
          />
          <button className="btn btn-ghost" onClick={onDelete} aria-label={`Delete ${connection.name}`}>
            <TrashIcon size={14} />
          </button>
        </div>
      </div>

      {editingAuth && onUpdateAuth && (
        <ConnectionAuthForm
          connection={connection}
          onCancel={() => setEditingAuth(false)}
          onSave={async (input) => {
            await onUpdateAuth(input);
            setEditingAuth(false);
          }}
        />
      )}
    </>
  );
}

/** Edits an API_COLLECTION connection's stored/default auth — the app-common tier (vs. a
 * per-request override in the request builder's Auth tab). Blank secret keeps the existing one. */
function ConnectionAuthForm({
  connection,
  onCancel,
  onSave,
}: {
  connection: ConnectionInfo;
  onCancel: () => void;
  onSave: (input: UpdateConnectionInput) => Promise<void>;
}) {
  const [authMode, setAuthMode] = useState<AuthMode>(connection.authMode);
  const [username, setUsername] = useState(connection.authUsername ?? "");
  const [secret, setSecret] = useState("");
  const [saving, setSaving] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    await onSave({
      authMode,
      username: authMode === "NONE" ? undefined : username || undefined,
      password: secret || undefined,
    });
    setSaving(false);
  };

  return (
    <form className="connection-form" onSubmit={submit}>
      <div className="form-row">
        <label className="form-field">
          <span>Authentication</span>
          <select className="form-input" value={authMode} onChange={(e) => setAuthMode(e.target.value as AuthMode)}>
            <option value="NONE">None</option>
            <option value="BASIC">Basic (username + password)</option>
            <option value="BEARER">Bearer token</option>
            <option value="API_KEY_HEADER">API key header</option>
          </select>
        </label>
        {authMode === "BASIC" && (
          <label className="form-field">
            <span>Username</span>
            <input className="form-input" value={username} onChange={(e) => setUsername(e.target.value)} required />
          </label>
        )}
        {authMode === "API_KEY_HEADER" && (
          <label className="form-field">
            <span>Header name</span>
            <input className="form-input" value={username} onChange={(e) => setUsername(e.target.value)} required />
          </label>
        )}
      </div>
      {authMode !== "NONE" && (
        <label className="form-field">
          <span>
            {authMode === "BASIC" ? "Password" : authMode === "BEARER" ? "Token" : "API key"} (leave
            blank to keep the existing one)
          </span>
          <input className="form-input" type="password" value={secret} onChange={(e) => setSecret(e.target.value)} />
        </label>
      )}
      <div className="form-actions">
        <button type="button" className="btn btn-ghost" onClick={onCancel} disabled={saving}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={saving}>
          {saving ? "Saving…" : "Save auth"}
        </button>
      </div>
    </form>
  );
}

function statusIcon(status: ConnectionInfo["status"]) {
  switch (status) {
    case "CONNECTED":
      return <CheckCircleIcon size={12} />;
    case "ERROR":
      return <AlertIcon size={12} />;
    default:
      return null;
  }
}

function statusLabel(status: ConnectionInfo["status"], busy: boolean) {
  if (busy && status === "PENDING") return "Verifying…";
  switch (status) {
    case "PENDING":
      return "Pending";
    case "CONNECTED":
      return "Connected";
    case "ERROR":
      return "Error";
    case "DISABLED":
      return "Disabled";
    default:
      return status;
  }
}

function statusClass(status: ConnectionInfo["status"]) {
  switch (status) {
    case "CONNECTED":
      return "status-active";
    case "ERROR":
      return "status-error";
    case "DISABLED":
      return "status-disabled";
    default:
      return "status-default";
  }
}
