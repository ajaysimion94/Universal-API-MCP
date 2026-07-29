import { api } from "../api.js";
import {
  banner,
  downloadBlob,
  emptyState,
  escapeAttr,
  escapeHtml,
  icon,
  markdown,
  message,
  on,
} from "../ui.js";

const STORE_KEY = "mcp.search.sessions.v1";
const MAX_SESSIONS = 25;

function freshSession() {
  const now = Date.now();
  return {
    id: crypto.randomUUID(),
    title: "New search",
    createdAt: now,
    updatedAt: now,
    query: "",
    web: false,
    status: "idle",
  };
}

function loadStore() {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORE_KEY));
    if (Array.isArray(parsed?.sessions) && parsed.sessions.length) {
      parsed.sessions.forEach((session) => {
        if (session.status === "loading") {
          session.status = "error";
          session.error = "Search was interrupted. Run it again.";
        }
      });
      return {
        activeId: parsed.sessions.some((session) => session.id === parsed.activeId)
          ? parsed.activeId
          : parsed.sessions[0].id,
        sessions: parsed.sessions,
      };
    }
  } catch {
    // Search remains usable when browser storage is unavailable.
  }
  const session = freshSession();
  return { activeId: session.id, sessions: [session] };
}

function saveStore(store) {
  try {
    const sessions = [...store.sessions]
      .filter((session) => session.query || session.id === store.activeId)
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .slice(0, MAX_SESSIONS);
    localStorage.setItem(STORE_KEY, JSON.stringify({ activeId: store.activeId, sessions }));
  } catch {
    // History is an optional convenience.
  }
}

function titleFromQuery(query) {
  return query.length > 52 ? `${query.slice(0, 52).trimEnd()}…` : query;
}

function sessionTime(timestamp) {
  const date = new Date(timestamp);
  return date.toDateString() === new Date().toDateString()
    ? date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
    : date.toLocaleDateString([], { month: "short", day: "numeric" });
}

function isToolQuery(query) {
  const value = query.trimStart();
  return value.startsWith("#") || value.startsWith("@");
}

function sessionScope(session) {
  return isToolQuery(session.query) ? "App" : session.web ? "Knowledge + web" : "Knowledge";
}

function groupResults(results) {
  const groups = new Map();
  results.forEach((result) => {
    const key = `${result.sourceKind}:${result.sourceName}:${result.sourcePath}:${result.sourceUrl}`;
    const existing = groups.get(key);
    if (existing) {
      existing.chunks.push(result);
      existing.score = Math.max(existing.score, result.score);
    } else {
      groups.set(key, {
        name: result.sourceName,
        path: result.sourcePath,
        url: result.sourceUrl,
        kind: result.sourceKind,
        score: result.score,
        chunks: [result],
      });
    }
  });
  return [...groups.values()].sort((left, right) => right.score - left.score);
}

function fieldInput(name, schema, value = "") {
  const type = schema.type || "string";
  const label = `${escapeHtml(name)}${schema.required ? " *" : ""}`;
  if (schema.enum?.length) {
    return `<label class="tool-field"><span>${label}</span><select class="form-input" name="${escapeAttr(name)}" ${schema.required ? "required" : ""}>
      <option value="">Select…</option>${schema.enum.map((option) => `<option value="${escapeAttr(option)}" ${String(option) === String(value) ? "selected" : ""}>${escapeHtml(option)}</option>`).join("")}
    </select>${schema.description ? `<small>${escapeHtml(schema.description)}</small>` : ""}</label>`;
  }
  if (type === "boolean") {
    return `<label class="tool-field"><span>${label}</span><select class="form-input" name="${escapeAttr(name)}"><option value="">Default</option><option value="true">true</option><option value="false">false</option></select></label>`;
  }
  return `<label class="tool-field"><span>${label}</span><input class="form-input ${["integer", "number"].includes(type) ? "mono" : ""}" name="${escapeAttr(name)}" value="${escapeAttr(value)}" ${schema.required ? "required" : ""} ${["integer", "number"].includes(type) ? 'type="number"' : 'type="text"'}>${schema.description ? `<small>${escapeHtml(schema.description)}</small>` : ""}</label>`;
}

