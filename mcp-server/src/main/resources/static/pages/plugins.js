import { api } from "../api.js";
import { banner, escapeAttr, escapeHtml, icon, message, on, statusClass, toggle } from "../ui.js";

export async function mount(outlet) {
  const state = { plugins: [], loading: true, error: "", jobs: new Map() };
  const abort = new AbortController();
  let pollTimer = 0;

  function statusLabel(plugin) {
    if (state.jobs.has(plugin.id)) return "Installing…";
    return {
      NOT_INSTALLED: "Not installed",
      INSTALLING: "Installing…",
      INSTALLED: "Installed",
      ACTIVE: "Active",
      ERROR: "Error",
      DISABLED: "Disabled",
    }[plugin.status] || plugin.status;
  }

  function render() {
    outlet.innerHTML = `<div class="plugins-page">
      <div class="plugins-header">
        <h1 class="plugins-title">${icon("puzzle", 20)} Plugins</h1>
        <p class="plugins-subtitle">Manage the services and models that power search and web augmentation.</p>
      </div>
      ${state.error ? banner(state.error) : ""}
      ${state.loading ? `<div class="plugins-skeleton">${Array.from({ length: 3 }, () => '<div class="plugin-row-skeleton"><div class="skel-line skel-title"></div><div class="skel-line skel-desc"></div></div>').join("")}</div>` : `
        <div class="plugins-list">${state.plugins.map((plugin) => {
          const installing = state.jobs.has(plugin.id);
          const isSearxng = plugin.id === "searxng";
          const canToggle = isSearxng || plugin.id === "nomic-embedding";
          return `<div class="plugin-row">
            <div class="plugin-info">
              <div class="plugin-name-row">
                ${isSearxng ? icon("globe", 16, "plugin-icon") : ""}
                <span class="plugin-name">${escapeHtml(plugin.name)}</span>
                <span class="plugin-category mono ${plugin.category === "REQUIRED" ? "required" : "optional"}">${plugin.category === "REQUIRED" ? "Required" : "Optional"}</span>
                ${plugin.builtin ? '<span class="plugin-category mono optional" title="Ships inside the JAR">Built-in</span>' : ""}
              </div>
              <p class="plugin-description">${escapeHtml(plugin.description)}</p>
              <div class="plugin-health mono">${escapeHtml(plugin.health)}</div>
            </div>
            <div class="plugin-status">
              <span class="status-pill ${statusClass(plugin.status)}">${plugin.status === "ACTIVE" ? icon("check", 12) : plugin.status === "ERROR" ? icon("alert", 12) : ""}${escapeHtml(statusLabel(plugin))}</span>
            </div>
            <div class="plugin-actions">
              ${plugin.status === "NOT_INSTALLED" && !plugin.builtin
                ? `<button class="btn btn-primary" type="button" data-action="install" data-id="${escapeAttr(plugin.id)}" ${installing ? "disabled" : ""}>${icon("download", 14)} ${installing ? "Installing…" : "Install"}</button>`
                : ""}
              ${installing ? '<div class="install-progress"><div class="install-progress-bar"></div></div>' : ""}
              ${canToggle && plugin.status !== "NOT_INSTALLED"
                ? toggle(plugin.enabled, plugin.enabled ? "Enabled" : "Disabled", "toggle", plugin.id)
                : ""}
              ${isSearxng && plugin.status !== "NOT_INSTALLED"
                ? `<button class="btn btn-ghost" type="button" data-action="start-stop" data-id="${escapeAttr(plugin.id)}" ${plugin.enabled ? "" : "disabled"}>${icon("power", 14)} ${plugin.running ? "Stop" : "Start"}</button>`
                : ""}
            </div>
          </div>`;
        }).join("")}</div>`}
    </div>`;
  }

  async function load() {
    try {
      state.plugins = await api.listPlugins();
      state.error = "";
    } catch (error) {
      state.error = message(error, "Failed to load plugins");
    } finally {
      state.loading = false;
      render();
    }
  }

  async function pollJobs() {
    for (const [pluginId, jobId] of [...state.jobs]) {
      try {
        const job = await api.getPluginJob(jobId);
        if (["completed", "failed"].includes(job.status)) {
          state.jobs.delete(pluginId);
          if (job.error) state.error = job.error;
          await load();
        }
      } catch {
        state.jobs.delete(pluginId);
      }
    }
    if (!state.jobs.size) {
      clearInterval(pollTimer);
      pollTimer = 0;
    }
  }

  on(outlet, "click", "[data-action]", async (_event, target) => {
    const plugin = state.plugins.find((item) => item.id === target.dataset.id);
    if (target.dataset.action === "dismiss-banner") {
      state.error = "";
      render();
      return;
    }
    if (!plugin) return;
    try {
      if (target.dataset.action === "install") {
        const job = await api.installPlugin(plugin.id);
        state.jobs.set(plugin.id, job.jobId);
        if (!pollTimer) pollTimer = window.setInterval(pollJobs, 1500);
      } else if (target.dataset.action === "toggle") {
        await (plugin.enabled ? api.disablePlugin(plugin.id) : api.enablePlugin(plugin.id));
        await load();
      } else if (target.dataset.action === "start-stop") {
        await (plugin.running ? api.stopPlugin(plugin.id) : api.startPlugin(plugin.id));
        await load();
      }
      render();
    } catch (error) {
      state.error = message(error, "Plugin action failed");
      render();
    }
  });

  render();
  await load();
  return () => {
    abort.abort();
    clearInterval(pollTimer);
  };
}
