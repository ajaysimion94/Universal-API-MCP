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
  };
  const abort = new AbortController();

  const apiConnections = () => state.connections.filter((connection) => connection.type === "API_COLLECTION");
  const selectedTool = () => state.tools.find((tool) => tool.id === state.activeToolId);
  const connectionTools = (id) => state.tools.filter((tool) => tool.connectionId === id);
  const appSlug = (id) => connectionTools(id)[0]?.appSlug || "";

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

  function schemaFields(tool) {
    const properties = tool.paramsSchema?.properties || {};
    const required = new Set(tool.paramsSchema?.required || []);
    return Object.entries(properties).map(([name, property]) => `<label class="tool-field"><span>${escapeHtml(name)}${required.has(name) ? " *" : ""}</span>${property.enum?.length
      ? `<select class="form-input" name="${escapeAttr(name)}" ${required.has(name) ? "required" : ""}><option value="">Select…</option>${property.enum.map((value) => `<option value="${escapeAttr(value)}">${escapeHtml(value)}</option>`).join("")}</select>`
      : `<input class="form-input" name="${escapeAttr(name)}" ${required.has(name) ? "required" : ""} ${["integer", "number"].includes(property.type) ? 'type="number"' : 'type="text"'}>`}${property.description ? `<small>${escapeHtml(property.description)}</small>` : ""}</label>`).join("");
  }

  function requestBuilder() {
    const tool = selectedTool();
    if (!tool && !state.draftConnectionId) {
      return emptyState("Choose an endpoint to start a request", "Expand an app in the sidebar, or use its + action to create a custom request.", '<a href="/guide" data-link class="empty-link">Read the app query guide</a>');
    }
    if (!tool) {
      return `<form class="rb-panel" id="manual-tool-form">
        <div class="rb-request-line"><select class="form-input rb-method-select" name="method">${["GET", "POST", "PUT", "PATCH", "DELETE"].map((method) => `<option>${method}</option>`).join("")}</select><input class="form-input" name="path" placeholder="/path/to/resource" required><button class="btn btn-primary" type="submit">Save request</button></div>
        <div class="rb-tabs"><button class="rb-tab is-active" type="button">Request</button></div>
        <div class="rb-tab-content"><div class="form-row"><label class="form-field"><span>Display name</span><input class="form-input" name="displayName" required></label><label class="form-field"><span>Category</span><input class="form-input" name="category" value="Manual"></label></div><label class="form-field"><span>Description</span><textarea class="form-input" name="description"></textarea></label><label class="form-field"><span>JSON body template</span><textarea class="form-input mono" name="bodyTemplate" rows="8"></textarea></label></div>
      </form>`;
    }
    return `<div class="rb-panel">
      <form id="request-form">
        <div class="rb-request-line"><span class="method-badge mono ${tool.method === "GET" ? "" : "method-write"}">${escapeHtml(tool.method)}</span><code class="rb-url-template">${escapeHtml(tool.urlTemplate)}</code><button class="btn btn-primary" type="submit">${icon("play", 14)} ${tool.method === "GET" ? "Send" : "Review"}</button></div>
        <div class="rb-tabs"><button class="rb-tab ${state.requestTab === "params" ? "is-active" : ""}" type="button" data-action="request-tab" data-id="params">Params</button><button class="rb-tab ${state.requestTab === "auth" ? "is-active" : ""}" type="button" data-action="request-tab" data-id="auth">Auth</button><button class="rb-tab ${state.requestTab === "history" ? "is-active" : ""}" type="button" data-action="request-tab" data-id="history">Details</button></div>
        <div class="rb-tab-content">${state.requestTab === "params" ? `<div class="tool-form">${schemaFields(tool) || '<p class="tool-form-empty">This endpoint takes no schema parameters.</p>'}</div>` : state.requestTab === "auth" ? `<p class="rb-hint">Uses ${escapeHtml(tool.authMode || "connection")} authentication. Change persistent auth on the Connections page.</p>` : `<dl class="rb-details"><dt>Tool ID</dt><dd class="mono">${escapeHtml(tool.id)}</dd><dt>Origin</dt><dd>${escapeHtml(tool.origin)}</dd><dt>Category</dt><dd>${escapeHtml(tool.category)}</dd></dl>`}</div>
      </form>
      <div class="rb-footer-actions">${toggle(tool.enabled, tool.enabled ? "Enabled" : "Disabled", "toggle-tool", tool.id)}${tool.origin === "MANUAL" ? `<button class="btn btn-ghost btn-danger" type="button" data-action="delete-tool" data-id="${escapeAttr(tool.id)}">${icon("trash", 13)} Delete</button>` : ""}</div>
      ${state.requestError ? banner(state.requestError) : ""}
      ${state.requestPreview ? `<section class="tool-confirm-panel"><h2>Confirm write request</h2><div class="tool-preview"><span class="method-badge mono method-write">${escapeHtml(state.requestPreview.preview?.method)}</span><code>${escapeHtml(state.requestPreview.preview?.url)}</code>${state.requestPreview.preview?.body ? `<pre><code>${escapeHtml(state.requestPreview.preview.body)}</code></pre>` : ""}</div><div class="form-actions"><button class="btn btn-ghost" type="button" data-action="reject-preview">Reject</button><button class="btn btn-primary" type="button" data-action="confirm-preview">Confirm and send</button></div></section>` : ""}
      ${state.requestResult ? `<section class="tool-result-panel"><header class="tool-result-header"><span class="status-pill ${state.requestResult.status >= 200 && state.requestResult.status < 300 ? "status-active" : "status-error"}">HTTP ${state.requestResult.status}</span><span class="mono">${state.requestResult.latencyMs} ms</span></header><pre class="tool-result-body"><code>${escapeHtml(state.requestResult.body || "")}</code></pre></section>` : ""}
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

  function formArgs(form, tool) {
    const data = new FormData(form);
    const properties = tool.paramsSchema?.properties || {};
    return Object.fromEntries(Object.entries(properties).flatMap(([name, schema]) => {
      const raw = data.get(name);
      if (raw === "" || raw === null) return [];
      if (schema.type === "boolean") return [[name, raw === "true"]];
      if (["integer", "number"].includes(schema.type)) return [[name, Number(raw)]];
      return [[name, raw]];
    }));
  }

  on(outlet, "click", "[data-action]", async (event, target) => {
    const { action, id } = target.dataset;
    if (action === "dismiss-banner") {
      state.error = "";
      state.requestError = "";
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
      state.activeToolId = id;
      if (!state.tabs.includes(id)) state.tabs.push(id);
      state.requestResult = null;
      state.requestPreview = null;
      saveTabs();
      render();
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
    }
  }, { signal: abort.signal });
  outlet.addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = event.target;
    const data = Object.fromEntries(new FormData(form));
    try {
      if (form.id === "new-group-form") {
        const group = await api.createGroup(data.name, data.description || undefined);
        state.groupForm = false;
        await load();
        await selectGroup(group.id);
      } else if (form.id === "manual-tool-form") {
        const tool = await api.createManualTool({
          connectionId: state.draftConnectionId,
          displayName: data.displayName,
          method: data.method,
          path: data.path,
          category: data.category,
          description: data.description,
          bodyTemplate: data.bodyTemplate || undefined,
          params: [],
        });
        state.draftConnectionId = "";
        state.activeToolId = tool.id;
        state.tabs.push(tool.id);
        await load();
      } else if (form.id === "request-form") {
        const tool = selectedTool();
        const result = await api.invokeTool(tool.id, formArgs(form, tool));
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
  return () => abort.abort();
}