function argsFromForm(form, tool) {
  const data = new FormData(form);
  const properties = tool.paramsSchema?.properties || {};
  const args = {};
  Object.entries(properties).forEach(([name, schema]) => {
    const raw = data.get(name);
    if (raw === "" || raw === null) return;
    if (schema.type === "boolean") args[name] = raw === "true";
    else if (schema.type === "integer") args[name] = Number.parseInt(raw, 10);
    else if (schema.type === "number") args[name] = Number(raw);
    else args[name] = raw;
  });
  return args;
}

function toolForm(response) {
  const tool = response.toolInfo;
  if (!tool) return banner(response.error || "Tool details are unavailable");
  const required = new Set(tool.paramsSchema?.required || []);
  const properties = tool.paramsSchema?.properties || {};
  return `<section class="tool-form-panel" aria-labelledby="tool-form-title">
    <div class="tool-panel-header">
      <div><span class="method-badge mono ${tool.method === "GET" ? "" : "method-write"}">${escapeHtml(tool.method)}</span>
      <h2 id="tool-form-title">${escapeHtml(tool.displayName)}</h2></div>
      <span class="mono tool-name">#${escapeHtml(tool.name)}</span>
    </div>
    ${tool.description ? `<p class="tool-panel-description">${escapeHtml(tool.description)}</p>` : ""}
    ${response.error ? banner(response.error) : ""}
    <form id="tool-run-form" class="tool-form">
      ${Object.entries(properties).map(([name, schema]) => fieldInput(name, { ...schema, required: required.has(name) }, response.prefill?.[name] ?? "")).join("")}
      ${!Object.keys(properties).length ? '<p class="tool-form-empty">This tool takes no arguments.</p>' : ""}
      <div class="form-actions"><button type="submit" class="btn btn-primary">${icon("play", 14)} ${tool.method === "GET" ? "Run tool" : "Review request"}</button></div>
    </form>
  </section>`;
}

function resultPanel(result) {
  if (!result) return "";
  return `<section class="tool-result-panel">
    <header class="tool-result-header">
      <div><span class="status-pill ${result.status >= 200 && result.status < 300 ? "status-active" : "status-error"}">HTTP ${escapeHtml(result.status)}</span>
      <span class="mono">${escapeHtml(result.latencyMs)} ms</span></div>
      <button type="button" class="btn btn-ghost btn-sm" data-action="copy-result">Copy body</button>
    </header>
    <pre class="tool-result-body" id="tool-result-body"><code>${escapeHtml(result.body || "")}</code></pre>
    ${result.truncated ? '<p class="tool-result-note">Response truncated by the server.</p>' : ""}
  </section>`;
}

function confirmPanel(response) {
  const preview = response.preview || {};
  return `<section class="tool-confirm-panel">
    <div class="tool-confirm-heading">${icon("alert", 18)}<div><h2>Confirm write request</h2><p>This action can change external data. Review the exact request before continuing.</p></div></div>
    <div class="tool-preview">
      <div><span class="method-badge mono method-write">${escapeHtml(preview.method)}</span><code>${escapeHtml(preview.url)}</code></div>
      ${preview.body ? `<pre><code>${escapeHtml(preview.body)}</code></pre>` : ""}
    </div>
    <div class="form-actions">
      <button class="btn btn-ghost" type="button" data-action="reject-tool" data-token="${escapeAttr(response.confirmationToken)}">Reject</button>
      <button class="btn btn-primary" type="button" data-action="confirm-tool" data-token="${escapeAttr(response.confirmationToken)}">Confirm and run</button>
    </div>
  </section>`;
}

