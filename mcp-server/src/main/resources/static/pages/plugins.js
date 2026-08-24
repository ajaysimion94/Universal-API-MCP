import { api } from "../api.js";
import { banner, escapeAttr, escapeHtml, icon, message, on, statusClass, toggle } from "../ui.js";

export async function mount(outlet) {
  const state = {
    plugins: [], loading: true, error: "", jobs: new Map(), learning: null, notice: "",
  };
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
        <h1 class="plugins-title">${icon("puzzle", 20)} Settings</h1>
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
                ? `<button class="btn btn-primary" type="button" data-action="setup" data-id="${escapeAttr(plugin.id)}" ${installing ? "disabled" : ""}>${icon("download", 14)} ${installing ? "Setting up…" : "Install & start"}</button>`
                : ""}
              ${installing ? '<div class="install-progress"><div class="install-progress-bar"></div></div>' : ""}
              ${canToggle && plugin.status !== "NOT_INSTALLED"
                ? toggle(plugin.enabled, isSearxng ? "Start automatically" : (plugin.enabled ? "Enabled" : "Disabled"), "toggle", plugin.id,
                  isSearxng ? `Start ${plugin.name} automatically` : `${plugin.enabled ? "Disable" : "Enable"} ${plugin.name}`)
                : ""}
              ${isSearxng && plugin.status !== "NOT_INSTALLED"
                ? `<button class="btn btn-ghost" type="button" data-action="start-stop" data-id="${escapeAttr(plugin.id)}" ${plugin.enabled ? "" : "disabled"}>${icon("power", 14)} ${plugin.running ? "Stop service" : "Start service"}</button>`
                : ""}
            </div>
          </div>`;
        }).join("")}</div>`}
      ${learningPanel()}
    </div>`;
  }

  /**
   * What the ranking has learned, and how to undo it. Lives here rather than on its own route
   * because Settings is already the page for "what is this server doing under the hood".
   */
  function learningPanel() {
    const data = state.learning;
    if (!data) return "";
    const impressions = data.impressions || {};
    const signals = data.feedback || {};
    const entries = data.memory?.topEntries || [];
    const arms = data.arms || [];

    const mode = !data.enabled ? "Off"
      : data.banditEnabled ? "Learning + weight tuning"
      : data.shadowMode ? "Learning (weight tuning in shadow)"
      : "Learning from feedback";

    return `<section class="learning-panel">
      <div class="learning-header">
        <h2>${icon("route", 18)} Ranking feedback</h2>
        <span class="status-pill ${statusClass(data.enabled ? "ACTIVE" : "DISABLED")}">${escapeHtml(mode)}</span>
      </div>
      <p class="plugins-subtitle">Thumbs on a search result teach this server which sources answer which questions. Ratings are stored locally and never leave the machine.</p>
      ${state.notice ? banner(state.notice, "status") : ""}
      <div class="learning-stats">
        ${statTile("Searches recorded", impressions.total ?? 0)}
        ${statTile("With feedback", impressions.withFeedback ?? 0)}
        ${statTile("Ratings", signals.RATING ?? 0)}
        ${statTile("Learned preferences", data.memory?.entries ?? 0)}
      </div>
      ${entries.length ? `<table class="learning-table">
        <thead><tr><th>Query</th><th>Source</th><th class="num">Strength</th><th class="num">Votes</th></tr></thead>
        <tbody>${entries.map((entry) => `<tr>
          <td>${escapeHtml(entry.query)}</td>
          <td>${escapeHtml(entry.sourceName || entry.chunkId.slice(0, 8))}</td>
          <td class="num mono ${entry.decayedStrength < 0 ? "is-negative" : "is-positive"}">${entry.decayedStrength > 0 ? "+" : ""}${entry.decayedStrength}</td>
          <td class="num mono">${entry.observations}</td>
        </tr>`).join("")}</tbody>
      </table>` : `<p class="learning-empty">Nothing learned yet. Rate a search result and it will appear here.</p>`}
      ${data.banditEnabled || data.shadowMode ? `<table class="learning-table">
        <thead><tr><th>Weight blend</th><th class="num">vector</th><th class="num">lexical</th><th class="num">uses</th><th class="num">mean reward</th></tr></thead>
        <tbody>${arms.map((arm) => `<tr class="${arm.enabled ? "" : "is-disabled"}">
          <td>${escapeHtml(arm.armId)}${arm.enabled ? "" : " (retired)"}</td>
          <td class="num mono">${arm.wVector}</td><td class="num mono">${arm.wLexical}</td>
          <td class="num mono">${arm.pulls}</td><td class="num mono">${arm.pulls ? arm.meanReward : "—"}</td>
        </tr>`).join("")}</tbody>
      </table>` : ""}
      <div class="learning-actions">
        <button class="btn btn-ghost btn-danger" type="button" data-action="reset-memory">Clear learned preferences</button>
        <button class="btn btn-ghost" type="button" data-action="rebuild-learning">Rebuild learned preferences</button>
        ${data.droppedWrites ? `<span class="mono learning-dropped">${data.droppedWrites} dropped write(s)</span>` : ""}
      </div>
    </section>`;
  }

  function statTile(label, value) {
    return `<div class="learning-stat"><span class="learning-stat-value mono">${value}</span><span class="learning-stat-label">${escapeHtml(label)}</span></div>`;
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

  /** Best-effort: the panel is supplementary, so a failure here must not blank the plugins list. */
  async function loadLearning() {
    try {
      state.learning = await api.fetchLearning();
    } catch {
      state.learning = null;
    }
    render();
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
      state.notice = "";
      render();
      return;
    }
    if (target.dataset.action === "reset-memory") {
      if (!confirm("Clear learned ranking preferences? Rating history will be kept so you can rebuild them later.")) return;
      try {
        await api.resetLearning("memory");
        state.notice = "Cleared what ranking had learned. Your rating history is kept, so Rebuild can restore it.";
      } catch (error) {
        state.error = message(error, "Reset failed");
      }
      await loadLearning();
      return;
    }
    if (target.dataset.action === "rebuild-learning") {
      try {
        await api.rebuildLearning();
        state.notice = "Rebuilding from your rating history.";
      } catch (error) {
        state.error = message(error, "Rebuild failed");
      }
      await loadLearning();
      return;
    }
    if (!plugin) return;
    try {
      if (target.dataset.action === "setup") {
        const job = await api.setupPlugin(plugin.id);
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
  await loadLearning();
  return () => {
    abort.abort();
    clearInterval(pollTimer);
  };
}
