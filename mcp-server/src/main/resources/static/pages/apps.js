import { api } from "../api.js";
import {
  banner,
  emptyState,
  escapeAttr,
  escapeHtml,
  icon,
  message,
  on,
  statusClass,
  toggle,
} from "../ui.js";

const TABS_KEY = "mcp.apps.openTabs.v1";

function plural(count, noun) {
  return `${count} ${noun}${count === 1 ? "" : "s"}`;
}

function loadTabs() {
  try {
    return JSON.parse(localStorage.getItem(TABS_KEY)) || { toolIds: [], activeToolId: null };
  } catch {
    return { toolIds: [], activeToolId: null };
  }
}

export async function mount(outlet) {
  const stored = loadTabs();
  const state = {
    connections: [],
    tools: [],
    groups: [],
    loading: true,
    error: "",
    notice: "",
    query: "",
    expanded: new Set(),
    selectedGroupId: null,
    groupDetail: null,
    groupForm: false,
    editingMembers: false,
    editApps: new Set(),
    editTools: new Set(),
    tabs: stored.toolIds || [],
    activeToolId: stored.activeToolId,
    draftConnectionId: "",
    requestResult: null,
    requestPreview: null,
    requestError: "",
    requestTab: "params",
    requestDrafts: new Map(),
    previewTimer: null,
    manualEditToolId: null,
    manualDrafts: new Map(),
  };
  const abort = new AbortController();

  const apiConnections = () => state.connections.filter((connection) => connection.type === "API_COLLECTION");
  const selectedTool = () => state.tools.find((tool) => tool.id === state.activeToolId);
  const connectionTools = (id) => state.tools.filter((tool) => tool.connectionId === id);
  const appSlug = (id) => connectionTools(id)[0]?.appSlug || "";

  function newKvRow() {
    return { key: "", value: "", enabled: true };
  }

  function requestDraft(tool) {
    if (!state.requestDrafts.has(tool.id)) {
      const values = {};
      for (const [name, property] of Object.entries(tool.paramsSchema?.properties || {})) {
        if (property.default !== undefined) values[name] = String(property.default);
        else values[name] = property.type === "boolean" ? "false" : "";
      }
      state.requestDrafts.set(tool.id, {
        values,
        extraQuery: [newKvRow()],
        extraHeaders: [newKvRow()],
        bodyMode: "SCHEMA",
        rawBody: tool.bodyTemplate || "",
        rawContentType: "application/json",
        authMode: tool.authMode || "INHERIT",
        authUsername: tool.authUsername || "",
        authSecret: "",
        authNotice: "",
        resolved: null,
        previewLoading: false,
        previewError: "",
        showCode: false,
        history: null,
        historyLoading: false,
      });
    }
    return state.requestDrafts.get(tool.id);
  }

  function paramsFromTool(tool) {
    const properties = tool.paramsSchema?.properties || {};
    const required = new Set(tool.paramsSchema?.required || []);
    return Object.entries(properties).flatMap(([name, property]) => {
      const location = tool.paramLocations?.[name];
      if (!["query", "header"].includes(location)) return [];
      return [{
        name,
        in: location,
        required: required.has(name),
        defaultValue: property.default === undefined ? "" : String(property.default),
        description: property.description || "",
      }];
    });
  }

  function manualDraft(connectionId, tool = null) {
    const key = tool ? `edit:${tool.id}` : `new:${connectionId}`;
    if (!state.manualDrafts.has(key)) {
      state.manualDrafts.set(key, {
        connectionId,
        displayName: tool?.displayName || "",
        method: tool?.method || "GET",
        path: tool?.urlTemplate || "/",
        category: tool?.category || "Manual",
        description: tool?.description || "",
        params: tool ? paramsFromTool(tool) : [],
        bodyTemplate: tool?.bodyTemplate || "",
      });
    }
    return state.manualDrafts.get(key);
  }

  function coerce(raw, property) {
    if (property?.type === "boolean") return raw === "true";
    if (property?.type === "integer") {
      const value = Number.parseInt(raw, 10);
      return Number.isNaN(value) ? raw : value;
    }
    if (property?.type === "number") {
      const value = Number.parseFloat(raw);
      return Number.isNaN(value) ? raw : value;
    }
    if (["array", "object"].includes(property?.type)) {
      try {
        return JSON.parse(raw);
      } catch {
        return raw;
      }
    }
    return raw;
  }

  function requestArgs(tool, draft = requestDraft(tool)) {
    const properties = tool.paramsSchema?.properties || {};
    return Object.fromEntries(Object.entries(properties).flatMap(([name, property]) => {
      const raw = draft.values[name];
      return raw === "" || raw === undefined ? [] : [[name, coerce(raw, property)]];
    }));
  }

  function rowsToMap(rows) {
    return Object.fromEntries(rows.flatMap((row) =>
      row.enabled && row.key.trim() ? [[row.key.trim(), row.value]] : []));
  }

  function requestOverrides(draft) {
    return {
      extraHeaders: rowsToMap(draft.extraHeaders),
      extraQueryParams: rowsToMap(draft.extraQuery),
      bodyMode: draft.bodyMode,
      rawBody: draft.bodyMode === "RAW" ? draft.rawBody : undefined,
      rawContentType: draft.bodyMode === "RAW" ? draft.rawContentType : undefined,
    };
  }

  function saveTabs() {
    localStorage.setItem(TABS_KEY, JSON.stringify({
      toolIds: state.tabs,
      activeToolId: state.activeToolId,
    }));
  }

  function groupSidebar() {
    return `<div class="apps-groups-header"><span class="apps-groups-title">Groups</span><button class="btn btn-ghost apps-new-group-btn" type="button" data-action="toggle-group-form">${icon("plus", 13)} New group</button></div>
      ${state.groupForm ? `<form class="group-form" id="new-group-form"><input class="form-input" name="name" placeholder="Name — becomes the @group handle" required autofocus><input class="form-input" name="description" placeholder="Description (optional)"><div class="form-actions"><button class="btn btn-ghost" type="button" data-action="toggle-group-form">Cancel</button><button class="btn btn-primary" type="submit">Create</button></div></form>` : ""}
      <button class="group-row ${state.selectedGroupId ? "" : "is-active"}" type="button" data-action="select-group" data-id=""><span class="group-row-name">All apps</span><span class="group-row-counts mono">${plural(apiConnections().length, "app")} · ${plural(state.tools.length, "endpoint")}</span></button>
      ${state.groups.map((group) => `<button class="group-row ${state.selectedGroupId === group.id ? "is-active" : ""}" type="button" data-action="select-group" data-id="${escapeAttr(group.id)}"><span class="group-row-name">${escapeHtml(group.name)}</span><span class="group-row-slug mono">@${escapeHtml(group.slug)}</span><span class="group-row-counts mono">${plural(group.appCount, "app")} · ${plural(group.toolCount, "endpoint")} · ${group.enabledToolCount} enabled</span></button>`).join("")}
      ${!state.loading && !state.groups.length && !state.groupForm ? '<p class="group-empty">No groups yet. Create one for batch control and an @group search handle.</p>' : ""}`;
  }

  function inGroup(connection, tool) {
    if (!state.selectedGroupId || !state.groupDetail || state.editingMembers) return true;
    const appIds = new Set(state.groupDetail.apps.map((app) => app.id));
    const toolIds = new Set(state.groupDetail.tools.map((item) => item.id));
    return appIds.has(connection.id) || (tool && toolIds.has(tool.id));
  }

  function visibleTools(connection) {
    const query = state.query.toLowerCase().trim();
    return connectionTools(connection.id).filter((tool) => {
      if (!inGroup(connection, tool)) return false;
      return !query || connection.name.toLowerCase().includes(query) || tool.name.toLowerCase().includes(query) || tool.displayName.toLowerCase().includes(query);
    });
  }

  function appTree() {
    const connections = apiConnections().filter((connection) => {
      const tools = visibleTools(connection);
      return tools.length || (!state.selectedGroupId && (!state.query || connection.name.toLowerCase().includes(state.query.toLowerCase())));
    });
    if (!connections.length) return `<p class="group-empty">${apiConnections().length ? "Nothing matches this view." : "No apps connected. Import an API collection on Connections."}</p>`;
    return connections.map((connection) => {
      const tools = visibleTools(connection);
      const open = state.expanded.has(connection.id) || Boolean(state.query);
      return `<div>
        <div class="tree-row app-tree-row ${open ? "is-active" : ""}" role="button" tabindex="0" data-action="toggle-app" data-id="${escapeAttr(connection.id)}">
          <span class="tree-chevron">${icon("chevron", 14, open ? "chev-open" : "")}</span>
          <span class="status-dot ${connection.status === "CONNECTED" ? "is-active" : connection.status === "ERROR" ? "is-error" : ""}"></span>
          <div class="app-tree-name-wrap"><span class="app-tree-name">${escapeHtml(connection.name)}</span><span class="app-tree-meta mono">@${escapeHtml(appSlug(connection.id))} · ${plural(tools.length, "endpoint")}</span></div>
          <button class="btn btn-ghost rb-icon-btn app-tree-add-btn" type="button" data-action="new-request" data-id="${escapeAttr(connection.id)}" aria-label="New request in ${escapeAttr(connection.name)}">${icon("plus", 12)}</button>
        </div>
        ${open ? `<div class="tree-children">${tools.map((tool) => `<button type="button" class="tree-row tool-tree-row ${state.activeToolId === tool.id ? "is-active" : ""}" data-action="open-tool" data-id="${escapeAttr(tool.id)}"><span class="method-badge mono tool-tree-method ${tool.method === "GET" ? "" : "method-write"}">${escapeHtml(tool.method)}</span><span class="tool-tree-name">${escapeHtml(tool.displayName || tool.name)}</span><span class="tool-tree-dot ${tool.enabled ? "is-enabled" : ""}"></span></button>`).join("") || '<p class="app-tools-empty">No endpoints to show.</p>'}</div>` : ""}
      </div>`;
    }).join("");
  }

  function groupBanner() {
    const group = state.groupDetail;
    if (!state.selectedGroupId) return "";
    if (!group) return '<div class="group-banner"><span class="group-banner-loading">Loading group…</span></div>';
    const info = state.groups.find((item) => item.id === group.id);
    return `<div class="group-banner">
      <div class="group-banner-info"><div class="group-banner-title">${icon("at", 15)}<span>${escapeHtml(group.name)}</span><span class="group-banner-slug mono">@${escapeHtml(group.slug)}</span></div>${group.description ? `<p class="group-banner-desc">${escapeHtml(group.description)}</p>` : ""}${state.editingMembers ? '<p class="group-banner-desc">Select whole apps or individual endpoints. Whole-app membership includes every endpoint.</p>' : ""}<div class="group-banner-counts mono">${plural(info?.appCount ?? group.apps.length, "app")} · ${plural(info?.toolCount ?? group.tools.length, "endpoint")}${state.notice ? ` — ${escapeHtml(state.notice)}` : ""}</div></div>
      <div class="group-banner-actions">${state.editingMembers
        ? '<button class="btn btn-ghost" type="button" data-action="cancel-members">Cancel</button><button class="btn btn-primary" type="button" data-action="save-members">Save members</button>'
        : '<button class="btn btn-ghost" type="button" data-action="enable-group">Enable all</button><button class="btn btn-ghost" type="button" data-action="disable-group">Disable all</button><button class="btn btn-ghost" type="button" data-action="edit-members">Edit members</button><button class="btn btn-ghost btn-danger" type="button" data-action="delete-group">' + icon("trash", 13) + " Delete</button>"}
      </div>
    </div>`;
  }

  function memberEditor() {
    return `<div class="apps-directory">${apiConnections().map((connection) => {
      const checked = state.editApps.has(connection.id);
      const tools = connectionTools(connection.id);
      return `<section class="app-section"><div class="app-header">
        <input type="checkbox" class="member-check" data-member-app="${escapeAttr(connection.id)}" ${checked ? "checked" : ""} aria-label="Include ${escapeAttr(connection.name)}">
        <button class="app-header-main" type="button" data-action="toggle-app" data-id="${escapeAttr(connection.id)}">${icon("chevron", 14, "app-chevron chev-open")}<div class="app-heading"><div class="app-name-row"><span class="app-name">${escapeHtml(connection.name)}</span><span class="app-slug mono">@${escapeHtml(appSlug(connection.id))}</span><span class="status-pill ${statusClass(connection.status)}">${escapeHtml(connection.status)}</span></div><div class="app-meta mono">${escapeHtml(connection.baseUrl || connection.specSourceUrl || "")} — ${tools.filter((tool) => tool.enabled).length} enabled / ${tools.length} endpoints</div></div></button>
      </div><div class="app-tools"><div class="tool-category">${tools.map((tool) => `<div class="tool-row"><input type="checkbox" class="member-check" data-member-tool="${escapeAttr(tool.id)}" ${checked || state.editTools.has(tool.id) ? "checked" : ""} ${checked ? "disabled" : ""}><span class="method-badge mono ${tool.method === "GET" ? "" : "method-write"}">${escapeHtml(tool.method)}</span><div class="tool-row-info"><span class="tool-row-name mono">${escapeHtml(tool.name)}</span><span class="tool-row-desc">${escapeHtml(tool.displayName)}</span></div></div>`).join("")}</div></div></section>`;
    }).join("")}</div>`;
  }

  function tabStrip() {
    const tabs = state.tabs.map((id) => state.tools.find((tool) => tool.id === id)).filter(Boolean);
    const draft = state.draftConnectionId ? [{ id: "draft", method: "NEW", displayName: "New request" }] : [];
    return `<div class="rb-tabstrip">${[...tabs, ...draft].map((tool) => `<button class="rb-tabstrip-tab ${state.activeToolId === tool.id || (tool.id === "draft" && !state.activeToolId) ? "is-active" : ""}" type="button" data-action="${tool.id === "draft" ? "activate-draft" : "open-tool"}" data-id="${escapeAttr(tool.id)}"><span class="method-badge mono rb-tabstrip-method ${tool.method === "GET" || tool.method === "NEW" ? "" : "method-write"}">${escapeHtml(tool.method)}</span><span class="rb-tabstrip-name">${escapeHtml(tool.displayName || tool.name)}</span><span class="rb-tabstrip-close" data-action="close-tab" data-id="${escapeAttr(tool.id)}">${icon("close", 11)}</span></button>`).join("")}</div>`;
  }

  function schemaFields(tool, draft) {
    const properties = tool.paramsSchema?.properties || {};
    const required = new Set(tool.paramsSchema?.required || []);
    return Object.entries(properties).map(([name, property]) => {
      const value = draft.values[name] ?? "";
      let control;
      if (property.enum?.length) {
        control = `<select class="form-input" name="${escapeAttr(name)}" data-request-value="${escapeAttr(name)}" ${required.has(name) ? "required" : ""}><option value="">Select…</option>${property.enum.map((option) => `<option value="${escapeAttr(option)}" ${String(option) === value ? "selected" : ""}>${escapeHtml(option)}</option>`).join("")}</select>`;
      } else if (property.type === "boolean") {
        control = `<select class="form-input" name="${escapeAttr(name)}" data-request-value="${escapeAttr(name)}"><option value="false" ${value === "false" ? "selected" : ""}>false</option><option value="true" ${value === "true" ? "selected" : ""}>true</option></select>`;
      } else if (["array", "object"].includes(property.type)) {
        control = `<textarea class="form-input tool-form-textarea mono" name="${escapeAttr(name)}" data-request-value="${escapeAttr(name)}" rows="3" ${required.has(name) ? "required" : ""} placeholder="${property.type === "array" ? "[…]" : "{…}"}">${escapeHtml(value)}</textarea>`;
      } else {
        control = `<input class="form-input" name="${escapeAttr(name)}" data-request-value="${escapeAttr(name)}" value="${escapeAttr(value)}" ${required.has(name) ? "required" : ""} ${["integer", "number"].includes(property.type) ? `type="number" step="${property.type === "integer" ? "1" : "any"}"` : 'type="text"'}>`;
      }
      return `<label class="tool-field"><span>${escapeHtml(name)}${required.has(name) ? ' <b class="tool-form-required">*</b>' : ""}<small class="tool-form-type mono">${escapeHtml(property.type || "string")}</small></span>${control}${property.description ? `<small>${escapeHtml(property.description)}</small>` : ""}</label>`;
    }).join("");
  }

  function kvEditor(kind, rows, placeholder) {
    return `<div class="rb-kv-table">${rows.map((row, index) => `<div class="rb-kv-row rb-kv-row-editable">
      <input type="checkbox" data-kv-kind="${kind}" data-kv-index="${index}" data-kv-field="enabled" ${row.enabled ? "checked" : ""} aria-label="Row ${index + 1} enabled">
      <input class="form-input" data-kv-kind="${kind}" data-kv-index="${index}" data-kv-field="key" value="${escapeAttr(row.key)}" placeholder="${escapeAttr(placeholder)}" aria-label="Row ${index + 1} name">
      <input class="form-input" data-kv-kind="${kind}" data-kv-index="${index}" data-kv-field="value" value="${escapeAttr(row.value)}" placeholder="value" aria-label="Row ${index + 1} value">
      <button class="btn btn-ghost rb-icon-btn" type="button" data-action="remove-kv" data-kind="${kind}" data-index="${index}" aria-label="Remove row">${icon("trash", 12)}</button>
    </div>`).join("")}</div><button class="btn btn-ghost rb-add-row" type="button" data-action="add-kv" data-kind="${kind}">${icon("plus", 12)} Add row</button>`;
  }

  function previewCode(preview) {
    const headers = Object.entries(preview.headers || {}).map(([name, value]) =>
      `  -H ${JSON.stringify(`${name}: ${value}`)}`).join(" \\\n");
    const body = preview.body === undefined ? "" : ` \\\n  --data-raw ${JSON.stringify(preview.body)}`;
    const curl = `curl -X ${preview.method} ${JSON.stringify(preview.url)}${headers ? ` \\\n${headers}` : ""}${body}`;
    const options = { method: preview.method };
    if (Object.keys(preview.headers || {}).length) options.headers = preview.headers;
    if (preview.body !== undefined) options.body = preview.body;
    const fetchCode = `const response = await fetch(${JSON.stringify(preview.url)}, ${JSON.stringify(options, null, 2)});\nconst body = await response.text();`;
    return `<section class="rb-code-panel"><div class="rb-code-head"><strong>cURL</strong><button class="btn btn-ghost btn-sm" type="button" data-action="copy-code" data-code="${escapeAttr(curl)}">Copy</button></div><pre class="tool-result-body rb-code-block"><code>${escapeHtml(curl)}</code></pre><div class="rb-code-head"><strong>Browser fetch</strong><button class="btn btn-ghost btn-sm" type="button" data-action="copy-code" data-code="${escapeAttr(fetchCode)}">Copy</button></div><pre class="tool-result-body rb-code-block"><code>${escapeHtml(fetchCode)}</code></pre></section>`;
  }

  function resolvedPanel(draft) {
    if (draft.previewLoading) return '<section class="rb-resolved" id="request-resolved"><p class="rb-hint">Resolving request…</p></section>';
    if (draft.previewError) return `<section class="rb-resolved" id="request-resolved">${banner(draft.previewError)}</section>`;
    if (!draft.resolved) return '<section class="rb-resolved" id="request-resolved"><p class="rb-hint">Fill the request fields, then preview or send.</p></section>';
    const preview = draft.resolved;
    const headers = Object.entries(preview.headers || {});
    return `<section class="rb-resolved" id="request-resolved">
      <div class="rb-resolved-head"><span>Resolved request</span><button class="btn btn-ghost btn-sm" type="button" data-action="toggle-code">${draft.showCode ? "Hide code" : "Code"}</button></div>
      <div class="rb-url-bar rb-url-bar-readout"><span class="method-badge mono ${preview.method === "GET" ? "" : "method-write"}">${escapeHtml(preview.method)}</span><code class="rb-url-readout">${escapeHtml(preview.url)}</code></div>
      ${headers.length ? `<div class="rb-kv-table rb-kv-table-readonly">${headers.map(([name, value]) => `<div class="rb-kv-row"><span class="rb-kv-key mono">${escapeHtml(name)}</span><span class="rb-kv-value mono">${escapeHtml(value)}</span></div>`).join("")}</div>` : ""}
      ${preview.body !== undefined ? `<pre class="tool-result-body rb-preview-body"><code>${escapeHtml(preview.body)}</code></pre>` : ""}
      ${draft.showCode ? previewCode(preview) : ""}
    </section>`;
  }

  function historyPanel(draft) {
    if (draft.historyLoading) return '<p class="rb-hint">Loading invocation history…</p>';
    if (!draft.history?.length) return '<p class="rb-hint">No prior invocations recorded for this endpoint.</p>';
    return `<div class="rb-history-list">${draft.history.map((entry) => `<button class="rb-history-row" type="button" data-action="rerun-history" data-id="${escapeAttr(entry.id)}" ${entry.arguments ? "" : "disabled"}><span class="mono rb-history-event">${escapeHtml(entry.eventType)}</span><span class="rb-history-summary">${escapeHtml(entry.resultSummary || entry.error || "Request recorded")}</span><span class="mono rb-history-time">${escapeHtml(entry.createdAt ? new Date(entry.createdAt).toLocaleString() : "")}</span></button>`).join("")}</div>`;
  }

  function authPanel(tool, draft) {
    const mode = draft.authMode;
    return `<div class="rb-tab-body">
      <p class="rb-hint">Saved for this endpoint. It overrides the connection authentication for every invocation; secrets are never displayed after saving.</p>
      <select class="form-input" data-request-setting="authMode" aria-label="Request authentication mode">
        <option value="INHERIT" ${mode === "INHERIT" ? "selected" : ""}>Inherit from connection</option>
        <option value="NONE" ${mode === "NONE" ? "selected" : ""}>No authentication</option>
        <option value="BASIC" ${mode === "BASIC" ? "selected" : ""}>Basic authentication</option>
        <option value="BEARER" ${mode === "BEARER" ? "selected" : ""}>Bearer token</option>
        <option value="API_KEY_HEADER" ${mode === "API_KEY_HEADER" ? "selected" : ""}>API key header</option>
      </select>
      ${["BASIC", "API_KEY_HEADER"].includes(mode) ? `<input class="form-input" data-request-setting="authUsername" value="${escapeAttr(draft.authUsername)}" placeholder="${mode === "BASIC" ? "Username" : "Header name (for example X-Api-Key)"}">` : ""}
      ${["BASIC", "BEARER", "API_KEY_HEADER"].includes(mode) ? `<input class="form-input" type="password" data-request-setting="authSecret" value="${escapeAttr(draft.authSecret)}" placeholder="${tool.authMode === mode ? "Leave blank to keep the saved secret" : mode === "BASIC" ? "Password" : mode === "BEARER" ? "Token" : "API key"}">` : ""}
      <div class="form-actions">${draft.authNotice ? `<span class="rb-auth-notice">${escapeHtml(draft.authNotice)}</span>` : ""}<button class="btn btn-primary" type="button" data-action="save-auth">Save auth</button></div>
    </div>`;
  }

  function responsePanel(result) {
    if (!result) return "";
    const headers = Object.entries(result.headers || {});
    return `<section class="tool-result-panel">
      <header class="tool-result-header"><div><span class="status-pill ${result.status >= 200 && result.status < 300 ? "status-active" : "status-error"}">HTTP ${escapeHtml(result.status)}</span><span class="mono">${escapeHtml(result.latencyMs)} ms</span>${result.contentType ? `<span class="mono rb-response-meta">${escapeHtml(result.contentType)}</span>` : ""}</div><button class="btn btn-ghost btn-sm" type="button" data-action="clear-response">Clear</button></header>
      ${headers.length ? `<details class="rb-response-headers"><summary>${headers.length} response headers</summary><div class="rb-kv-table rb-kv-table-readonly">${headers.map(([name, values]) => `<div class="rb-kv-row"><span class="rb-kv-key mono">${escapeHtml(name)}</span><span class="rb-kv-value mono">${escapeHtml(Array.isArray(values) ? values.join(", ") : values)}</span></div>`).join("")}</div></details>` : ""}
      <pre class="tool-result-body"><code>${escapeHtml(result.body || "")}</code></pre>${result.truncated ? '<p class="rb-hint">Response display was truncated by the server.</p>' : ""}
    </section>`;
  }

  function manualParamRows(draft) {
    if (!draft.params.length) return '<p class="rb-hint">No query or header parameters. Path placeholders such as {id} are inferred automatically.</p>';
    return draft.params.map((param, index) => `<div class="rb-kv-row rb-manual-param-row">
      <input class="form-input" data-manual-param="${index}" data-param-field="name" value="${escapeAttr(param.name)}" placeholder="Parameter name">
      <select class="form-input" data-manual-param="${index}" data-param-field="in"><option value="query" ${param.in === "query" ? "selected" : ""}>Query</option><option value="header" ${param.in === "header" ? "selected" : ""}>Header</option></select>
      <input class="form-input" data-manual-param="${index}" data-param-field="defaultValue" value="${escapeAttr(param.defaultValue)}" placeholder="Default value">
      <label class="rb-required-check"><input type="checkbox" data-manual-param="${index}" data-param-field="required" ${param.required ? "checked" : ""}> Required</label>
      <button class="btn btn-ghost rb-icon-btn" type="button" data-action="remove-manual-param" data-index="${index}" aria-label="Remove parameter">${icon("trash", 12)}</button>
    </div>`).join("");
  }

  function manualRequestForm(connectionId, tool = null) {
    const draft = manualDraft(connectionId, tool);
    return `<form class="rb-panel" id="manual-tool-form" data-edit-id="${escapeAttr(tool?.id || "")}">
      <div class="rb-request-line"><select class="form-input rb-method-select" data-manual-field="method">${["GET", "POST", "PUT", "PATCH", "DELETE"].map((method) => `<option ${draft.method === method ? "selected" : ""}>${method}</option>`).join("")}</select><input class="form-input mono" data-manual-field="path" value="${escapeAttr(draft.path)}" placeholder="/path/to/resource" required><button class="btn btn-primary" type="submit">${tool ? "Save changes" : "Save request"}</button></div>
      <div class="rb-tabs"><button class="rb-tab is-active" type="button">Request shape</button></div>
      <div class="rb-tab-content"><div class="form-row"><label class="form-field"><span>Display name</span><input class="form-input" data-manual-field="displayName" value="${escapeAttr(draft.displayName)}" required></label><label class="form-field"><span>Category</span><input class="form-input" data-manual-field="category" value="${escapeAttr(draft.category)}"></label></div>
      <label class="form-field"><span>Description</span><input class="form-input" data-manual-field="description" value="${escapeAttr(draft.description)}"></label>
      <div class="rb-manual-params"><div class="rb-manual-params-head"><span>Query and header parameters</span><button class="btn btn-ghost" type="button" data-action="add-manual-param">${icon("plus", 12)} Add parameter</button></div>${manualParamRows(draft)}</div>
      <label class="form-field"><span>Body template</span><textarea class="form-input mono" data-manual-field="bodyTemplate" rows="8" placeholder='{"name":"{name}"}'>${escapeHtml(draft.bodyTemplate)}</textarea></label></div>
      ${tool ? '<div class="rb-footer-actions"><button class="btn btn-ghost" type="button" data-action="cancel-edit-tool">Cancel</button></div>' : ""}
    </form>`;
  }

  function requestBuilder() {
    const tool = selectedTool();
    if (!tool && !state.draftConnectionId) {
      return emptyState("Choose an endpoint to start a request", "Expand an app in the sidebar, or use its + action to create a custom request.", '<a href="/guide" data-link class="empty-link">Read the app query guide</a>');
    }
    if (!tool) return manualRequestForm(state.draftConnectionId);
    if (state.manualEditToolId === tool.id) return manualRequestForm(tool.connectionId, tool);
    const draft = requestDraft(tool);
    const tabBody = state.requestTab === "params"
      ? `<div class="rb-tab-body"><div class="tool-form">${schemaFields(tool, draft) || '<p class="tool-form-empty">This endpoint has no schema parameters.</p>'}</div><div class="rb-manual-params-head"><span>Ad-hoc query parameters</span></div>${kvEditor("extraQuery", draft.extraQuery, "Parameter name")}</div>`
      : state.requestTab === "headers"
        ? `<div class="rb-tab-body"><p class="rb-hint">These headers are layered on top of imported and schema-derived headers.</p>${kvEditor("extraHeaders", draft.extraHeaders, "Header name")}</div>`
        : state.requestTab === "body"
          ? `<div class="rb-tab-body"><div class="rb-body-mode">${["SCHEMA", "NONE", "RAW"].map((mode) => `<label class="rb-body-mode-option"><input type="radio" name="bodyMode" data-request-setting="bodyMode" value="${mode}" ${draft.bodyMode === mode ? "checked" : ""}>${mode === "SCHEMA" ? "Schema/default body" : mode === "NONE" ? "No body" : "Raw body"}</label>`).join("")}</div>${draft.bodyMode === "RAW" ? `<select class="form-input" data-request-setting="rawContentType"><option value="application/json" ${draft.rawContentType === "application/json" ? "selected" : ""}>application/json</option><option value="application/xml" ${draft.rawContentType === "application/xml" ? "selected" : ""}>application/xml</option><option value="text/plain" ${draft.rawContentType === "text/plain" ? "selected" : ""}>text/plain</option><option value="application/x-www-form-urlencoded" ${draft.rawContentType === "application/x-www-form-urlencoded" ? "selected" : ""}>application/x-www-form-urlencoded</option></select><textarea class="form-input tool-form-textarea mono" data-request-setting="rawBody" rows="10" placeholder="Raw request body">${escapeHtml(draft.rawBody)}</textarea>` : '<p class="rb-hint">Schema mode renders the imported body template. No-body mode suppresses it for this invocation.</p>'}</div>`
          : state.requestTab === "auth"
            ? authPanel(tool, draft)
            : historyPanel(draft);
    return `<div class="rb-panel">
      <form id="request-form">
        <div class="rb-request-line"><span class="method-badge mono ${tool.method === "GET" ? "" : "method-write"}">${escapeHtml(tool.method)}</span><code class="rb-url-template">${escapeHtml(tool.urlTemplate)}</code><button class="btn btn-ghost" type="button" data-action="preview-request">Preview</button><button class="btn btn-primary" type="submit" ${tool.enabled ? "" : "disabled"}>${icon("play", 14)} ${tool.method === "GET" ? "Send" : "Review"}</button></div>
        <div class="rb-tabs">${["params", "headers", "body", "auth", "history"].map((tab) => `<button class="rb-tab ${state.requestTab === tab ? "is-active" : ""}" type="button" data-action="request-tab" data-id="${tab}">${tab === "history" ? "History" : tab[0].toUpperCase() + tab.slice(1)}</button>`).join("")}</div>
        <div class="rb-tab-content">${tabBody}</div>
      </form>
      ${resolvedPanel(draft)}
      <div class="rb-footer-actions">${toggle(tool.enabled, tool.enabled ? "Enabled" : "Disabled", "toggle-tool", tool.id)}<span class="rb-endpoint-meta mono">${escapeHtml(tool.name)} · ${escapeHtml(tool.origin)} · ${escapeHtml(tool.category)}</span>${tool.origin === "MANUAL" ? `<button class="btn btn-ghost" type="button" data-action="edit-tool" data-id="${escapeAttr(tool.id)}">Edit shape</button><button class="btn btn-ghost btn-danger" type="button" data-action="delete-tool" data-id="${escapeAttr(tool.id)}">${icon("trash", 13)} Delete</button>` : ""}</div>
      ${state.requestError ? banner(state.requestError) : ""}
      ${state.requestPreview ? `<section class="tool-confirm-panel"><div class="tool-confirm-heading"><div><h2>Confirm write request</h2><p class="rb-hint">Review the exact destination and payload before allowing the external change.</p></div></div><div class="tool-preview"><div class="rb-url-bar"><span class="method-badge mono method-write">${escapeHtml(state.requestPreview.preview?.method)}</span><code>${escapeHtml(state.requestPreview.preview?.url)}</code></div>${Object.keys(state.requestPreview.preview?.headers || {}).length ? `<div class="rb-kv-table rb-kv-table-readonly">${Object.entries(state.requestPreview.preview.headers).map(([name, value]) => `<div class="rb-kv-row"><span class="rb-kv-key mono">${escapeHtml(name)}</span><span class="rb-kv-value mono">${escapeHtml(value)}</span></div>`).join("")}</div>` : ""}${state.requestPreview.preview?.body !== undefined ? `<pre class="tool-result-body"><code>${escapeHtml(state.requestPreview.preview.body)}</code></pre>` : ""}</div><div class="form-actions"><button class="btn btn-ghost" type="button" data-action="reject-preview">Reject</button><button class="btn btn-primary" type="button" data-action="confirm-preview">Confirm and send</button></div></section>` : ""}
      ${responsePanel(state.requestResult)}
    </div>`;
  }

  function render() {
    outlet.innerHTML = `<div class="apps-page">
      <aside class="sidebar apps-sidebar">${groupSidebar()}<div class="apps-sidebar-divider"></div>
        <div class="search-field apps-sidebar-search">${icon("search", 14, "search-icon")}<input class="search-input" id="apps-filter" value="${escapeAttr(state.query)}" placeholder="Filter apps and endpoints" aria-label="Filter apps and endpoints"></div>
        <div class="apps-tree">${state.loading ? '<div class="plugins-skeleton"><div class="plugin-row-skeleton"></div></div>' : appTree()}</div>
      </aside>
      <section class="main" aria-labelledby="apps-page-title"><h1 id="apps-page-title" class="sr-only">Apps and API requests</h1>
        ${state.error ? banner(state.error) : ""}${groupBanner()}
        ${state.loading ? '<div class="plugins-skeleton" style="padding:var(--space-md)"><div class="plugin-row-skeleton"></div></div>' : state.editingMembers ? memberEditor() : `${tabStrip()}<div class="rb-workspace">${requestBuilder()}</div>`}
      </section>
    </div>`;
    outlet.querySelector("[autofocus]")?.focus();
  }

  async function load() {
    try {
      const [connections, tools, groups] = await Promise.all([api.listConnections(), api.listTools(), api.listGroups()]);
      state.connections = connections;
      state.tools = tools;
      state.groups = groups;
      state.tabs = state.tabs.filter((id) => tools.some((tool) => tool.id === id));
      if (state.activeToolId && !tools.some((tool) => tool.id === state.activeToolId)) state.activeToolId = state.tabs[0] || null;
      state.error = "";
    } catch (error) {
      state.error = message(error, "Failed to load apps");
    } finally {
      state.loading = false;
      saveTabs();
      render();
    }
  }

  async function selectGroup(id) {
    state.selectedGroupId = id || null;
    state.groupDetail = null;
    state.editingMembers = false;
    state.notice = "";
    render();
    if (id) {
      try {
        state.groupDetail = await api.getGroup(id);
      } catch (error) {
        state.error = message(error, "Failed to load group");
      }
      render();
    }
  }

  function updateResolvedPanel(draft) {
    const current = outlet.querySelector("#request-resolved");
    if (current) current.outerHTML = resolvedPanel(draft);
  }

  async function previewRequest(tool, quiet = false) {
    if (!tool) return;
    const draft = requestDraft(tool);
    draft.previewLoading = true;
    draft.previewError = "";
    if (!quiet) updateResolvedPanel(draft);
    try {
      draft.resolved = await api.previewTool(tool.id, requestArgs(tool, draft), requestOverrides(draft));
    } catch (error) {
      draft.resolved = null;
      draft.previewError = message(error, "Could not resolve request");
    } finally {
      draft.previewLoading = false;
      updateResolvedPanel(draft);
    }
  }

  function schedulePreview() {
    const tool = selectedTool();
    if (!tool || state.manualEditToolId) return;
    clearTimeout(state.previewTimer);
    state.previewTimer = setTimeout(() => previewRequest(tool, true), 350);
  }

  async function loadHistory(tool) {
    const draft = requestDraft(tool);
    draft.historyLoading = true;
    render();
    try {
      const response = await api.fetchAuditLog({ toolName: tool.name, size: 20 });
      draft.history = response.items || [];
    } catch {
      draft.history = [];
    } finally {
      draft.historyLoading = false;
      render();
    }
  }

  on(outlet, "click", "[data-action]", async (event, target) => {
    const { action, id } = target.dataset;
    if (action === "dismiss-banner") {
      state.error = "";
      state.requestError = "";
      const tool = selectedTool();
      if (tool) requestDraft(tool).previewError = "";
      render();
    } else if (action === "toggle-group-form") {
      state.groupForm = !state.groupForm;
      render();
    } else if (action === "select-group") {
      await selectGroup(id);
    } else if (action === "toggle-app") {
      if (state.expanded.has(id)) state.expanded.delete(id); else state.expanded.add(id);
      render();
    } else if (action === "new-request") {
      event.stopPropagation();
      state.draftConnectionId = id;
      state.activeToolId = null;
      state.requestError = "";
      render();
    } else if (action === "open-tool") {
      state.draftConnectionId = "";
      state.manualEditToolId = null;
      state.activeToolId = id;
      if (!state.tabs.includes(id)) state.tabs.push(id);
      state.requestResult = null;
      state.requestPreview = null;
      state.requestError = "";
      state.requestTab = "params";
      saveTabs();
      render();
      schedulePreview();
    } else if (action === "activate-draft") {
      state.activeToolId = null;
      render();
    } else if (action === "close-tab") {
      event.stopPropagation();
      if (id === "draft") state.draftConnectionId = "";
      else {
        state.tabs = state.tabs.filter((tab) => tab !== id);
        if (state.activeToolId === id) state.activeToolId = state.tabs[0] || null;
      }
      saveTabs();
      render();
    } else if (action === "request-tab") {
      state.requestTab = id;
      render();
      if (id === "history") {
        const tool = selectedTool();
        if (tool && requestDraft(tool).history === null) await loadHistory(tool);
      }
    } else if (action === "preview-request") {
      await previewRequest(selectedTool());
    } else if (action === "add-kv") {
      const draft = requestDraft(selectedTool());
      draft[target.dataset.kind].push(newKvRow());
      render();
    } else if (action === "remove-kv") {
      const draft = requestDraft(selectedTool());
      const rows = draft[target.dataset.kind];
      rows.splice(Number(target.dataset.index), 1);
      if (!rows.length) rows.push(newKvRow());
      render();
      schedulePreview();
    } else if (action === "toggle-code") {
      const draft = requestDraft(selectedTool());
      draft.showCode = !draft.showCode;
      updateResolvedPanel(draft);
    } else if (action === "copy-code") {
      await navigator.clipboard.writeText(target.dataset.code || "");
      const label = target.textContent;
      target.textContent = "Copied";
      setTimeout(() => { target.textContent = label; }, 1200);
    } else if (action === "clear-response") {
      state.requestResult = null;
      render();
    } else if (action === "save-auth") {
      const tool = selectedTool();
      const draft = requestDraft(tool);
      try {
        const updated = await api.updateToolAuth(tool.id, {
          mode: draft.authMode === "INHERIT" ? undefined : draft.authMode,
          username: draft.authUsername || undefined,
          secret: draft.authSecret || undefined,
        });
        state.tools = state.tools.map((item) => item.id === updated.id ? updated : item);
        draft.authSecret = "";
        draft.authNotice = draft.authMode === "INHERIT" ? "Now inheriting connection auth" : "Authentication saved";
        render();
        schedulePreview();
      } catch (error) {
        draft.authNotice = message(error, "Could not save authentication");
        render();
      }
    } else if (action === "rerun-history") {
      const tool = selectedTool();
      const draft = requestDraft(tool);
      const entry = draft.history?.find((item) => String(item.id) === String(id));
      if (entry?.arguments && typeof entry.arguments === "object") {
        for (const [name, value] of Object.entries(entry.arguments)) {
          if (Object.hasOwn(draft.values, name)) {
            draft.values[name] = typeof value === "string" ? value : JSON.stringify(value);
          }
        }
        state.requestTab = "params";
        render();
        schedulePreview();
      }
    } else if (action === "edit-tool") {
      state.manualEditToolId = id;
      render();
    } else if (action === "cancel-edit-tool") {
      state.manualEditToolId = null;
      render();
    } else if (action === "add-manual-param") {
      const tool = selectedTool();
      const draft = manualDraft(tool?.connectionId || state.draftConnectionId, state.manualEditToolId ? tool : null);
      draft.params.push({ name: "", in: "query", required: false, defaultValue: "", description: "" });
      render();
    } else if (action === "remove-manual-param") {
      const tool = selectedTool();
      const draft = manualDraft(tool?.connectionId || state.draftConnectionId, state.manualEditToolId ? tool : null);
      draft.params.splice(Number(target.dataset.index), 1);
      render();
    } else if (action === "toggle-tool") {
      const tool = state.tools.find((item) => item.id === id);
      if (!tool) return;
      try {
        await (tool.enabled ? api.disableTool(id) : api.enableTool(id));
        await load();
      } catch (error) {
        state.error = message(error, "Failed to update endpoint");
        render();
      }
    } else if (action === "delete-tool") {
      const tool = state.tools.find((item) => item.id === id);
      if (!tool || !confirm(`Delete "${tool.displayName}"?`)) return;
      try {
        await api.deleteManualTool(id);
        state.tabs = state.tabs.filter((tab) => tab !== id);
        state.activeToolId = state.tabs[0] || null;
        await load();
      } catch (error) {
        state.error = message(error, "Delete failed");
        render();
      }
    } else if (action === "edit-members") {
      state.editingMembers = true;
      state.editApps = new Set(state.groupDetail.apps.map((app) => app.id));
      state.editTools = new Set(state.groupDetail.tools.map((tool) => tool.id));
      render();
    } else if (action === "cancel-members") {
      state.editingMembers = false;
      render();
    } else if (action === "save-members") {
      const members = [
        ...[...state.editApps].map((memberId) => ({ memberType: "APP", memberId })),
        ...[...state.editTools].filter((id) => {
          const tool = state.tools.find((item) => item.id === id);
          return tool && !state.editApps.has(tool.connectionId);
        }).map((memberId) => ({ memberType: "TOOL", memberId })),
      ];
      try {
        await api.setGroupMembers(state.selectedGroupId, members);
        state.editingMembers = false;
        state.notice = "Membership saved";
        state.groupDetail = await api.getGroup(state.selectedGroupId);
        await load();
      } catch (error) {
        state.error = message(error, "Failed to save members");
        render();
      }
    } else if (action === "enable-group" || action === "disable-group") {
      try {
        const result = await (action === "enable-group" ? api.enableGroup(state.selectedGroupId) : api.disableGroup(state.selectedGroupId));
        state.notice = `${result.updated} endpoints ${action === "enable-group" ? "enabled" : "disabled"}`;
        await load();
      } catch (error) {
        state.error = message(error, "Failed to update group");
        render();
      }
    } else if (action === "delete-group") {
      if (!confirm(`Delete group "${state.groupDetail.name}"?`)) return;
      try {
        await api.deleteGroup(state.selectedGroupId);
        await selectGroup(null);
        await load();
      } catch (error) {
        state.error = message(error, "Failed to delete group");
        render();
      }
    } else if (action === "confirm-preview" || action === "reject-preview") {
      try {
        const result = await (action === "confirm-preview" ? api.confirmTool(state.requestPreview.confirmationToken) : api.rejectTool(state.requestPreview.confirmationToken));
        state.requestResult = result.result || null;
        state.requestPreview = null;
        if (!result.result) state.notice = "Request rejected. No external changes were made.";
      } catch (error) {
        state.requestError = message(error, "Workflow action failed");
      }
      render();
    }
  });

  outlet.addEventListener("input", (event) => {
    if (event.target.id === "apps-filter") {
      state.query = event.target.value;
      const cursor = event.target.selectionStart;
      render();
      const input = outlet.querySelector("#apps-filter");
      input.focus();
      input.setSelectionRange(cursor, cursor);
      return;
    }
    const tool = selectedTool();
    if (event.target.dataset.requestValue && tool) {
      requestDraft(tool).values[event.target.dataset.requestValue] = event.target.value;
      schedulePreview();
    } else if (event.target.dataset.requestSetting && tool) {
      requestDraft(tool)[event.target.dataset.requestSetting] = event.target.value;
      schedulePreview();
    } else if (event.target.dataset.kvKind && tool) {
      const row = requestDraft(tool)[event.target.dataset.kvKind][Number(event.target.dataset.kvIndex)];
      if (row) row[event.target.dataset.kvField] = event.target.value;
      schedulePreview();
    } else if (event.target.dataset.manualField) {
      const editing = state.manualEditToolId ? tool : null;
      manualDraft(editing?.connectionId || state.draftConnectionId, editing)[event.target.dataset.manualField] = event.target.value;
    } else if (event.target.dataset.manualParam !== undefined) {
      const editing = state.manualEditToolId ? tool : null;
      const row = manualDraft(editing?.connectionId || state.draftConnectionId, editing).params[Number(event.target.dataset.manualParam)];
      if (row) row[event.target.dataset.paramField] = event.target.value;
    }
  }, { signal: abort.signal });
  outlet.addEventListener("change", (event) => {
    if (event.target.dataset.memberApp) {
      if (event.target.checked) state.editApps.add(event.target.dataset.memberApp);
      else state.editApps.delete(event.target.dataset.memberApp);
      render();
    } else if (event.target.dataset.memberTool) {
      if (event.target.checked) state.editTools.add(event.target.dataset.memberTool);
      else state.editTools.delete(event.target.dataset.memberTool);
    } else {
      const tool = selectedTool();
      if (event.target.dataset.requestValue && tool) {
        requestDraft(tool).values[event.target.dataset.requestValue] = event.target.value;
        schedulePreview();
      } else if (event.target.dataset.requestSetting && tool) {
        const setting = event.target.dataset.requestSetting;
        requestDraft(tool)[setting] = event.target.value;
        if (["bodyMode", "authMode"].includes(setting)) render();
        schedulePreview();
      } else if (event.target.dataset.kvKind && tool) {
        const row = requestDraft(tool)[event.target.dataset.kvKind][Number(event.target.dataset.kvIndex)];
        if (row) row[event.target.dataset.kvField] = event.target.type === "checkbox" ? event.target.checked : event.target.value;
        schedulePreview();
      } else if (event.target.dataset.manualField) {
        const editing = state.manualEditToolId ? tool : null;
        manualDraft(editing?.connectionId || state.draftConnectionId, editing)[event.target.dataset.manualField] = event.target.value;
      } else if (event.target.dataset.manualParam !== undefined) {
        const editing = state.manualEditToolId ? tool : null;
        const row = manualDraft(editing?.connectionId || state.draftConnectionId, editing).params[Number(event.target.dataset.manualParam)];
        if (row) row[event.target.dataset.paramField] = event.target.type === "checkbox" ? event.target.checked : event.target.value;
      }
    }
  }, { signal: abort.signal });
  outlet.addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = event.target;
    try {
      if (form.id === "new-group-form") {
        const data = Object.fromEntries(new FormData(form));
        const group = await api.createGroup(data.name, data.description || undefined);
        state.groupForm = false;
        await load();
        await selectGroup(group.id);
      } else if (form.id === "manual-tool-form") {
        const existing = state.manualEditToolId ? selectedTool() : null;
        const draft = manualDraft(existing?.connectionId || state.draftConnectionId, existing);
        if (!draft.displayName.trim() || !draft.path.trim()) throw new Error("Display name and request path are required");
        const input = {
          connectionId: existing?.connectionId || state.draftConnectionId,
          displayName: draft.displayName.trim(),
          method: draft.method,
          path: draft.path.trim(),
          category: draft.category.trim() || "Manual",
          description: draft.description.trim() || undefined,
          bodyTemplate: draft.bodyTemplate.trim() || undefined,
          params: draft.params.filter((param) => param.name.trim()).map((param) => ({ ...param, name: param.name.trim() })),
        };
        const saved = existing
          ? await api.updateManualTool(existing.id, input)
          : await api.createManualTool(input);
        state.draftConnectionId = "";
        state.manualEditToolId = null;
        state.activeToolId = saved.id;
        if (!state.tabs.includes(saved.id)) state.tabs.push(saved.id);
        state.requestDrafts.delete(saved.id);
        state.manualDrafts.delete(existing ? `edit:${existing.id}` : `new:${input.connectionId}`);
        await load();
      } else if (form.id === "request-form") {
        const tool = selectedTool();
        const draft = requestDraft(tool);
        const result = await api.invokeTool(tool.id, requestArgs(tool, draft), requestOverrides(draft));
        if ("confirmationToken" in result) state.requestPreview = result;
        else state.requestResult = result;
        state.requestError = "";
        render();
      }
    } catch (error) {
      if (form.id === "request-form") state.requestError = message(error, "Request failed");
      else state.error = message(error, "Could not save");
      render();
    }
  }, { signal: abort.signal });

  render();
  await load();
  return () => {
    clearTimeout(state.previewTimer);
    abort.abort();
  };
}