function responseContent(session) {
  if (session.status === "loading") {
    return `<div class="search-loading" role="status"><span>Searching</span><span class="search-loading-dots">•••</span></div>`;
  }
  if (session.status === "error") return banner(session.error || "Search failed");
  if (session.status !== "success" || !session.response) {
    return `<div class="search-empty">
      <span class="search-empty-mark">${icon("search", 26)}</span>
      <h2>Find the exact evidence</h2>
      <p>Search indexed knowledge, add web sources when available, or invoke an app tool with <code>#</code>.</p>
      <div class="search-examples">
        <button type="button" data-example="deployment rollback procedure">deployment rollback procedure</button>
        <button type="button" data-example="#list_projects">#list_projects</button>
        <button type="button" data-example="@jira #search issues assigned to me">@jira #search issues assigned to me</button>
      </div>
    </div>`;
  }

  const response = session.response;
  if (response.mode === "tool-form") return toolForm(response);
  if (response.mode === "tool-confirm") return confirmPanel(response);
  if (response.mode === "tool-result") return resultPanel(response.result);
  if (response.mode === "notReady") {
    return `<div class="search-not-ready">${icon("alert", 18)}<div><h2>Search needs setup</h2><p>${escapeHtml(response.message || "Install and enable the required plugins.")}</p><a href="/plugins" data-link class="btn btn-primary">Open plugins</a></div></div>`;
  }
  if (response.mode === "empty" || !response.results?.length) {
    const suggestions = response.suggestions || [];
    return emptyState(
      "No matching evidence",
      response.message || "Try a shorter query, another source, or enable web augmentation.",
      suggestions.length ? `<div class="tool-suggestions">${suggestions.map((tool) => `<button type="button" data-example="#${escapeAttr(tool.name)}">#${escapeHtml(tool.name)} · ${escapeHtml(tool.displayName)}</button>`).join("")}</div>` : "",
    );
  }

  const local = response.results.filter((result) => result.sourceKind !== "web");
  const web = response.results.filter((result) => result.sourceKind === "web");
  const groups = groupResults(local);
  return `<div class="search-results">
    <div class="search-result-summary">
      <span><strong>${response.total ?? response.results.length}</strong> matches</span>
      <span class="mono">${response.lexicalOnly ? "lexical index" : "hybrid retrieval"}</span>
      ${response.web ? `<span class="mono">${web.length} web</span>` : ""}
    </div>
    ${response.lexicalMessage ? `<p class="search-inline-notice">${escapeHtml(response.lexicalMessage)}</p>` : ""}
    ${response.webMessage ? `<p class="search-inline-notice">${escapeHtml(response.webMessage)}</p>` : ""}
    ${groups.map((group, index) => `<article class="file-group" data-group="${index}">
      <button class="file-group-header" type="button" data-action="toggle-result" data-id="${index}" aria-expanded="${index === 0}">
        ${icon("chevron", 14, `file-group-chevron ${index === 0 ? "chev-open" : ""}`)}
        ${icon(group.kind === "web" ? "globe" : "file", 15)}
        <span class="file-group-title">${escapeHtml(group.name)}</span>
        <span class="file-group-path mono">${escapeHtml(group.path || "")}</span>
        <span class="file-group-count mono">${group.chunks.length} ${group.chunks.length === 1 ? "match" : "matches"}</span>
      </button>
      <div class="file-group-chunks" ${index === 0 ? "" : "hidden"}>
        ${group.chunks.map((chunk) => `<div class="search-result">
          <div class="search-result-meta"><span class="mono">score ${Number(chunk.score || 0).toFixed(3)}</span>${chunk.sourceUrl ? `<a href="${escapeAttr(chunk.sourceUrl)}" target="_blank" rel="noopener noreferrer">${icon("external", 13)} source</a>` : ""}</div>
          <div class="search-result-excerpt">${markdown(chunk.excerpt || chunk.description || chunk.content || "")}</div>
        </div>`).join("")}
      </div>
    </article>`).join("")}
    ${web.length ? `<section class="web-results"><h2>${icon("globe", 16)} Web evidence</h2>${web.map((item) => `<article class="web-result"><a href="${escapeAttr(item.sourceUrl)}" target="_blank" rel="noopener noreferrer">${escapeHtml(item.sourceName)}</a><p>${escapeHtml(item.excerpt || item.description)}</p></article>`).join("")}</section>` : ""}
  </div>`;
}

