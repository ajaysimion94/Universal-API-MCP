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

function statusLabel(connection, busy) {
  if (busy && connection.status === "PENDING") return "Verifying…";
  if (connection.type === "API_COLLECTION" && connection.status === "CONNECTED") return "Imported";
  return { PENDING: "Pending", CONNECTED: "Connected", ERROR: "Error", DISABLED: "Disabled" }[connection.status] || connection.status;
}

function healthLabel(connection) {
  if (connection.lastTestFailureCategory) {
    return `Last check failed: ${connection.lastTestFailureCategory.replaceAll("_", " ").toLowerCase()}`;
  }
  if (connection.lastTestSucceededAt) return `Last checked ${formatDate(connection.lastTestSucceededAt)}`;
  if (connection.status === "ERROR" && connection.lastError) return connection.lastError;
  if (connection.lastSyncedAt) return `Last synced ${formatDate(connection.lastSyncedAt)}`;
  if (connection.type === "API_COLLECTION") return "Definition imported · test the remote target in APIs";
  return "Content sync not started yet";
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
    formName: "",
    specUrl: "",
    specInspected: false,
    detectedBaseUrl: "",
    detectedSpecFormat: "",
    apiUrlMode: "CONNECTION_BASE",
    baseUrlOverride: "",
    authNotice: "",
    jobs: new Map(),
    expanded: new Set(),
    tools: new Map(),
    editingAuth: "",
    editAuthMode: "",
    submitting: false,
  };
  const abort = new AbortController();
  let pollTimer = 0;

  function resetSpecInspection() {
    state.specInspected = false;
    state.detectedBaseUrl = "";
    state.detectedSpecFormat = "";
    state.authNotice = "";
  }

  function applySpecInspection(detected) {
    state.authMode = detected.authMode || "NONE";
    state.detectedBaseUrl = detected.baseUrl || "";
    state.detectedSpecFormat = detected.specFormat || "";
    state.specInspected = true;
    const authText = state.authMode === "NONE"
      ? "No authentication shape detected."
      : `${state.authMode.replaceAll("_", " ")} authentication detected.`;
    state.authNotice = `${state.detectedSpecFormat ? `${state.detectedSpecFormat} inspected. ` : ""}${authText}`;
  }

  function detectedCollectionUrl(value = state.detectedBaseUrl, inspected = state.specInspected) {
    const display = value || (inspected
      ? "No single API URL declared in this document"
      : "Inspect the collection to view its API URL");
    return `<label class="form-field"><span>Spec API URL <small>(detected)</small></span><input class="form-input connection-url-readonly mono" value="${escapeAttr(display)}" readonly aria-readonly="true"></label>`;
  }

  function baseUrlOverrideField(value = state.baseUrlOverride, disabled = state.apiUrlMode === "SOURCE_URLS", effectiveUrl = "") {
    const help = disabled
      ? "Each request keeps the host declared in the source file."
      : "Leave blank to use the API URL declared by the document.";
    const effective = effectiveUrl
      ? `<small>Current effective API URL: <span class="mono">${escapeHtml(effectiveUrl)}</span></small>`
      : "";
    return `<label class="form-field"><span>Base URL <small>(optional override)</small></span><input class="form-input mono" name="baseUrl" value="${escapeAttr(value || "")}" placeholder="https://api.example.com/v1" ${disabled ? "disabled aria-disabled=\"true\"" : ""} aria-describedby="base-url-override-help"><small id="base-url-override-help">${help}</small>${effective}</label>`;
  }

  function connectionForm() {
    if (!state.formOpen) return "";
    const isApi = state.formType === "API_COLLECTION";
    return `<form class="connection-form" id="connection-form" aria-busy="${state.submitting}">
      <div class="form-row">
        <label class="form-field"><span>Type</span><select class="form-input" name="type" id="connection-type">${TYPES.map((type) => `<option value="${type}" ${state.formType === type ? "selected" : ""}>${typeLabel(type)}</option>`).join("")}</select></label>
        <label class="form-field"><span>Name</span><input class="form-input" name="name" value="${escapeAttr(state.formName)}" placeholder="${isApi ? "e.g. Todo App — becomes the @app slug" : "e.g. Engineering Confluence"}" required></label>
      </div>
      ${isApi ? `<div class="form-row">
        <label class="form-field"><span>Spec source</span><select class="form-input" name="specSource" id="spec-source"><option value="url" ${state.specSource === "url" ? "selected" : ""}>URL</option><option value="file" ${state.specSource === "file" ? "selected" : ""}>Upload file</option></select></label>
        ${state.specSource === "url"
          ? `<label class="form-field"><span>Spec URL</span><div class="spec-url-row"><input class="form-input" name="specUrl" value="${escapeAttr(state.specUrl)}" placeholder="https://api.example.com/openapi.json" required><button class="btn btn-ghost" type="button" data-action="detect-url-auth">Inspect spec</button></div></label>`
          : `<label class="form-field"><span>Spec file</span><input type="file" id="spec-file" class="file-input-hidden" accept=".json,.yaml,.yml,application/json"><button class="btn btn-ghost spec-file-btn" type="button" data-action="choose-spec">${icon("upload", 14)} ${escapeHtml(state.specFile?.name || "Choose Postman / OpenAPI file")}</button></label>`}
      </div>
      ${state.authNotice ? `<p class="connection-auth-notice">${escapeHtml(state.authNotice)}</p>` : ""}
      <fieldset class="url-policy">
        <legend>Request URLs</legend>
        <div class="url-policy-options">
          <label class="url-policy-option"><input type="radio" name="apiUrlMode" value="CONNECTION_BASE" ${state.apiUrlMode === "CONNECTION_BASE" ? "checked" : ""}><span><strong>Use one base URL</strong><small>Send every imported request to the Base URL below, or to the API server URL from the document.</small></span></label>
          <label class="url-policy-option"><input type="radio" name="apiUrlMode" value="SOURCE_URLS" ${state.apiUrlMode === "SOURCE_URLS" ? "checked" : ""}><span><strong>Keep each request's URL</strong><small>Send each request to the exact host declared in the Postman/OpenAPI source.</small></span></label>
        </div>
      </fieldset>
      ${detectedCollectionUrl()}
      ${baseUrlOverrideField()}
      <p class="url-policy-warning">The target API must already be running. <span class="mono">localhost</span> means the machine running MCP Server.</p>
      <p class="url-policy-warning">If you keep each request's URL, this connection's credentials may be sent to every host in the source file.</p>
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
      <div class="form-actions"><button class="btn btn-ghost" type="button" data-action="cancel-form" ${state.submitting ? "disabled" : ""}>Cancel</button><button class="btn btn-primary" type="submit" ${state.submitting ? "disabled" : ""}>${state.submitting ? (isApi ? "Importing…" : "Connecting…") : isApi ? "Import API collection" : "Connect source"}</button></div>
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
        <div class="form-actions"><button class="btn btn-ghost" type="button" data-action="cancel-auth" ${state.submitting ? "disabled" : ""}>Cancel</button><button class="btn btn-primary" type="submit" ${state.submitting ? "disabled" : ""}>${state.submitting ? "Saving settings…" : "Save settings"}</button></div>
      </form>`;
    }
    return `<form class="connection-form connection-auth-form" data-connection-id="${escapeAttr(connection.id)}">
      <div class="form-row">
        <label class="form-field"><span>Request URLs</span><select class="form-input" name="apiUrlMode">
          <option value="CONNECTION_BASE" ${connection.apiUrlMode !== "SOURCE_URLS" ? "selected" : ""}>Use one base URL</option>
          <option value="SOURCE_URLS" ${connection.apiUrlMode === "SOURCE_URLS" ? "selected" : ""}>Keep each request's URL</option>
        </select></label>
        ${baseUrlOverrideField(connection.baseUrlOverride || "", connection.apiUrlMode === "SOURCE_URLS", connection.baseUrl || "")}
      </div>
      <p class="url-policy-warning">The target API must already be running. <span class="mono">localhost</span> means the machine running MCP Server.</p>
      <div class="form-row">
        <label class="form-field"><span>Authentication</span><select class="form-input" name="authMode">
          ${["NONE", "BASIC", "BEARER", "API_KEY_HEADER"].map((mode) => `<option value="${mode}" ${connection.authMode === mode ? "selected" : ""}>${mode.replaceAll("_", " ")}</option>`).join("")}
        </select></label>
        <label class="form-field"><span>Username / header name</span><input class="form-input" name="username" value="${escapeAttr(connection.authUsername || "")}"></label>
      </div>
      <label class="form-field"><span>New secret <small>(blank keeps current)</small></span><input class="form-input" name="password" type="password"></label>
      <div class="form-actions"><button class="btn btn-ghost" type="button" data-action="cancel-auth" ${state.submitting ? "disabled" : ""}>Cancel</button><button class="btn btn-primary" type="submit" ${state.submitting ? "disabled" : ""}>${state.submitting ? "Saving settings…" : "Save settings"}</button></div>
    </form>`;
  }

  function toolList(connection) {
    if (connection.type !== "API_COLLECTION") return "";
    const expanded = state.expanded.has(connection.id);
    const tools = state.tools.get(connection.id);
    return `<div class="tool-list">
      <button class="tool-list-header" type="button" data-action="toggle-tools" data-id="${escapeAttr(connection.id)}" aria-expanded="${expanded}">
        ${icon("chevron", 14, `file-group-chevron ${expanded ? "chev-open" : ""}`)}${icon("hash", 14)}
        <span>${tools ? `${expanded ? "Hide" : "Show"} ${tools.length} tools — ${tools.filter((tool) => tool.enabled).length} enabled, ${tools.filter((tool) => tool.pending).length} pending review` : `${expanded ? "Hide" : "Show"} tools`}</span>
      </button>
      ${expanded ? tools
        ? `<div class="tool-category">${tools.map((tool) => `<div class="tool-row">
          <span class="method-badge mono ${tool.method === "GET" ? "" : "method-write"}">${escapeHtml(tool.method)}</span>
          <div class="tool-row-info"><span class="tool-row-name mono">${escapeHtml(tool.name)}</span><span class="tool-row-desc">${escapeHtml(tool.displayName)}</span></div>
          ${tool.pending ? '<span class="tool-pending-badge mono">pending</span>' : ""}
          ${tool.method === "GET" && tool.enabled ? `<span class="tool-knowledge-toggle" title="Ingest responses into search">${icon("book", 13)}${toggle(tool.knowledgeSource, "", "toggle-knowledge", tool.id, `${tool.knowledgeSource ? "Stop using" : "Use"} ${tool.displayName || tool.name} as search content`)}</span>` : ""}
          ${toggle(tool.enabled, tool.enabled ? "Enabled" : "Off", "toggle-tool", tool.id, `${tool.enabled ? "Disable" : "Enable"} ${tool.displayName || tool.name}`)}
        </div>`).join("")}</div>`
        : '<p class="tool-list-empty">Loading tools…</p>' : ""}
    </div>`;
  }

  function renderRow(connection) {
    const busy = state.jobs.has(connection.id);
    const job = state.jobs.get(connection.id);
    const isApi = connection.type === "API_COLLECTION";
    return `<div class="connection-block">
      <div class="plugin-row connection-row">
        <div class="connection-row-summary">
          <div class="plugin-info">
            <div class="plugin-name-row"><span class="plugin-name">${escapeHtml(connection.name)}</span><span class="plugin-category mono optional">${typeLabel(connection.type)}</span>${connection.specFormat ? `<span class="plugin-category mono optional">${escapeHtml(connection.specFormat)}</span>` : ""}${isApi ? `<span class="plugin-category mono optional">${connection.apiUrlMode === "SOURCE_URLS" ? "per-request URLs" : "one base URL"}</span>` : ""}</div>
            <p class="plugin-description">${escapeHtml(connection.baseUrl || connection.specSourceUrl || (isApi ? "No single base URL" : "No base URL"))}</p>
            <div class="plugin-health mono">${escapeHtml(healthLabel(connection))}${job?.itemsTotal ? ` — ${job.itemsProcessed}/${job.itemsTotal}` : busy ? " — working…" : ""}</div>
          </div>
          <div class="plugin-status"><span class="status-pill ${statusClass(connection.status)}">${connection.status === "CONNECTED" ? icon("check", 12) : connection.status === "ERROR" ? icon("alert", 12) : ""}${statusLabel(connection, busy)}</span></div>
        </div>
        <div class="plugin-actions">
          ${busy ? '<div class="install-progress"><div class="install-progress-bar"></div></div>' : `<button class="btn btn-ghost" type="button" data-action="backfill" data-id="${escapeAttr(connection.id)}" ${connection.status === "CONNECTED" ? "" : "disabled"}>${isApi ? "Refresh content" : "Import content"}</button>`}
          ${!isApi ? `<button class="btn btn-ghost" type="button" data-action="test-connection" data-id="${escapeAttr(connection.id)}" ${connection.status === "DISABLED" ? "disabled" : ""}>Test connection</button>` : ""}
          ${isApi ? '<a class="btn btn-ghost" href="/apps">View in APIs</a>' : ""}
          <button class="btn btn-ghost ${state.editingAuth === connection.id ? "is-active" : ""}" type="button" data-action="edit-auth" data-id="${escapeAttr(connection.id)}">Settings</button>
          ${toggle(connection.status !== "DISABLED", connection.status === "DISABLED" ? "Disabled" : "Enabled", "toggle-connection", connection.id, `${connection.status === "DISABLED" ? "Enable" : "Disable"} ${connection.name}`)}
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
      state.formName = "";
      state.specUrl = "";
      state.baseUrlOverride = "";
      state.apiUrlMode = "CONNECTION_BASE";
      resetSpecInspection();
      render();
    } else if (action === "choose-spec") {
      outlet.querySelector("#spec-file")?.click();
    } else if (action === "detect-url-auth") {
      const formData = new FormData(target.closest("form"));
      const specUrl = formData.get("specUrl");
      state.formName = formData.get("name") || "";
      state.specUrl = specUrl || "";
      try {
        const detected = await api.detectImportAuth({ specUrl });
        applySpecInspection(detected);
      } catch {
        resetSpecInspection();
        state.authNotice = "The spec could not be inspected; authentication can still be selected manually.";
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
        } else if (action === "test-connection" && connection) {
          const job = await api.testConnection(id);
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

  outlet.addEventListener("input", (event) => {
    if (!event.target.closest("#connection-form")) return;
    if (event.target.name === "name") state.formName = event.target.value;
    if (event.target.name === "baseUrl") state.baseUrlOverride = event.target.value;
    if (event.target.name === "specUrl") {
      state.specUrl = event.target.value;
      resetSpecInspection();
      const readOnlyUrl = event.target.closest("form")?.querySelector(".connection-url-readonly");
      if (readOnlyUrl) readOnlyUrl.value = "Inspect the collection to view its API URL";
      event.target.closest("form")?.querySelector(".connection-auth-notice")?.remove();
    }
  }, { signal: abort.signal });

  outlet.addEventListener("change", async (event) => {
    if (event.target.id === "connection-type") {
      state.formType = event.target.value;
      state.authMode = event.target.value === "API_COLLECTION" ? "NONE" : "BASIC";
      state.specFile = null;
      state.baseUrlOverride = "";
      state.apiUrlMode = "CONNECTION_BASE";
      resetSpecInspection();
      render();
    } else if (event.target.id === "spec-source") {
      state.specSource = event.target.value;
      state.specFile = null;
      resetSpecInspection();
      render();
    } else if (event.target.name === "apiUrlMode" && event.target.closest("#connection-form")) {
      state.apiUrlMode = event.target.value;
      render();
    } else if (event.target.name === "apiUrlMode" && event.target.closest(".connection-auth-form")) {
      const baseUrlInput = event.target.closest("form")?.querySelector("input[name=\"baseUrl\"]");
      if (baseUrlInput) {
        const sourceUrls = event.target.value === "SOURCE_URLS";
        baseUrlInput.disabled = sourceUrls;
        baseUrlInput.setAttribute("aria-disabled", String(sourceUrls));
        const help = event.target.closest("form")?.querySelector("#base-url-override-help");
        if (help) {
          help.textContent = sourceUrls
            ? "Each request keeps the host declared in the source file."
            : "Leave blank to use the API URL declared by the document.";
        }
      }
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
          applySpecInspection(detected);
        } catch {
          resetSpecInspection();
          state.authNotice = "The file could not be inspected; authentication can still be selected manually.";
        }
      }
      render();
    }
  }, { signal: abort.signal });

  outlet.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (state.submitting) return;
    const form = event.target;
    const data = Object.fromEntries(new FormData(form));
    state.submitting = true;
    render();
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
          const input = {
            type: state.formType,
            name: data.name,
            username: data.username || "",
            password: data.password || "",
            specUrl: data.specUrl,
            authMode: state.authMode,
            apiKeyHeader: data.apiKeyHeader,
            apiUrlMode: data.apiUrlMode,
          };
          input.baseUrl = data.baseUrl || undefined;
          result = await api.createConnection(input);
        }
        state.formOpen = false;
        state.specFile = null;
        state.formName = "";
        state.specUrl = "";
        state.baseUrlOverride = "";
        state.apiUrlMode = "CONNECTION_BASE";
        resetSpecInspection();
        await load();
        watchJob(result.id, result.jobId);
      } else if (form.classList.contains("connection-auth-form")) {
        const connection = state.connections.find((item) => item.id === form.dataset.connectionId);
        const input = {
          name: data.name || undefined,
          authMode: data.authMode,
          username: data.username || undefined,
          password: data.password || undefined,
          apiUrlMode: data.apiUrlMode,
        };
        input.baseUrl = connection?.type === "API_COLLECTION"
          ? data.baseUrl
          : data.baseUrl || undefined;
        const result = await api.updateConnection(form.dataset.connectionId, input);
        state.editingAuth = "";
        state.editAuthMode = "";
        state.notice = "Connection settings saved; the source is being verified.";
        await load();
        watchJob(form.dataset.connectionId, result.jobId);
      }
    } catch (error) {
      state.error = message(error, "Could not save connection");
    } finally {
      state.submitting = false;
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
