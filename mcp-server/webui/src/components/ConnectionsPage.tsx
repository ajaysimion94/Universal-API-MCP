import { useCallback, useEffect, useState } from "react";
import {
  ConnectionInfo,
  ConnectionType,
  CreateConnectionInput,
  listConnections,
  createConnection,
  deleteConnection,
  triggerBackfill,
  enableConnection,
  disableConnection,
  getConnectionJob,
} from "../api";
import { LinkIcon, PlusIcon, TrashIcon, CheckCircleIcon, AlertIcon } from "../icons";
import { Toggle } from "./Toggle";

const CONNECTABLE_TYPES: ConnectionType[] = ["CONFLUENCE", "JIRA"];

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

  const handleCreate = async (input: CreateConnectionInput) => {
    try {
      const { id, jobId } = await createConnection(input);
      setRunningJobs((prev) => ({ ...prev, [id]: jobId }));
      setShowForm(false);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to create connection");
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

  const handleDelete = async (connection: ConnectionInfo) => {
    if (!window.confirm(`Delete "${connection.name}"? This purges every chunk it ingested.`)) return;
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
            Ingest content from Confluence and Jira — credentials, sync status, and backfill.
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
        <ConnectionForm onCancel={() => setShowForm(false)} onSubmit={handleCreate} />
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
        <p className="plugins-subtitle">No connections yet — add a Confluence or Jira connection to start ingesting.</p>
      ) : (
        <div className="plugins-list">
          {connections.map((c) => (
            <ConnectionRow
              key={c.id}
              connection={c}
              busy={!!runningJobs[c.id]}
              backfillProgress={backfillProgress[c.id]}
              onBackfill={() => handleBackfill(c)}
              onToggle={(enabled) => handleToggle(c, enabled)}
              onDelete={() => handleDelete(c)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function ConnectionForm({
  onCancel,
  onSubmit,
}: {
  onCancel: () => void;
  onSubmit: (input: CreateConnectionInput) => void;
}) {
  const [type, setType] = useState<ConnectionType>("CONFLUENCE");
  const [name, setName] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");

  return (
    <form
      className="connection-form"
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit({ type, name, baseUrl, username, password });
      }}
    >
      <div className="form-row">
        <label className="form-field">
          <span>Type</span>
          <select className="form-input" value={type} onChange={(e) => setType(e.target.value as ConnectionType)}>
            {CONNECTABLE_TYPES.map((t) => (
              <option key={t} value={t}>
                {t === "CONFLUENCE" ? "Confluence" : "Jira"}
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
            placeholder="e.g. Engineering Confluence"
            required
          />
        </label>
      </div>
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
      <div className="form-actions">
        <button type="button" className="btn btn-ghost" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary">
          Connect
        </button>
      </div>
    </form>
  );
}

function ConnectionRow({
  connection,
  busy,
  backfillProgress,
  onBackfill,
  onToggle,
  onDelete,
}: {
  connection: ConnectionInfo;
  busy: boolean;
  backfillProgress?: { done: number; total: number };
  onBackfill: () => void;
  onToggle: (enabled: boolean) => void;
  onDelete: () => void;
}) {
  return (
    <div className="plugin-row">
      <div className="plugin-info">
        <div className="plugin-name-row">
          <span className="plugin-name">{connection.name}</span>
          <span className="plugin-category mono optional">
            {connection.type === "CONFLUENCE" ? "Confluence" : connection.type === "JIRA" ? "Jira" : "SharePoint"}
          </span>
          {connection.deploymentType !== "UNKNOWN" && (
            <span className="plugin-category mono optional">
              {connection.deploymentType === "CLOUD" ? "Cloud" : "Server/DC"}
            </span>
          )}
        </div>
        <p className="plugin-description">{connection.baseUrl}</p>
        <div className="plugin-health mono">
          {connection.status === "ERROR" && connection.lastError
            ? connection.lastError
            : connection.lastSyncedAt
              ? `Last synced ${new Date(connection.lastSyncedAt).toLocaleString()}`
              : "Not synced yet"}
          {backfillProgress && backfillProgress.total > 0
            ? ` — backfilling ${backfillProgress.done}/${backfillProgress.total}`
            : busy && !backfillProgress
              ? " — verifying…"
              : ""}
        </div>
      </div>

      <div className="plugin-status">
        <span className={`status-pill ${statusClass(connection.status)}`}>
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
          >
            Backfill
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