export async function mount(outlet, context) {
  const state = {
    store: loadStore(),
    input: "",
    web: false,
    plugins: [],
    tools: [],
    guideOpen: false,
    historyOpen: false,
    exportOpen: false,
    exportFiles: [],
    exportConnections: [],
    exportError: "",
    exportNotice: "",
  };
  const abort = new AbortController();

  const active = () => state.store.sessions.find((session) => session.id === state.store.activeId) || state.store.sessions[0];

  function patchSession(id, patch) {
    state.store.sessions = state.store.sessions.map((session) => session.id === id ? { ...session, ...patch } : session);
    saveStore(state.store);
  }

  function renderAutocomplete() {
    const query = state.input.trimStart();
    if (!query.startsWith("#") && !query.startsWith("@")) return "";
    const fragment = query.split(/\s+/).at(-1).slice(1).toLowerCase();
    if (!fragment && query.length > 1) return "";
    const items = query.startsWith("@")
      ? [...new Map(state.tools.filter((tool) => tool.enabled).map((tool) => [tool.appSlug, tool])).values()]
          .filter((tool) => tool.appSlug.includes(fragment))
          .slice(0, 6)
          .map((tool) => ({ value: `@${tool.appSlug} `, name: tool.appSlug, detail: "app" }))
      : state.tools.filter((tool) => tool.enabled && (tool.name.includes(fragment) || tool.displayName.toLowerCase().includes(fragment)))
          .slice(0, 6)
          .map((tool) => ({ value: `#${tool.name} `, name: tool.name, detail: `${tool.method} · ${tool.displayName}` }));
    if (!items.length) return "";
    return `<div class="search-autocomplete">${items.map((item) => `<button type="button" data-action="accept-suggestion" data-value="${escapeAttr(item.value)}"><span class="mono">${escapeHtml(item.value.trim())}</span><small>${escapeHtml(item.detail)}</small></button>`).join("")}</div>`;
  }

  function renderExportDialog() {
    if (!state.exportOpen) return "";
    const fileRows = state.exportFiles.filter((file) => file.type === "FILE").map((file) => `<label class="summary-source-row"><input type="checkbox" name="fileIds" value="${escapeAttr(file.id)}"><span>${icon("file", 14)}${escapeHtml(file.name)}</span></label>`).join("");
    const connectionRows = state.exportConnections.map((connection) => `<label class="summary-source-row"><input type="checkbox" name="connectionIds" value="${escapeAttr(connection.id)}"><span>${icon("globe", 14)}${escapeHtml(connection.name)}</span></label>`).join("");
    return `<div class="summary-dialog-backdrop" role="presentation">
      <section class="summary-dialog" role="dialog" aria-modal="true" aria-labelledby="summary-title">
        <header><div><h2 id="summary-title">Export evidence</h2><p>Select indexed sources for a plain-text evidence bundle.</p></div><button type="button" class="icon-btn" data-action="close-export" aria-label="Close">${icon("close", 16)}</button></header>
        <form id="export-form">
          ${state.exportError ? banner(state.exportError) : ""}
          <div class="summary-source-columns">
            <div><h3>Files</h3><div class="summary-source-list">${fileRows || "<p>No indexed files.</p>"}</div></div>
            <div><h3>Connections</h3><div class="summary-source-list">${connectionRows || "<p>No connections.</p>"}</div></div>
          </div>
          <div class="form-actions"><button type="button" class="btn btn-ghost" data-action="close-export">Cancel</button><button type="submit" class="btn btn-primary">${icon("download", 14)} Export TXT</button></div>
        </form>
      </section>
    </div>`;
  }

  function render() {
    const session = active();
    const searxngReady = state.plugins.some((plugin) => plugin.id === "searxng" && plugin.status === "ACTIVE");
    const sorted = [...state.store.sessions].sort((a, b) => b.updatedAt - a.updatedAt);
    outlet.innerHTML = `<div class="search-workspace">
      <aside id="search-history" class="search-history-rail ${state.historyOpen ? "is-mobile-open" : ""}" aria-label="Search sessions">
        <div class="search-history-header"><span class="search-history-title">Search sessions</span><button class="btn btn-sm" type="button" data-action="new-search">${icon("plus", 13)} New</button></div>
        <div class="search-history-list">${sorted.map((item) => `<div class="search-history-item ${item.id === session.id ? "active" : ""}">
          <button type="button" class="search-history-item-main" data-action="select-session" data-id="${escapeAttr(item.id)}">
            <span class="search-history-item-title">${escapeHtml(item.title)}</span>
            <span class="search-history-item-meta mono">${item.query ? sessionScope(item) : "Ready"} · ${sessionTime(item.updatedAt)}</span>
          </button>
          <button type="button" class="search-history-item-delete" data-action="delete-session" data-id="${escapeAttr(item.id)}" aria-label="Delete ${escapeAttr(item.title)}">${icon("trash", 13)}</button>
        </div>`).join("")}</div>
        <p class="search-history-note">Results are saved in this browser. Select a session to reuse its query.</p>
      </aside>
      <section class="search-main" aria-labelledby="search-workspace-title">
        <header class="search-workspace-header">
          <button class="btn btn-sm search-history-mobile-toggle" type="button" data-action="toggle-history">${icon("book", 14)} Sessions</button>
          <div class="search-workspace-heading"><h1 id="search-workspace-title">${escapeHtml(session.query ? session.title : "Knowledge search")}</h1><span class="mono">${session.query ? `${sessionScope(session)} · saved ${sessionTime(session.updatedAt)}` : "Search files, connected sources, and apps"}</span></div>
          <div class="search-workspace-actions">
            <button class="btn btn-ghost" type="button" data-action="toggle-guide">${icon("book", 14)} Search guide</button>
            <button class="btn btn-ghost" type="button" data-action="open-export">${icon("download", 14)} Export evidence</button>
          </div>
        </header>
        ${state.guideOpen ? `<section class="search-guide" id="search-guide"><div><h2>Query grammar</h2><p>Plain text searches knowledge. Start with <code>#</code> to call a tool or <code>@app</code> to scope it.</p></div><div class="search-guide-examples"><button data-example="incident response runbook">Knowledge search</button><button data-example="#list_projects">Tool by name</button><button data-example="@jira #search assigned to me">Scoped app tool</button></div></section>` : ""}
        ${state.exportNotice ? banner(state.exportNotice, "status") : ""}
        <div class="search-query-region">
          <form class="search-composer" id="search-form">
            <div class="search-composer-input">${icon(isToolQuery(state.input) ? "hash" : "search", 18)}
              <input id="workspace-search-input" type="search" name="query" value="${escapeAttr(state.input)}" autocomplete="off" placeholder="Search knowledge or type # for a tool" aria-label="Search query">
              ${renderAutocomplete()}
            </div>
            <label class="web-toggle ${searxngReady ? "" : "is-disabled"}"><input type="checkbox" name="web" ${state.web ? "checked" : ""} ${searxngReady ? "" : "disabled"}><span>Web</span></label>
            <button class="btn btn-primary" type="submit" ${session.status === "loading" ? "disabled" : ""}>${icon("search", 14)} ${session.status === "loading" ? "Searching…" : isToolQuery(state.input) ? "Run" : "Search"}</button>
          </form>
          ${!searxngReady ? '<p class="search-composer-note">Web augmentation is off until SearXNG is active.</p>' : ""}
        </div>
        <div class="search-response" id="search-response">${responseContent(session)}</div>
      </section>
      ${renderExportDialog()}
    </div>`;
    outlet.querySelector("#workspace-search-input")?.setSelectionRange(state.input.length, state.input.length);
  }

  async function runSearch(sessionId, query, web) {
    patchSession(sessionId, {
      title: titleFromQuery(query),
      query,
      web,
      status: "loading",
      response: undefined,
      error: undefined,
      updatedAt: Date.now(),
    });
    render();
    try {
      const response = await api.search(query, 20, web);
      patchSession(sessionId, { status: "success", response, updatedAt: Date.now() });
    } catch (error) {
      patchSession(sessionId, { status: "error", error: message(error, "Search failed"), updatedAt: Date.now() });
    }
    render();
  }

  function submitQuery(raw, web = state.web, reuse = false) {
    const query = raw.trim();
    if (!query) return;
    state.input = query;
    let session = active();
    if (!reuse && session.query) {
      session = { ...freshSession(), title: titleFromQuery(query), query, web, status: "loading" };
      state.store.activeId = session.id;
      state.store.sessions.unshift(session);
      saveStore(state.store);
    }
    runSearch(session.id, query, web);
  }

  async function openExport() {
    state.exportOpen = true;
    state.exportError = "";
    render();
    try {
      [state.exportFiles, state.exportConnections] = await Promise.all([
        api.fetchFileTree(),
        api.listConnections(),
      ]);
    } catch (error) {
      state.exportError = message(error, "Failed to load sources");
    }
    render();
  }

  on(outlet, "click", "[data-action], [data-example]", async (_event, target) => {
    if (target.dataset.example) {
      state.input = target.dataset.example;
      state.guideOpen = false;
      render();
      outlet.querySelector("#workspace-search-input")?.focus();
      return;
    }
    const { action, id } = target.dataset;
    if (action === "new-search") {
      const selected = active();
      if (selected.query) {
        const session = freshSession();
        state.store.sessions.unshift(session);
        state.store.activeId = session.id;
      }
      state.input = "";
      render();
    } else if (action === "select-session") {
      state.store.activeId = id;
      state.input = active().query;
      state.web = active().web;
      state.historyOpen = false;
      saveStore(state.store);
      render();
    } else if (action === "delete-session") {
      state.store.sessions = state.store.sessions.filter((session) => session.id !== id);
      if (!state.store.sessions.length) state.store.sessions.push(freshSession());
      if (state.store.activeId === id) state.store.activeId = state.store.sessions[0].id;
      state.input = active().query;
      saveStore(state.store);
      render();
    } else if (action === "toggle-history") {
      state.historyOpen = !state.historyOpen;
      render();
    } else if (action === "toggle-guide") {
      state.guideOpen = !state.guideOpen;
      render();
    } else if (action === "accept-suggestion") {
      state.input = target.dataset.value;
      render();
      outlet.querySelector("#workspace-search-input")?.focus();
    } else if (action === "toggle-result") {
      const group = target.closest(".file-group");
      const content = group.querySelector(".file-group-chunks");
      const chevron = target.querySelector(".file-group-chevron");
      content.hidden = !content.hidden;
      target.setAttribute("aria-expanded", String(!content.hidden));
      chevron.classList.toggle("chev-open", !content.hidden);
    } else if (action === "copy-result") {
      await navigator.clipboard.writeText(outlet.querySelector("#tool-result-body")?.innerText || "");
      target.textContent = "Copied";
    } else if (action === "confirm-tool" || action === "reject-tool") {
      try {
        const result = await (action === "confirm-tool" ? api.confirmTool(target.dataset.token) : api.rejectTool(target.dataset.token));
        const session = active();
        patchSession(session.id, {
          response: {
            ...session.response,
            mode: "tool-result",
            result: result.result,
            message: result.state === "REJECTED" ? "Request rejected. No external changes were made." : undefined,
          },
        });
      } catch (error) {
        patchSession(active().id, { status: "error", error: message(error, "Workflow action failed") });
      }
      render();
    } else if (action === "open-export") {
      await openExport();
    } else if (action === "close-export") {
      state.exportOpen = false;
      render();
    } else if (action === "dismiss-banner") {
      state.exportNotice = "";
      state.exportError = "";
      render();
    }
  });

  outlet.addEventListener("input", (event) => {
    if (event.target.id === "workspace-search-input") {
      state.input = event.target.value;
      const region = event.target.closest(".search-composer-input");
      region.querySelector(".search-autocomplete")?.remove();
      region.insertAdjacentHTML("beforeend", renderAutocomplete());
    }
  }, { signal: abort.signal });
  outlet.addEventListener("change", (event) => {
    if (event.target.name === "web") state.web = event.target.checked;
  }, { signal: abort.signal });
  outlet.addEventListener("submit", async (event) => {
    if (event.target.id === "search-form") {
      event.preventDefault();
      submitQuery(new FormData(event.target).get("query") || "");
    } else if (event.target.id === "tool-run-form") {
      event.preventDefault();
      const session = active();
      const tool = session.response?.toolInfo;
      if (!tool) return;
      try {
        const result = await api.invokeTool(tool.id, argsFromForm(event.target, tool));
        patchSession(session.id, {
          response: "confirmationToken" in result
            ? { ...session.response, mode: "tool-confirm", ...result }
            : { ...session.response, mode: "tool-result", result },
        });
      } catch (error) {
        patchSession(session.id, { response: { ...session.response, error: message(error, "Tool failed") } });
      }
      render();
    } else if (event.target.id === "export-form") {
      event.preventDefault();
      const data = new FormData(event.target);
      const selection = { fileIds: data.getAll("fileIds"), connectionIds: data.getAll("connectionIds") };
      if (!selection.fileIds.length && !selection.connectionIds.length) {
        state.exportError = "Select at least one source.";
        render();
        return;
      }
      try {
        const result = await api.createSummaryExport(selection);
        downloadBlob(result.blob, result.filename);
        state.exportOpen = false;
        state.exportNotice = `${result.filename} — ${result.sourceCount} sources, ${result.chunkCount} chunks`;
      } catch (error) {
        state.exportError = message(error, "Export failed");
      }
      render();
    }
  }, { signal: abort.signal });

  render();
  Promise.all([api.listPlugins(), api.listTools()])
    .then(([plugins, tools]) => {
      state.plugins = plugins;
      state.tools = tools;
      render();
    })
    .catch(() => {});

  const query = context.params.get("q")?.trim();
  if (query) {
    const requestedWeb = context.params.get("web") === "1";
    history.replaceState({}, "", "/");
    state.input = query;
    setTimeout(() => submitQuery(query, requestedWeb, false), 0);
  } else {
    state.input = active().query;
    render();
    setTimeout(() => outlet.querySelector("#workspace-search-input")?.focus(), 0);
  }

  return () => abort.abort();
}
