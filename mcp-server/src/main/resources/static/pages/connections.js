import { api } from "../api.js";
import {
  banner,
  emptyState,
  escapeAttr,
  escapeHtml,
  formatDate,
  icon,
  message,
  on,
  statusClass,
  toggle,
} from "../ui.js";

const TYPES = ["CONFLUENCE", "JIRA", "API_COLLECTION"];

function typeLabel(type) {
  return {
    CONFLUENCE: "Confluence",
    JIRA: "Jira",
    API_COLLECTION: "API collection",
    SHAREPOINT: "SharePoint",
    GITHUB: "GitHub",
  }[type] || type;
}

function statusLabel(status, busy) {
  if (busy && status === "PENDING") return "Verifying…";
  return { PENDING: "Pending", CONNECTED: "Connected", ERROR: "Error", DISABLED: "Disabled" }[status] || status;
}

export async function mount(outlet) {
  const state = {
    connections: [],
    loading: true,
    error: "",
    notice: "",
    formOpen: false,
    formType: "CONFLUENCE",
    specSource: "url",
    authMode: "BASIC",
    specFile: null,
    authNotice: "",
    jobs: new Map(),
    expanded: new Set(),
    tools: new Map(),
    editingAuth: "",
    editAuthMode: "",
  };
  const abort = new AbortController();
  let pollTimer = 0;

  function connectionForm() {
    if (!state.formOpen) return "";
    const isApi = state.formType === "API_COLLECTION";
    return `<form class="connection-form" id="connection-form">
      <div class="form-row">
        <label class="form-field"><span>Type</span><select class="form-input" name="type" id="connection-type">${TYPES.map((type) => `<option value="${type}" ${state.formType === type ? "selected" : ""}>${typeLabel(type)}</option>`).join("")}</select></label>
        <label class="form-field"><span>Name</span><input class="form-input" name="name" placeholder="${isApi ? "e.g. Todo App — becomes the @app slug" : "e.g. Engineering Confluence"}" required></label>
      </div>
      ${isApi ? `<div class="form-row">
        <label class="form-field"><span>Spec source</span><select class="form-input" name="specSource" id="spec-source"><option value="url" ${state.specSource === "url" ? "selected" : ""}>URL</option><option value="file" ${state.specSource === "file" ? "selected" : ""}>Upload file</option></select></label>
        ${state.specSource === "url"
          ? `<label class="form-field"><span>Spec URL</span><div class="spec-url-row"><input class="form-input" name="specUrl" placeholder="https://api.example.com/openapi.json" required><button class="btn btn-ghost" type="button" data-action="detect-url-auth">Detect auth</button></div></label>`
          : `<label class="form-field"><span>Spec file</span><input type="file" id="spec-file" class="file-input-hidden" accept=".json,.yaml,.yml,application/json"><button class="btn btn-ghost spec-file-btn" type="button" data-action="choose-spec">${icon("upload", 14)} ${escapeHtml(state.specFile?.name || "Choose Postman / OpenAPI file")}</button></label>`}
      </div>
      ${state.authNotice ? `<p class="connection-auth-notice">${escapeHtml(state.authNotice)}</p>` : ""}
      <fieldset class="url-policy">
        <legend>Request URLs</legend>
        <div class="url-policy-options">
          <label class="url-policy-option"><input type="radio" name="apiUrlMode" value="CONNECTION_BASE" checked><span><strong>Use one base URL</strong><small>Replace every imported host with the connection base URL.</small></span></label>
          <label class="url-policy-option"><input type="radio" name="apiUrlMode" value="SOURCE_URLS"><span><strong>Keep source URLs</strong><small>Call each Postman or OpenAPI URL on the host declared in the source.</small></span></label>
        </div>
      </fieldset>
      <label class="form-field"><span>Base URL <small>(override, or fallback for relative source URLs)</small></span><input class="form-input" name="baseUrl" placeholder="https://api.example.com"></label>
      <p class="url-policy-warning">When source URLs are kept, stored connection credentials are sent to every preserved host.</p>
      <div class="form-row">
        <label class="form-field"><span>Authentication</span><select class="form-input" name="authMode" id="auth-mode">
          ${["NONE", "BASIC", "BEARER", "API_KEY_HEADER"].map((mode) => `<option value="${mode}" ${state.authMode === mode ? "selected" : ""}>${mode.replaceAll("_", " ")}</option>`).join("")}
        </select></label>
        ${state.authMode === "BASIC" ? '<label class="form-field"><span>Username</span><input class="form-input" name="username" required></label>' : ""}
        ${state.authMode === "API_KEY_HEADER" ? '<label class="form-field"><span>Header name</span><input class="form-input" name="apiKeyHeader" value="X-API-Key" required></label>' : ""}
      </div>
      ${state.authMode !== "NONE" ? `<label class="form-field"><span>${state.authMode === "BASIC" ? "Password" : state.authMode === "BEARER" ? "Token" : "API key"}</span><input class="form-input" type="password" name="password" required></label>` : ""}`
      : `<label class="form-field"><span>Base URL</span><input class="form-input" name="baseUrl" placeholder="https://your-team.atlassian.net" required></label>
      <div class="form-row">
        <label class="form-field"><span>Authentication</span><select class="form-input" name="authMode" id="auth-mode">
          <option value="BASIC" ${state.authMode === "BASIC" ? "selected" : ""}>Cloud token / password</option>
          <option value="BEARER" ${state.authMode === "BEARER" ? "selected" : ""}>Data Center PAT</option>
        </select></label>
        ${state.authMode === "BASIC" ? '<label class="form-field"><span>Username / Cloud email</span><input class="form-input" name="username" required></label>' : ""}
      </div>
      <label class="form-field"><span>${state.authMode === "BEARER" ? "Personal access token" : "Password / Cloud API token"}</span><input class="form-input" type="password" name="password" required></label>`}
      <div class="form-actions"><button class="btn btn-ghost" type="button" data-action="cancel-form">Cancel</button><button class="btn btn-primary" type="submit">${isApi ? "Import" : "Connect"}</button></div>
    </form>`;
  }

  function authForm(connection) {
    if (state.editingAuth !== connection.id) return "";
    const isApi = connection.type === "API_COLLECTION";
    const mode = state.editAuthMode || connection.authMode;
    if (!isApi) {
      return `<form class="connection-form connection-auth-form" data-connection-id="${escapeAttr(connection.id)}">
        <div class="form-row">
          <label class="form-field"><span>Name</span><input class="form-input" name="name" value="${escapeAttr(connection.name)}" required></label>
          <label class="form-field"><span>Base URL</span><input class="form-input" name="baseUrl" value="${escapeAttr(connection.baseUrl || "")}" required></label>
        </div>
        <div class="form-row">
          <label class="form-field"><span>Authentication</span><select class="form-input" name="authMode" id="edit-auth-mode">
            <option value="BASIC" ${mode === "BASIC" ? "selected" : ""}>Cloud token / password</option>
            <option value="BEARER" ${mode === "BEARER" ? "selected" : ""}>Data Center PAT</option>
          </select></label>
          ${mode === "BASIC" ? `<label class="form-field"><span>Username / Cloud email</span><input class="form-input" name="username" value="${escapeAttr(connection.authUsername || "")}" required></label>` : ""}
        </div>
        <label class="form-field"><span>New ${mode === "BEARER" ? "personal access token" : "password / API token"} <small>(${mode === connection.authMode ? "blank keeps current" : "required after mode change"})</small></span><input class="form-input" name="password" type="password" ${mode === connection.authMode ? "" : "required"}></label>
        <div class="form-actions"><button class="btn btn-ghost" type="button" data-action="cancel-auth">Cancel</button><button class="btn btn-primary" type="submit">Save settings</button></div>
      </form>`;
    }
    return `<form class="connection-form connection-auth-form" data-connection-id="${escapeAttr(connection.id)}">
      <div class="form-row">
        <label class="form-field"><span>Request URLs</span><select class="form-input" name="apiUrlMode">
          <option value="CONNECTION_BASE" ${connection.apiUrlMode !== "SOURCE_URLS" ? "selected" : ""}>Use one base URL</option>
          <option value="SOURCE_URLS" ${connection.apiUrlMode === "SOURCE_URLS" ? "selected" : ""}>Keep source URLs</option>
        </select></label>
        <label class="form-field"><span>Base URL <small>(override or relative-URL fallback)</small></span><input class="form-input" name="baseUrl" value="${escapeAttr(connection.baseUrl || "")}"></label>
      </div>
      <div class="form-row">
        <label class="form-field"><span>Authentication</span><select class="form-input" name="authMode">
          ${["NONE", "BASIC", "BEARER", "API_KEY_HEADER"].map((mode) => `<option value="${mode}" ${connection.authMode === mode ? "selected" : ""}>${mode.replaceAll("_", " ")}</option>`).join("")}
        </select></label>
        <label class="form-field"><span>Username / header name</span><input class="form-input" name="username" value="${escapeAttr(connection.authUsername || "")}"></label>
      </div>
      <label class="form-field"><span>New secret <small>(blank keeps current)</small></span><input class="form-input" name="password" type="password"></label>
      <div class="form-actions"><button class="btn btn-ghost" type="button" data-action="cancel-auth">Cancel</button><button class="btn btn-primary" type="submit">Save settings</button></div>
    </form>`;
  }

  function toolList(connection) {
    if (connection.type !== "API_COLLECTION") return "";
    const expanded = state.expanded.has(connection.id);
    const tools = state.tools.get(connection.id);
    return `<div class="tool-list">
      <button class="tool-list-header" type="button" data-action="toggle-tools" data-id="${escapeAttr(connection.id)}" aria-expanded="${expanded}">
        ${icon("chevron", 14, `file-group-chevron ${expanded ? "chev-open" : ""}`)}${icon("hash", 14)}
        <span>${tools ? `${tools.length} tools — ${tools.filter((tool) => tool.enabled).length} enabled, ${tools.filter((tool) => tool.pending).length} pending review` : "Tools"}</span>
      </button>
      ${expanded ? tools
        ? `<div class="tool-category">${tools.map((tool) => `<div class="tool-row">
          <span class="method-badge mono ${tool.method === "GET" ? "" : "method-write"}">${escapeHtml(tool.method)}</span>
          <div class="tool-row-info"><span class="tool-row-name mono">${escapeHtml(tool.name)}</span><span class="tool-row-desc">${escapeHtml(tool.displayName)}</span></div>
          ${tool.pending ? '<span class="tool-pending-badge mono">pending</span>' : ""}
          ${tool.method === "GET" && tool.enabled ? `<span class="tool-knowledge-toggle" title="Ingest responses into search">${icon("book", 13)}${toggle(tool.knowledgeSource, "", "toggle-knowledge", tool.id)}</span>` : ""}
          ${toggle(tool.enabled, tool.enabled ? "Enabled" : "Off", "toggle-tool", tool.id)}
        </div>`).join("")}</div>`
        : '<p class="tool-list-empty">Loading tools…</p>' : ""}
    </div>`;
  }

  function renderRow(connection) {
    const busy = state.jobs.has(connection.id);
    const job = state.jobs.get(connection.id);
    const isApi = connection.type === "API_COLLECTION";
    return `<div class="connection-block">
      <div class="plugin-row">
        <div class="plugin-info">
          <div class="plugin-name-row"><span class="plugin-name">${escapeHtml(connection.name)}</span><span class="plugin-category mono optional">${typeLabel(connection.type)}</span>${connection.specFormat ? `<span class="plugin-category mono optional">${escapeHtml(connection.specFormat)}</span>` : ""}${isApi ? `<span class="plugin-category mono optional">${connection.apiUrlMode === "SOURCE_URLS" ? "source URLs" : "base override"}</span>` : ""}</div>
          <p class="plugin-description">${escapeHtml(connection.baseUrl || connection.specSourceUrl || "No base URL")}</p>
          <div class="plugin-health mono">${escapeHtml(connection.status === "ERROR" && connection.lastError ? connection.lastError : connection.lastSyncedAt ? `Last synced ${formatDate(connection.lastSyncedAt)}` : isApi ? "Knowledge index not refreshed yet" : "Content sync not started yet")}${job?.itemsTotal ? ` — ${job.itemsProcessed}/${job.itemsTotal}` : busy ? " — working…" : ""}</div>
        </div>
        <div class="plugin-status"><span class="status-pill ${statusClass(connection.status)}">${connection.status === "CONNECTED" ? icon("check", 12) : connection.status === "ERROR" ? icon("alert", 12) : ""}${statusLabel(connection.status, busy)}</span></div>
        <div class="plugin-actions">
          ${busy ? '<div class="install-progress"><div class="install-progress-bar"></div></div>' : `<button class="btn btn-ghost" type="button" data-action="backfill" data-id="${escapeAttr(connection.id)}" ${connection.status === "CONNECTED" ? "" : "disabled"}>${isApi ? "Refresh knowledge" : "Backfill"}</button>`}
          <button class="btn btn-ghost ${state.editingAuth === connection.id ? "is-active" : ""}" type="button" data-action="edit-auth" data-id="${escapeAttr(connection.id)}">Edit settings</button>
          ${toggle(connection.status !== "DISABLED", connection.status === "DISABLED" ? "Disabled" : "Enabled", "toggle-connection", connection.id)}
          <button class="btn btn-ghost" type="button" data-action="delete-connection" data-id="${escapeAttr(connection.id)}" aria-label="Delete ${escapeAttr(connection.name)}">${icon("trash", 14)}</button>
        </div>
      </div>
      ${authForm(connection)}
      ${toolList(connection)}
    </div>`;
  }

  function render() {
    outlet.innerHTML = `<div class="connections-page plugins-page">
      <div class="plugins-header connections-header">
        <div><h1 class="plugins-title">${icon("globe", 20)} Connections</h1><p class="plugins-subtitle">Connect knowledge systems and import API collections as callable tools.</p></div>
        <button class="btn btn-primary" type="button" data-action="open-form">${icon("plus", 14)} Add connection</button>
      </div>
      ${state.error ? banner(state.error) : ""}${state.notice ? banner(state.notice, "status") : ""}
      ${connectionForm()}
      ${state.loading ? '<div class="plugins-skeleton"><div class="plugin-row-skeleton"></div><div class="plugin-row-skeleton"></div></div>' :
        state.connections.length ? `<div class="plugins-list">${state.connections.map(renderRow).join("")}</div>` :
        emptyState("No connections yet", "Connect Confluence or Jira, or import a Postman/OpenAPI collection.", `<button class="btn btn-primary" type="button" data-action="open-form">${icon("plus", 14)} Add connection</button>`)}
    </div>`;
  }

  // Native select popups are owned by the browser. Replacing their element while
  // the popup is open dismisses it immediately (especially visibly in Edge).
  // Background refreshes can wait until this short-lived editor is closed; form
  // interactions still call render() directly when their dependent fields change.
  function renderBackground() {
    if (!state.formOpen && !state.editingAuth) render();
  }

  async function load() {
    try {
      state.connections = await api.listConnections();
      state.error = "";
    } catch (error) {
      state.error = message(error, "Failed to load connections");
    } finally {
      state.loading = false;
      renderBackground();
    }
  }

  async function watchJob(connectionId, jobId) {
    state.jobs.set(connectionId, { jobId, status: "running" });
    renderBackground();
    if (!pollTimer) {
      pollTimer = window.setInterval(async () => {
        for (const [id, current] of [...state.jobs]) {
          try {
            const job = await api.getConnectionJob(current.jobId);
            state.jobs.set(id, job);
            if (["completed", "failed"].includes(job.status)) {
              state.jobs.delete(id);
              if (job.error) state.error = job.error;
              await load();
            }
          } catch {
            state.jobs.delete(id);
          }
        }
        if (!state.jobs.size) {
          clearInterval(pollTimer);
          pollTimer = 0;
        }
        renderBackground();
      }, 1200);
    }
  }

  async function loadTools(connectionId) {
    try {
      state.tools.set(connectionId, await api.listTools(undefined, connectionId));
    } catch (error) {
      state.error = message(error, "Failed to load tools");
    }
    renderBackground();
  }

  on(outlet, "click", "[data-action]", async (_event, target) => {
    const { action, id } = target.dataset;
    if (action === "open-form") {
      state.formOpen = true;
      state.error = "";
      render();
    } else if (action === "cancel-form") {
      state.formOpen = false;
      state.specFile = null;
      render();
    } else if (action === "choose-spec") {
      outlet.querySelector("#spec-file")?.click();
    } else if (action === "detect-url-auth") {
      const specUrl = new FormData(target.closest("form")).get("specUrl");
      try {
        const detected = await api.detectImportAuth({ specUrl });
        state.authMode = detected.authMode;
        state.authNotice = detected.authMode === "NONE" ? "No authentication shape detected." : `${detected.authMode.replaceAll("_", " ")} authentication detected.`;
      } catch {
        state.authNotice = "Authentication detection was inconclusive; choose it manually.";
      }
      render();
    } else if (action === "dismiss-banner") {
      state.error = "";
      state.notice = "";
      render();
    } else if (action === "toggle-tools") {
      if (state.expanded.has(id)) state.expanded.delete(id);
      else {
        state.expanded.add(id);
        if (!state.tools.has(id)) loadTools(id);
      }
      render();
    } else if (action === "edit-auth") {
      state.editingAuth = state.editingAuth === id ? "" : id;
      state.editAuthMode = state.editingAuth
        ? state.connections.find((connection) => connection.id === id)?.authMode || "BASIC"
        : "";
      render();
    } else if (action === "cancel-auth") {
      state.editingAuth = "";
      state.editAuthMode = "";
      render();
    } else {
      const connection = state.connections.find((item) => item.id === id);
      const tools = [...state.tools.values()].flat();
      const tool = tools.find((item) => item.id === id);
      try {
        if (action === "backfill" && connection) {
          const job = await api.triggerBackfill(id);
          watchJob(id, job.jobId);
        } else if (action === "toggle-connection" && connection) {
          await (connection.status === "DISABLED" ? api.enableConnection(id) : api.disableConnection(id));
          await load();
        } else if (action === "delete-connection" && connection) {
          if (!confirm(`Delete "${connection.name}" and its imported tools?`)) return;
          await api.deleteConnection(id);
          await load();
        } else if (action === "toggle-tool" && tool) {
          await (tool.enabled ? api.disableTool(id) : api.enableTool(id));
          await loadTools(tool.connectionId);
        } else if (action === "toggle-knowledge" && tool) {
          await api.setToolKnowledgeSource(id, !tool.knowledgeSource);
          await loadTools(tool.connectionId);
        }
      } catch (error) {
        state.error = message(error, "Connection action failed");
        render();
      }
    }
  });

  outlet.addEventListener("change", async (event) => {
    if (event.target.id === "connection-type") {
      state.formType = event.target.value;
      state.authMode = event.target.value === "API_COLLECTION" ? "NONE" : "BASIC";
      render();
    } else if (event.target.id === "spec-source") {
      state.specSource = event.target.value;
      render();
    } else if (event.target.id === "auth-mode") {
      state.authMode = event.target.value;
      render();
    } else if (event.target.id === "edit-auth-mode") {
      state.editAuthMode = event.target.value;
      render();
    } else if (event.target.id === "spec-file") {
      state.specFile = event.target.files?.[0] || null;
      if (state.specFile) {
        try {
          const detected = await api.detectImportAuth({ file: state.specFile });
          state.authMode = detected.authMode;
          state.authNotice = detected.authMode === "NONE" ? "No authentication shape detected." : `${detected.authMode.replaceAll("_", " ")} authentication detected.`;
        } catch {
          state.authNotice = "";
        }
      }
      render();
    }
  }, { signal: abort.signal });

  outlet.addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = event.target;
    const data = Object.fromEntries(new FormData(form));
    try {
      if (form.id === "connection-form") {
        let result;
        if (state.formType === "API_COLLECTION" && state.specSource === "file") {
          if (!state.specFile) throw new Error("Choose a spec file.");
          result = await api.importSpecFile({
            file: state.specFile,
            name: data.name,
            baseUrl: data.baseUrl,
            authMode: state.authMode,
            username: data.username,
            password: data.password,
            apiKeyHeader: data.apiKeyHeader,
            apiUrlMode: data.apiUrlMode,
          });
        } else {
          result = await api.createConnection({
            type: state.formType,
            name: data.name,
            baseUrl: data.baseUrl,
            username: data.username || "",
            password: data.password || "",
            specUrl: data.specUrl,
            authMode: state.authMode,
            apiKeyHeader: data.apiKeyHeader,
            apiUrlMode: data.apiUrlMode,
          });
        }
        state.formOpen = false;
        state.specFile = null;
        await load();
        watchJob(result.id, result.jobId);
      } else if (form.classList.contains("connection-auth-form")) {
        const result = await api.updateConnection(form.dataset.connectionId, {
          name: data.name || undefined,
          authMode: data.authMode,
          username: data.username || undefined,
          password: data.password || undefined,
          baseUrl: data.baseUrl || undefined,
          apiUrlMode: data.apiUrlMode,
        });
        state.editingAuth = "";
        state.editAuthMode = "";
        state.notice = "Connection settings saved; the source is being verified.";
        await load();
        watchJob(form.dataset.connectionId, result.jobId);
      }
    } catch (error) {
      state.error = message(error, "Could not save connection");
      render();
    }
  }, { signal: abort.signal });

  render();
  await load();
  return () => {
    abort.abort();
    clearInterval(pollTimer);
  };
}
