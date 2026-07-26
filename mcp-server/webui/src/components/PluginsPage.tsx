import { useCallback, useEffect, useState } from "react";
import {
  PluginInfo,
  listPlugins,
  installPlugin,
  enablePlugin,
  disablePlugin,
  startPlugin,
  stopPlugin,
  getPluginJob,
} from "../api";
import {
  PuzzleIcon,
  PowerIcon,
  DownloadIcon,
  CheckCircleIcon,
  AlertIcon,
  GlobeIcon,
} from "../icons";
import { Toggle } from "./Toggle";

export function PluginsPage() {
  const [plugins, setPlugins] = useState<PluginInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [installingJobs, setInstallingJobs] = useState<Record<string, string>>({});

  const load = useCallback(async () => {
    try {
      const data = await listPlugins();
      setPlugins(data);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load plugins");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    const intervals: Record<string, ReturnType<typeof setInterval>> = {};
    for (const [pluginId, jobId] of Object.entries(installingJobs)) {
      intervals[pluginId] = setInterval(async () => {
        try {
          const job = await getPluginJob(jobId);
          if (job.status === "completed" || job.status === "failed") {
            clearInterval(intervals[pluginId]);
            setInstallingJobs((prev) => {
              const next = { ...prev };
              delete next[pluginId];
              return next;
            });
            await load();
          }
        } catch {
          clearInterval(intervals[pluginId]);
        }
      }, 1500);
    }
    return () => {
      for (const interval of Object.values(intervals)) clearInterval(interval);
    };
  }, [installingJobs, load]);

  const handleInstall = async (id: string) => {
    try {
      const { jobId } = await installPlugin(id);
      setInstallingJobs((prev) => ({ ...prev, [id]: jobId }));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Install failed");
    }
  };

  const handleToggle = async (plugin: PluginInfo, enabled: boolean) => {
    try {
      if (enabled) {
        await enablePlugin(plugin.id);
      } else {
        await disablePlugin(plugin.id);
      }
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Toggle failed");
    }
  };

  const handleStartStop = async (plugin: PluginInfo) => {
    try {
      if (plugin.running) {
        await stopPlugin(plugin.id);
      } else {
        await startPlugin(plugin.id);
      }
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Start/stop failed");
    }
  };

  return (
    <div className="plugins-page">
      <div className="plugins-header">
        <h1 className="plugins-title">
          <PuzzleIcon size={20} /> Plugins
        </h1>
        <p className="plugins-subtitle">
          Manage the services and models that power search and web augmentation.
        </p>
      </div>

      {error && (
        <div className="error-banner" role="alert">
          {error}
          <button className="error-dismiss" onClick={() => setError(null)} aria-label="Dismiss">
            ×
          </button>
        </div>
      )}

      {loading ? (
        <div className="plugins-skeleton">
          {[0, 1, 2].map((i) => (
            <div key={i} className="plugin-row-skeleton">
              <div className="skel-line skel-title" />
              <div className="skel-line skel-desc" />
            </div>
          ))}
        </div>
      ) : (
        <div className="plugins-list">
          {plugins.map((plugin) => (
            <PluginRow
              key={plugin.id}
              plugin={plugin}
              installing={!!installingJobs[plugin.id]}
              onInstall={() => handleInstall(plugin.id)}
              onToggle={(enabled) => handleToggle(plugin, enabled)}
              onStartStop={() => handleStartStop(plugin)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function PluginRow({
  plugin,
  installing,
  onInstall,
  onToggle,
  onStartStop,
}: {
  plugin: PluginInfo;
  installing: boolean;
  onInstall: () => void;
  onToggle: (enabled: boolean) => void;
  onStartStop: () => void;
}) {
  const isSearxng = plugin.id === "searxng";
  const canToggle = isSearxng || plugin.id === "nomic-embedding" || plugin.id === "copilot-chat";
  const statusIcon = getStatusIcon(plugin.status);
  const statusLabel = getStatusLabel(plugin.status, installing);

  return (
    <div className="plugin-row">
      <div className="plugin-info">
        <div className="plugin-name-row">
          {isSearxng ? <GlobeIcon size={16} className="plugin-icon" /> : null}
          <span className="plugin-name">{plugin.name}</span>
          <span className={`plugin-category mono ${plugin.category === "REQUIRED" ? "required" : "optional"}`}>
            {plugin.category === "REQUIRED" ? "Required" : "Optional"}
          </span>
          {plugin.builtin && (
            <span className="plugin-category mono optional" title="Ships inside the jar — no download or install needed">
              Built-in
            </span>
          )}
        </div>
        <p className="plugin-description">{plugin.description}</p>
        <div className="plugin-health mono">{plugin.health}</div>
      </div>

      <div className="plugin-status">
        <span className={`status-pill ${statusClass(plugin.status)}`}>
          {statusIcon}
          {statusLabel}
        </span>
      </div>

      <div className="plugin-actions">
        {plugin.status === "NOT_INSTALLED" && !plugin.builtin && (
          <button className="btn btn-primary" onClick={onInstall} disabled={installing}>
            <DownloadIcon size={14} />
            {installing ? "Installing…" : "Install"}
          </button>
        )}

        {installing && (
          <div className="install-progress">
            <div className="install-progress-bar" />
          </div>
        )}

        {canToggle && plugin.status !== "NOT_INSTALLED" && (
          <>
            <Toggle
              checked={plugin.enabled}
              onChange={onToggle}
              label={plugin.enabled ? "Enabled" : "Disabled"}
            />
            {isSearxng && (
              <button
                className="btn btn-ghost"
                onClick={onStartStop}
                disabled={!plugin.enabled}
              >
                <PowerIcon size={14} />
                {plugin.running ? "Stop" : "Start"}
              </button>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function getStatusIcon(status: string) {
  switch (status) {
    case "ACTIVE":
      return <CheckCircleIcon size={12} />;
    case "ERROR":
      return <AlertIcon size={12} />;
    default:
      return null;
  }
}

function getStatusLabel(status: string, installing: boolean) {
  if (installing) return "Installing…";
  switch (status) {
    case "NOT_INSTALLED":
      return "Not installed";
    case "INSTALLING":
      return "Installing…";
    case "INSTALLED":
      return "Installed";
    case "ACTIVE":
      return "Active";
    case "ERROR":
      return "Error";
    case "DISABLED":
      return "Disabled";
    default:
      return status;
  }
}

function statusClass(status: string) {
  switch (status) {
    case "ACTIVE":
      return "status-active";
    case "ERROR":
      return "status-error";
    case "DISABLED":
      return "status-disabled";
    case "INSTALLED":
      return "status-installed";
    default:
      return "status-default";
  }
}
