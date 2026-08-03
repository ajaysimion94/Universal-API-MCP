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

const STORE_KEY = "mcp.search.sessions.v2";
const LEGACY_STORE_KEY = "mcp.search.sessions.v1";
const MAX_SESSIONS = 12;
const MAX_TURNS = 20;

function freshSession() {
  const now = Date.now();
  return {
    id: crypto.randomUUID(),
    title: "New session",
    createdAt: now,
    updatedAt: now,
    turns: [],
  };
}

function migrateSession(session) {
  if (Array.isArray(session.turns)) {
    return {
      ...session,
      title: session.title || "New session",
      turns: session.turns.map((turn) => ({
        id: turn.id || crypto.randomUUID(),
        createdAt: turn.createdAt || session.createdAt || Date.now(),
        updatedAt: turn.updatedAt || session.updatedAt || Date.now(),
        ...turn,
      })),
    };
  }
  const turns = session.query
    ? [{
        id: crypto.randomUUID(),
        query: session.query,
        web: Boolean(session.web),
        status: session.status || "success",
        response: session.response,
        error: session.error,
        createdAt: session.createdAt || Date.now(),
        updatedAt: session.updatedAt || Date.now(),
      }]
    : [];
  return {
    id: session.id || crypto.randomUUID(),
    title: session.title || (turns[0] ? titleFromQuery(turns[0].query) : "New session"),
    createdAt: session.createdAt || Date.now(),
    updatedAt: session.updatedAt || Date.now(),
    turns,
  };
}

function loadStore() {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORE_KEY) || localStorage.getItem(LEGACY_STORE_KEY));
    if (Array.isArray(parsed?.sessions) && parsed.sessions.length) {
      const sessions = parsed.sessions.map(migrateSession);
      sessions.forEach((session) => {
        session.turns.forEach((turn) => {
          if (turn.status === "loading") {
            turn.status = "error";
            turn.error = "This request was interrupted. Send it again.";
          }
        });
      });
      return {
        activeId: sessions.some((session) => session.id === parsed.activeId)
          ? parsed.activeId
          : sessions[0].id,
        sessions,
      };
    }
  } catch {
    // Sessions remain usable when browser storage is unavailable.
  }
  const session = freshSession();
  return { activeId: session.id, sessions: [session] };
}

function saveStore(store) {
  try {
    const sessions = [...store.sessions]
      .filter((session) => session.turns?.length || session.id === store.activeId)
      .map((session) => ({ ...session, turns: session.turns.slice(-MAX_TURNS) }))
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .slice(0, MAX_SESSIONS);
    localStorage.setItem(STORE_KEY, JSON.stringify({ activeId: store.activeId, sessions }));
  } catch {
    // Session persistence is an optional convenience when browser storage is full or unavailable.
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
  const turn = session.turns.at(-1);
  if (!turn) return "Ready";
  return isToolQuery(turn.query) ? "App" : turn.web ? "Knowledge + web" : "Knowledge";
}

function turnCountLabel(session) {
  const count = session.turns.length;
  return `${count} ${count === 1 ? "turn" : "turns"}`;
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
    return `<label class="tool-field"><span>${label}<small class="tool-form-type mono">${escapeHtml(type)}</small></span><select class="form-input" name="${escapeAttr(name)}" data-tool-param="${escapeAttr(name)}" ${schema.required ? "required" : ""}>
      <option value="">Select…</option>${schema.enum.map((option) => `<option value="${escapeAttr(option)}" ${String(option) === String(value) ? "selected" : ""}>${escapeHtml(option)}</option>`).join("")}
    </select>${schema.description ? `<small>${escapeHtml(schema.description)}</small>` : ""}</label>`;
  }
  if (type === "boolean") {
    return `<label class="tool-field"><span>${label}<small class="tool-form-type mono">boolean</small></span><select class="form-input" name="${escapeAttr(name)}" data-tool-param="${escapeAttr(name)}"><option value="">Default</option><option value="true" ${String(value) === "true" ? "selected" : ""}>true</option><option value="false" ${String(value) === "false" ? "selected" : ""}>false</option></select></label>`;
  }
  if (["array", "object"].includes(type)) {
    const serialized = typeof value === "string" ? value : value === "" ? "" : JSON.stringify(value, null, 2);
    return `<label class="tool-field"><span>${label}<small class="tool-form-type mono">${escapeHtml(type)}</small></span><textarea class="form-input tool-form-textarea mono" name="${escapeAttr(name)}" data-tool-param="${escapeAttr(name)}" rows="4" ${schema.required ? "required" : ""} placeholder="${type === "array" ? "[…]" : "{…}"}">${escapeHtml(serialized)}</textarea>${schema.description ? `<small>${escapeHtml(schema.description)}</small>` : ""}</label>`;
  }
  return `<label class="tool-field"><span>${label}<small class="tool-form-type mono">${escapeHtml(type)}</small></span><input class="form-input ${["integer", "number"].includes(type) ? "mono" : ""}" name="${escapeAttr(name)}" data-tool-param="${escapeAttr(name)}" value="${escapeAttr(value)}" ${schema.required ? "required" : ""} ${["integer", "number"].includes(type) ? `type="number" step="${type === "integer" ? "1" : "any"}"` : 'type="text"'}>${schema.description ? `<small>${escapeHtml(schema.description)}</small>` : ""}</label>`;
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
    else if (["array", "object"].includes(schema.type)) {
      try {
        args[name] = JSON.parse(raw);
      } catch {
        args[name] = raw;
      }
    }
    else args[name] = raw;
  });
  return args;
}

function defaultToolDraft(response) {
  const tool = response.toolInfo || {};
  const values = {};
  for (const [name, property] of Object.entries(tool.paramsSchema?.properties || {})) {
    const prefilled = response.prefill?.[name];
    if (prefilled !== undefined) values[name] = prefilled;
    else if (property.default !== undefined) values[name] = property.default;
    else values[name] = "";
  }
  return {
    mode: "FORM",
    values,
    rawBody: tool.bodyTemplate || "",
    rawContentType: "application/json",
    preview: null,
    previewError: "",
    previewLoading: false,
  };
}

function requestOverrides(turn) {
  const draft = turn.toolDraft || {};
  if (draft.mode !== "RAW") return { bodyMode: "SCHEMA" };
  return {
    bodyMode: "RAW",
    rawBody: draft.rawBody || "",
    rawContentType: draft.rawContentType || "application/json",
  };
}

function resolvedRequest(preview) {
  if (!preview) return "";
  const headers = Object.entries(preview.headers || {});
  return `<div class="session-request-preview">
    <div class="session-request-line"><span class="method-badge mono ${preview.method === "GET" ? "" : "method-write"}">${escapeHtml(preview.method)}</span><code>${escapeHtml(preview.url)}</code></div>
    ${headers.length ? `<details><summary>${headers.length} request headers</summary><div class="session-kv-list">${headers.map(([name, value]) => `<div><span class="mono">${escapeHtml(name)}</span><code>${escapeHtml(value)}</code></div>`).join("")}</div></details>` : ""}
    ${preview.body !== undefined ? `<pre><code>${escapeHtml(preview.body)}</code></pre>` : ""}
  </div>`;
}

function toolForm(turn) {
  const response = turn.response;
  const tool = response.toolInfo;
  if (!tool) return banner(response.error || "Tool details are unavailable");
  const draft = turn.toolDraft || defaultToolDraft(response);
  const required = new Set(tool.paramsSchema?.required || []);
  const properties = tool.paramsSchema?.properties || {};
  const canHaveBody = !["GET", "HEAD"].includes(tool.method);
  const visibleProperties = draft.mode === "RAW"
    ? Object.fromEntries(Object.entries(properties).filter(([name]) => tool.paramLocations?.[name] !== "body"))
    : properties;
  return `<section class="tool-form-panel conversation-tool-form" aria-labelledby="tool-form-title-${escapeAttr(turn.id)}">
    <div class="tool-panel-header">
      <div><span class="method-badge mono ${tool.method === "GET" ? "" : "method-write"}">${escapeHtml(tool.method)}</span>
      <h2 id="tool-form-title-${escapeAttr(turn.id)}">${escapeHtml(tool.displayName)}</h2></div>
      <span class="mono tool-name">#${escapeHtml(tool.name)}</span>
    </div>
    ${tool.description ? `<p class="tool-panel-description">${escapeHtml(tool.description)}</p>` : ""}
    ${response.error ? banner(response.error) : ""}
    <form id="tool-run-form-${escapeAttr(turn.id)}" class="tool-form" data-tool-run-form data-turn-id="${escapeAttr(turn.id)}">
      ${canHaveBody ? `<div class="session-mode-tabs" role="tablist" aria-label="Request input mode">
        <button class="${draft.mode === "FORM" ? "is-active" : ""}" type="button" role="tab" aria-selected="${draft.mode === "FORM"}" data-action="request-mode" data-turn-id="${escapeAttr(turn.id)}" data-mode="FORM">Form</button>
        <button class="${draft.mode === "RAW" ? "is-active" : ""}" type="button" role="tab" aria-selected="${draft.mode === "RAW"}" data-action="request-mode" data-turn-id="${escapeAttr(turn.id)}" data-mode="RAW">Raw body</button>
      </div>` : ""}
      <div class="session-tool-fields">
        ${Object.entries(visibleProperties).map(([name, schema]) => fieldInput(name, { ...schema, required: required.has(name) && !(draft.mode === "RAW" && tool.paramLocations?.[name] === "body") }, draft.values?.[name] ?? "")).join("")}
        ${!Object.keys(visibleProperties).length ? '<p class="tool-form-empty">This request has no form parameters.</p>' : ""}
      </div>
      ${draft.mode === "RAW" ? `<div class="session-raw-request">
        <label class="tool-field"><span>Content type</span><select class="form-input" data-tool-draft="rawContentType">
          ${["application/json", "application/xml", "text/plain", "application/x-www-form-urlencoded"].map((contentType) => `<option value="${contentType}" ${draft.rawContentType === contentType ? "selected" : ""}>${contentType}</option>`).join("")}
        </select></label>
        <label class="tool-field"><span>Request body</span><textarea class="form-input tool-form-textarea mono" data-tool-draft="rawBody" rows="10" placeholder='{"id": 1, "title": "Updated"}'>${escapeHtml(draft.rawBody || "")}</textarea></label>
      </div>` : ""}
      <div class="form-actions"><button type="button" class="btn btn-ghost" data-action="preview-tool" data-turn-id="${escapeAttr(turn.id)}">Preview request</button><button type="submit" class="btn btn-primary">${icon("play", 14)} ${tool.method === "GET" ? "Run tool" : "Review request"}</button></div>
      ${draft.previewLoading ? '<p class="tool-form-empty">Resolving request…</p>' : ""}
      ${draft.previewError ? banner(draft.previewError) : ""}
      ${draft.preview ? resolvedRequest(draft.preview) : ""}
    </form>
  </section>`;
}

function parseResponseBody(result) {
  const body = result?.body || "";
  const looksJson = result?.contentType?.includes("json") || /^[\s]*[\[{]/.test(body);
  if (!looksJson) return null;
  try {
    return JSON.parse(body);
  } catch {
    return null;
  }
}

// ── non-JSON response previews ──────────────────────────────────────────────
// Every branch below ends in escaped text inside a whitespace-preserving block. A response body is
// third-party content, so it is never rendered as markup — and it is never passed through the
// Markdown renderer either, which joins consecutive lines into a paragraph and so silently
// destroyed the structure of every line-oriented format (CSV rows merged onto one line).

/** RFC 4180-ish: honours quoted fields, escaped "" quotes, and CRLF. Never throws. */
function parseCsv(text) {
  const rows = [];
  let row = [];
  let field = "";
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (quoted) {
      if (char !== '"') field += char;
      else if (text[i + 1] === '"') { field += '"'; i++; }
      else quoted = false;
    } else if (char === '"') {
      quoted = true;
    } else if (char === ",") {
      row.push(field);
      field = "";
    } else if (char === "\n") {
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
    } else if (char !== "\r") {
      field += char;
    }
  }
  if (field.length || row.length) {
    row.push(field);
    rows.push(row);
  }
  return rows.filter((entry) => entry.length > 1 || (entry[0] || "").trim() !== "");
}

function csvPreview(body) {
  const rows = parseCsv(body);
  // A single column is more likely a plain-text body that happens to contain commas than a table.
  if (rows.length < 2 || rows[0].length < 2) return textPreview(body);
  const [header, ...data] = rows;
  return `<div class="session-table-wrap"><table class="session-response-table">
    <thead><tr>${header.map((cell) => `<th>${escapeHtml(cell)}</th>`).join("")}</tr></thead>
    <tbody>${data.slice(0, 100).map((entry) => `<tr>${header.map((_, index) => `<td>${escapeHtml(entry[index] ?? "")}</td>`).join("")}</tr>`).join("")}</tbody>
  </table></div>${data.length > 100 ? `<p class="tool-result-note">Showing 100 of ${data.length} rows.</p>` : ""}`;
}

/** Re-indents by tag depth. Purely cosmetic and tolerant — malformed input still comes back whole. */
function formatXml(source) {
  const compact = String(source).replace(/\r/g, "").replace(/>\s+</g, "><").trim();
  const tokens = compact.match(/<[^>]+>|[^<]+/g) || [];
  const lines = [];
  let depth = 0;
  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i];
    if (!token.trim()) continue;
    const isClose = token.startsWith("</");
    const isMeta = token.startsWith("<?") || token.startsWith("<!");
    const isSelfClosing = token.startsWith("<") && token.endsWith("/>");
    const isOpen = token.startsWith("<") && !isClose && !isMeta && !isSelfClosing;
    // <tag>value</tag> reads better kept on one line than split across three.
    if (isOpen && tokens[i + 1] && !tokens[i + 1].startsWith("<")
        && tokens[i + 2] && tokens[i + 2].startsWith("</")) {
      lines.push("  ".repeat(depth) + token + tokens[i + 1].trim() + tokens[i + 2]);
      i += 2;
      continue;
    }
    if (isClose) depth = Math.max(0, depth - 1);
    lines.push("  ".repeat(depth) + token.trim());
    if (isOpen) depth++;
  }
  return lines.join("\n");
}

function xmlPreview(body) {
  return `<pre class="tool-result-body"><code>${escapeHtml(formatXml(body))}</code></pre>`;
}

/**
 * HTML is shown as escaped source, never rendered: it is untrusted third-party markup, and
 * rendering it would execute whatever the upstream API chose to return inside this page.
 */
function htmlPreview(body) {
  const title = (body.match(/<title[^>]*>([\s\S]*?)<\/title>/i) || [])[1];
  const text = body
    .replace(/<(script|style)[\s\S]*?<\/\1>/gi, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/gi, " ")
    .replace(/\s+/g, " ")
    .trim();
  return `<div class="response-notice">${icon("globe", 14)}<span>HTML document${title ? ` — ${escapeHtml(title.trim())}` : ""}. Showing source; the page is not rendered.</span></div>
    ${text ? `<details class="response-text"><summary>Text content</summary><p>${escapeHtml(text.slice(0, 2000))}${text.length > 2000 ? "…" : ""}</p></details>` : ""}
    <pre class="tool-result-body"><code>${escapeHtml(body)}</code></pre>`;
}

function textPreview(body) {
  return `<pre class="tool-result-body"><code>${escapeHtml(body)}</code></pre>`;
}

/** Chooses a preview by content type, falling back to the body's own shape when the type is absent. */
function responsePreview(result) {
  const parsed = parseResponseBody(result);
  if (parsed !== null) return jsonPreview(parsed);
  const type = String(result?.contentType || "").toLowerCase();
  const body = result?.body || "";
  if (!body.trim()) return '<p class="session-empty-response">The API returned an empty body.</p>';
  if (type.includes("csv") || (!type && /^[^\n,]*,[^\n]*\n/.test(body))) return csvPreview(body);
  if (type.includes("html")) return htmlPreview(body);
  if (type.includes("xml") || /^\s*<\?xml/.test(body)) return xmlPreview(body);
  return textPreview(body);
}

function compactValue(value) {
  if (value === null) return '<span class="session-null">null</span>';
  if (typeof value === "object") return `<code>${escapeHtml(JSON.stringify(value))}</code>`;
  if (typeof value === "boolean") return `<span class="session-boolean">${escapeHtml(value)}</span>`;
  return escapeHtml(value);
}

function jsonPreview(value) {
  if (Array.isArray(value)) {
    if (!value.length) return '<p class="session-empty-response">The API returned an empty list.</p>';
    const objects = value.every((item) => item && typeof item === "object" && !Array.isArray(item));
    if (!objects) return `<ol class="session-value-list">${value.map((item) => `<li>${compactValue(item)}</li>`).join("")}</ol>`;
    const columns = [...new Set(value.flatMap((item) => Object.keys(item)))].slice(0, 8);
    return `<div class="session-table-wrap"><table class="session-response-table"><thead><tr>${columns.map((column) => `<th>${escapeHtml(column)}</th>`).join("")}</tr></thead><tbody>${value.slice(0, 100).map((row) => `<tr>${columns.map((column) => `<td>${compactValue(row[column])}</td>`).join("")}</tr>`).join("")}</tbody></table></div>${value.length > 100 ? `<p class="tool-result-note">Showing 100 of ${value.length} rows.</p>` : ""}`;
  }
  if (value && typeof value === "object") {
    return `<dl class="session-object-preview">${Object.entries(value).map(([name, item]) => `<div><dt>${escapeHtml(name)}</dt><dd>${compactValue(item)}</dd></div>`).join("")}</dl>`;
  }
  return `<p class="session-scalar-preview">${compactValue(value)}</p>`;
}

function resultPanel(turn) {
  const result = turn.response?.result;
  if (!result) return "";
  const view = turn.responseView || "preview";
  const preview = responsePreview(result);
  const headers = Object.entries(result.headers || {});
  return `<section class="tool-result-panel conversation-result-panel">
    <header class="tool-result-header">
      <div><span class="status-pill ${result.status >= 200 && result.status < 300 ? "status-active" : "status-error"}">HTTP ${escapeHtml(result.status)}</span>
      <span class="mono">${escapeHtml(result.latencyMs)} ms</span>${result.contentType ? `<span class="mono session-content-type">${escapeHtml(result.contentType)}</span>` : ""}</div>
      <button type="button" class="btn btn-ghost btn-sm" data-action="copy-result" data-turn-id="${escapeAttr(turn.id)}">Copy body</button>
    </header>
    <div class="session-response-tabs" role="tablist" aria-label="Response view">
      <button type="button" role="tab" class="${view === "preview" ? "is-active" : ""}" aria-selected="${view === "preview"}" data-action="response-view" data-turn-id="${escapeAttr(turn.id)}" data-view="preview">Preview</button>
      <button type="button" role="tab" class="${view === "raw" ? "is-active" : ""}" aria-selected="${view === "raw"}" data-action="response-view" data-turn-id="${escapeAttr(turn.id)}" data-view="raw">Raw response</button>
    </div>
    <div class="session-response-content">${view === "preview" ? preview : `<pre class="tool-result-body"><code>${escapeHtml(result.body || "")}</code></pre>`}</div>
    ${headers.length ? `<details class="rb-response-headers"><summary>${headers.length} response headers</summary><div class="session-kv-list">${headers.map(([name, value]) => `<div><span class="mono">${escapeHtml(name)}</span><code>${escapeHtml(Array.isArray(value) ? value.join(", ") : value)}</code></div>`).join("")}</div></details>` : ""}
    ${result.truncated ? '<p class="tool-result-note">Response truncated by the server.</p>' : ""}
  </section>`;
}

function confirmPanel(turn) {
  const response = turn.response;
  const preview = response.preview || {};
  return `<section class="tool-confirm-panel">
    <div class="tool-confirm-heading">${icon("alert", 18)}<div><h2>Confirm write request</h2><p>This action can change external data. Review the exact request before continuing.</p></div></div>
    ${resolvedRequest(preview)}
    <div class="form-actions">
      <button class="btn btn-ghost" type="button" data-action="reject-tool" data-turn-id="${escapeAttr(turn.id)}" data-token="${escapeAttr(response.confirmationToken)}">Reject</button>
      <button class="btn btn-primary" type="button" data-action="confirm-tool" data-turn-id="${escapeAttr(turn.id)}" data-token="${escapeAttr(response.confirmationToken)}">Confirm and run</button>
    </div>
  </section>`;
}

/**
 * Thumbs for one result. `data-action` only, never `data-example` — the delegated handler returns
 * early on a `data-example` target, so a vote button carrying both would silently do nothing.
 * The current vote lives on the turn, not the DOM, because every state change re-renders the outlet.
 */
function voteControl(chunk, vote) {
  const button = (value, name, label) => `<button type="button" class="vote-btn${vote === value ? " is-on" : ""}"
    data-action="rate-result" data-chunk="${escapeAttr(chunk.id)}" data-rank="${chunk.rank}" data-value="${value}"
    aria-pressed="${vote === value}" aria-label="${label}" title="${label}">${icon(name, 13)}</button>`;
  return `<div class="result-vote">${button(1, "thumbUp", "Helpful")}${button(-1, "thumbDown", "Not helpful")}</div>`;
}

function ragResults(turn) {
  const response = turn.response;
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

  // Rank is stamped before grouping: groupResults() buckets by source, which destroys the flat
  // index that is the true served rank — and rank is what feedback attribution needs.
  const local = response.results
    .filter((result) => result.sourceKind !== "web")
    .map((result, index) => ({ ...result, rank: index + 1 }));
  const web = response.results.filter((result) => result.sourceKind === "web");
  const groups = groupResults(local);
  const impressionId = response.impressionId || "";
  const votes = turn.votes || {};
  return `<div class="search-results">
    <div class="search-result-summary">
      <span><strong>${response.total ?? response.results.length}</strong> matches</span>
      <span class="mono">${response.lexicalOnly ? "lexical index" : "hybrid retrieval"}</span>
      ${response.web ? `<span class="mono">${web.length} web</span>` : ""}
      ${response.learnedAdjustments ? `<span class="mono search-learned" title="Results adjusted from your past feedback">learned ${response.learnedAdjustments}</span>` : ""}
    </div>
    ${response.lexicalMessage ? `<p class="search-inline-notice">${escapeHtml(response.lexicalMessage)}</p>` : ""}
    ${response.webMessage ? `<p class="search-inline-notice">${escapeHtml(response.webMessage)}</p>` : ""}
    <div class="evidence-list">${groups.map((group, index) => `<article class="evidence-source" data-group="${index}">
      <button class="evidence-source-header" type="button" data-action="toggle-result" data-id="${index}" aria-expanded="${index === 0}">
        ${icon("chevron", 14, `file-group-chevron ${index === 0 ? "chev-open" : ""}`)}
        ${icon("file", 15)}
        <span class="evidence-source-name">${escapeHtml(group.name)}</span>
        <span class="evidence-source-path mono">${escapeHtml(group.path || "")}</span>
        <span class="evidence-source-count mono">${group.chunks.length}</span>
      </button>
      <div class="file-group-chunks" ${index === 0 ? "" : "hidden"}>
        ${group.chunks.map((chunk) => `<div class="search-result">
          <div class="search-result-meta"><span class="mono">${Number(chunk.score || 0).toFixed(3)}</span>${chunk.sourceUrl ? `<a href="${escapeAttr(chunk.sourceUrl)}" target="_blank" rel="noopener noreferrer" data-action="open-source" data-chunk="${escapeAttr(chunk.id)}" data-rank="${chunk.rank}">${icon("external", 13)} source</a>` : ""}${impressionId ? voteControl(chunk, votes[chunk.id]) : ""}</div>
          <div class="search-result-excerpt">${markdown(chunk.excerpt || chunk.description || chunk.content || "")}</div>
        </div>`).join("")}
      </div>
    </article>`).join("")}</div>
    ${web.length ? `<section class="web-results"><h2>${icon("globe", 16)} Web evidence</h2>${web.map((item) => `<article class="web-result"><a href="${escapeAttr(item.sourceUrl)}" target="_blank" rel="noopener noreferrer">${escapeHtml(item.sourceName)}</a><p>${escapeHtml(item.excerpt || item.description)}</p></article>`).join("")}</section>` : ""}
  </div>`;
}

// The stored template verbatim, so the table shows what the request actually calls rather than a
// re-derived guess. It keeps any hard-coded query string: the importer turns Postman query params
// into schema parameters, so anything still in the template is genuinely fixed and worth showing.
function toolPath(tool) {
  return String(tool.urlTemplate || "") || "/";
}

function toolParams(tool) {
  const properties = tool.paramsSchema?.properties || {};
  const required = new Set(tool.paramsSchema?.required || []);
  return Object.keys(properties).map((name) => (required.has(name) ? `${name}*` : name));
}

function toolState(tool) {
  if (tool.pending) return { label: "pending", kind: "pending" };
  return tool.enabled ? { label: "enabled", kind: "ok" } : { label: "disabled", kind: "off" };
}

/**
 * A bare "@app" browse: every read request the app exposes, as a table. Writes are listed
 * separately and never made click-to-run, since running one is the approval-gated path.
 */
function toolCatalog(response) {
  const tools = response.tools || [];
  const reads = tools.filter((tool) => String(tool.method || "").toUpperCase() === "GET");
  const writes = tools.filter((tool) => String(tool.method || "").toUpperCase() !== "GET");
  if (!tools.length) {
    return emptyState("No requests", `Nothing is exposed under @${response.scope || ""} yet.`);
  }
  const row = (tool) => {
    const params = toolParams(tool);
    const state = toolState(tool);
    return `<tr>
      <td><button type="button" class="catalog-run" data-example="@${escapeAttr(response.scope)} #${escapeAttr(tool.name)}" title="Put this request in the composer">${escapeHtml(tool.displayName || tool.name)}</button><div class="catalog-keyword mono">#${escapeHtml(tool.name)}</div></td>
      <td class="mono">${escapeHtml(toolPath(tool))}</td>
      <td class="mono">${params.length ? escapeHtml(params.join(", ")) : "—"}</td>
      <td><span class="catalog-state is-${state.kind}">${state.label}</span></td>
    </tr>`;
  };
  return `<section class="catalog">
    <header class="catalog-header">
      <div>
        <p class="catalog-kicker">${response.scopeKind === "group" ? "Group" : "App"}</p>
        <h2>${escapeHtml(response.scopeName || response.scope || "")}</h2>
      </div>
      <span class="mono">${reads.length} GET · ${writes.length} write</span>
    </header>
    ${reads.length ? `<div class="catalog-scroll"><table class="catalog-table">
      <thead><tr><th>Request</th><th>Path</th><th>Parameters</th><th>Status</th></tr></thead>
      <tbody>${reads.map(row).join("")}</tbody>
    </table></div>` : '<p class="catalog-note">This app exposes no GET requests.</p>'}
    ${writes.length ? `<details class="catalog-writes"><summary>${writes.length} state-changing request${writes.length === 1 ? "" : "s"}</summary>
      <div class="catalog-scroll"><table class="catalog-table">
        <thead><tr><th>Request</th><th>Method</th><th>Path</th><th>Status</th></tr></thead>
        <tbody>${writes.map((tool) => {
          const state = toolState(tool);
          return `<tr><td>${escapeHtml(tool.displayName || tool.name)}<div class="catalog-keyword mono">#${escapeHtml(tool.name)}</div></td><td class="mono">${escapeHtml(String(tool.method || "").toUpperCase())}</td><td class="mono">${escapeHtml(toolPath(tool))}</td><td><span class="catalog-state is-${state.kind}">${state.label}</span></td></tr>`;
        }).join("")}</tbody>
      </table></div>
    </details>` : ""}
  </section>`;
}

function turnContent(turn) {
  if (turn.status === "loading") {
    return `<div class="search-loading" role="status"><span>Searching</span><span class="search-loading-dots">•••</span></div>`;
  }
  if (turn.status === "error") return banner(turn.error || "Request failed");
  if (turn.status !== "success" || !turn.response) return "";

  const response = turn.response;
  if (response.mode === "tool-catalog") return toolCatalog(response);
  if (response.mode === "tool-form") return toolForm(turn);
  if (response.mode === "tool-confirm") return confirmPanel(turn);
  if (response.mode === "tool-result") return `${response.message ? banner(response.message, "status") : ""}${resultPanel(turn)}`;
  return ragResults(turn);
}

function conversationContent(session) {
  if (!session.turns.length) {
    return `<div class="search-empty">
      <span class="search-empty-mark">${icon("search", 26)}</span>
      <h2>Start a working session</h2>
      <p>Search evidence, inspect an API response, then send the next related request without losing the earlier result.</p>
      <div class="search-examples">
        <button type="button" data-example="deployment rollback procedure">deployment rollback procedure</button>
        <button type="button" data-example="#list_todos">#list_todos</button>
        <button type="button" data-example="@jira #search issues assigned to me">@jira #search issues assigned to me</button>
      </div>
    </div>`;
  }
  return `<div class="session-transcript">${session.turns.map((turn, index) => `<article class="session-turn" data-turn-id="${escapeAttr(turn.id)}">
    <div class="session-turn-gutter"><span class="session-turn-number mono">${String(index + 1).padStart(2, "0")}</span></div>
    <div class="session-turn-content">
      <header class="session-user-turn"><span class="session-speaker">You</span><p>${escapeHtml(turn.query)}</p><time class="mono">${sessionTime(turn.createdAt)}</time></header>
      <div class="session-assistant-turn"><span class="session-speaker">${isToolQuery(turn.query) ? "App" : "Search"}</span><div class="session-turn-response">${turnContent(turn)}</div></div>
    </div>
  </article>`).join("")}</div>`;
}

export async function mount(outlet, context) {
  const state = {
    store: loadStore(),
    input: "",
    web: false,
    plugins: [],
    tools: [],
    groups: [],
    groupTools: {},
    autocompleteIndex: -1,
    guideOpen: false,
    historyOpen: false,
    exportOpen: false,
    exportFiles: [],
    exportConnections: [],
    exportError: "",
    exportNotice: "",
    stickToBottom: false,
  };
  const abort = new AbortController();

  const active = () => state.store.sessions.find((session) => session.id === state.store.activeId) || state.store.sessions[0];
  const turnById = (session, turnId) => session.turns.find((turn) => turn.id === turnId);

  function patchSession(id, patch) {
    state.store.sessions = state.store.sessions.map((session) => session.id === id ? { ...session, ...patch } : session);
    saveStore(state.store);
  }

  function patchTurn(sessionId, turnId, patch) {
    const session = state.store.sessions.find((item) => item.id === sessionId);
    if (!session) return;
    session.turns = session.turns.map((turn) => turn.id === turnId
      ? { ...turn, ...patch, updatedAt: Date.now() }
      : turn);
    session.updatedAt = Date.now();
    saveStore(state.store);
  }

  function updateTurnDraft(turnId, patch) {
    const session = active();
    const turn = turnById(session, turnId);
    if (!turn) return;
    turn.toolDraft = { ...(turn.toolDraft || defaultToolDraft(turn.response || {})), ...patch };
    session.updatedAt = Date.now();
    saveStore(state.store);
  }

  function autocompleteModel() {
    const query = state.input.trimStart();
    const scopeMatch = query.match(/^@([^\s#]*)$/);
    if (scopeMatch) {
      const fragment = scopeMatch[1].toLowerCase();
      const apps = [...new Set(state.tools.map((tool) => tool.appSlug).filter(Boolean))];
      const appSlugs = new Set(apps);
      const items = [
        ...apps.map((slug) => ({
          value: `@${slug} #`,
          name: `@${slug}`,
          search: slug,
          detail: "app",
        })),
        ...state.groups
          .filter((group) => !appSlugs.has(group.slug))
          .map((group) => ({
            value: `@${group.slug} #`,
            name: `@${group.slug}`,
            search: `${group.slug} ${group.name}`,
            detail: "group",
          })),
      ]
        .filter((item) => item.search.toLowerCase().includes(fragment))
        .sort((a, b) => a.name.localeCompare(b.name));
      return {
        label: "Apps and groups",
        empty: fragment ? `No app or group matches “${scopeMatch[1]}”.` : "No apps or groups are available yet.",
        items,
      };
    }

    const scopedToolMatch = query.match(/^@([^\s#]+)\s+#([^\s]*)$/);
    const globalToolMatch = query.match(/^#([^\s]*)$/);
    if (!scopedToolMatch && !globalToolMatch) return null;

    const scope = scopedToolMatch?.[1]?.toLowerCase();
    const fragment = (scopedToolMatch?.[2] ?? globalToolMatch?.[1] ?? "").toLowerCase();
    const appExists = scope && state.tools.some((tool) => tool.appSlug === scope);
    const pool = scope
      ? appExists
        ? state.tools.filter((tool) => tool.appSlug === scope)
        : state.groupTools[scope] || []
      : state.tools;
    const items = pool
      .filter((tool) => {
        const searchable = `${tool.name} ${tool.requestSlug || ""} ${tool.displayName || ""}`.toLowerCase();
        return searchable.includes(fragment);
      })
      .sort((a, b) => (a.displayName || a.name).localeCompare(b.displayName || b.name))
      .map((tool) => {
        const keyword = scope ? (tool.requestSlug || tool.name) : tool.name;
        return {
          value: scope ? `@${scope} #${keyword} ` : `#${keyword} `,
          name: `#${keyword}`,
          detail: `${tool.method} · ${tool.displayName}${tool.enabled ? "" : " · disabled"}`,
          disabled: !tool.enabled,
        };
      });
    return {
      label: scope ? `Requests in @${scope}` : "All requests",
      empty: fragment
        ? `No request matches “${fragment}”${scope ? ` in @${scope}` : ""}.`
        : scope ? `No requests are available in @${scope}.` : "No API requests are available yet.",
      items,
    };
  }

  function renderAutocomplete() {
    const model = autocompleteModel();
    if (!model) return "";
    const content = model.items.length
      ? model.items.map((item, index) => `<button type="button" role="option" aria-selected="${index === state.autocompleteIndex}" class="${index === state.autocompleteIndex ? "is-active" : ""}" data-action="accept-suggestion" data-value="${escapeAttr(item.value)}" data-autocomplete-index="${index}">
          <span class="mono">${escapeHtml(item.name)}</span>
          <small>${escapeHtml(item.detail)}</small>
        </button>`).join("")
      : `<p class="search-autocomplete-empty">${escapeHtml(model.empty)}</p>`;
    return `<div class="search-autocomplete" id="search-autocomplete" role="listbox" aria-label="${escapeAttr(model.label)}">
      <div class="search-autocomplete-label">${escapeHtml(model.label)}</div>
      <div class="search-autocomplete-options">${content}</div>
    </div>`;
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
    const previousTranscript = outlet.querySelector("#session-transcript-scroll");
    const previousScroll = previousTranscript?.scrollTop || 0;
    const session = active();
    const latestTurn = session.turns.at(-1);
    const busy = latestTurn?.status === "loading";
    const searxngReady = state.plugins.some((plugin) => plugin.id === "searxng" && plugin.status === "ACTIVE");
    const sorted = [...state.store.sessions].sort((a, b) => b.updatedAt - a.updatedAt);
    outlet.innerHTML = `<div class="search-workspace">
      <aside id="search-history" class="search-history-rail ${state.historyOpen ? "is-mobile-open" : ""}" aria-label="Search sessions">
        <div class="search-history-header"><span class="search-history-title">Sessions</span><button class="btn btn-sm" type="button" data-action="new-search">${icon("plus", 13)} New</button></div>
        <div class="search-history-list">${sorted.map((item) => `<div class="search-history-item ${item.id === session.id ? "active" : ""}">
          <button type="button" class="search-history-item-main" data-action="select-session" data-id="${escapeAttr(item.id)}">
            <span class="search-history-item-title">${escapeHtml(item.title)}</span>
            <span class="search-history-item-meta mono">${turnCountLabel(item)} · ${sessionScope(item)} · ${sessionTime(item.updatedAt)}</span>
          </button>
          <button type="button" class="search-history-item-delete" data-action="delete-session" data-id="${escapeAttr(item.id)}" aria-label="Delete ${escapeAttr(item.title)}">${icon("trash", 13)}</button>
        </div>`).join("")}</div>
        <p class="search-history-note">Every request and response stays in its session so related work can continue as one flow.</p>
      </aside>
      <section class="search-main" aria-labelledby="search-workspace-title">
        <header class="search-workspace-header">
          <button class="btn btn-sm search-history-mobile-toggle" type="button" data-action="toggle-history">${icon("book", 14)} Sessions</button>
          <div class="search-workspace-heading"><h1 id="search-workspace-title">${escapeHtml(session.title)}</h1><span class="mono">${session.turns.length ? `${turnCountLabel(session)} · updated ${sessionTime(session.updatedAt)}` : "Search, inspect, then continue with the next request"}</span></div>
          <div class="search-workspace-actions">
            <button class="btn btn-ghost" type="button" data-action="toggle-guide">${icon("book", 14)} Search guide</button>
            <button class="btn btn-ghost" type="button" data-action="open-export">${icon("download", 14)} Export evidence</button>
          </div>
        </header>
        ${state.guideOpen ? `<section class="search-guide" id="search-guide"><div><h2>Query grammar</h2><p>Plain text searches knowledge. Start with <code>#</code> to call a tool or <code>@app</code> to scope it.</p></div><div class="search-guide-examples"><button data-example="incident response runbook">Knowledge search</button><button data-example="#list_projects">Tool by name</button><button data-example="@jira #search assigned to me">Scoped app tool</button></div></section>` : ""}
        ${state.exportNotice ? banner(state.exportNotice, "status") : ""}
        <div class="search-response session-scroll" id="session-transcript-scroll">${conversationContent(session)}</div>
        <div class="search-query-region session-composer-region">
          <form class="search-composer" id="search-form">
          <div class="search-composer-input">${icon(isToolQuery(state.input) ? "hash" : "search", 18)}
              <input id="workspace-search-input" type="search" name="query" value="${escapeAttr(state.input)}" autocomplete="off" placeholder="${session.turns.length ? "Continue this session…" : "Search knowledge or type # for a tool"}" aria-label="Next request" role="combobox" aria-autocomplete="list" aria-controls="search-autocomplete" aria-expanded="${Boolean(autocompleteModel())}">
              ${renderAutocomplete()}
            </div>
            <label class="web-toggle ${searxngReady ? "" : "is-disabled"}"><input type="checkbox" name="web" ${state.web ? "checked" : ""} ${searxngReady ? "" : "disabled"}><span>Web</span></label>
            <button class="btn btn-primary" type="submit" ${busy ? "disabled" : ""}>${icon("play", 14)} ${busy ? "Working…" : "Send"}</button>
          </form>
          <p class="search-composer-note">${!searxngReady ? "Web augmentation is off until SearXNG is active. " : ""}Enter sends the next turn in this session.</p>
        </div>
      </section>
      ${renderExportDialog()}
    </div>`;
    outlet.querySelector("#workspace-search-input")?.setSelectionRange(state.input.length, state.input.length);
    const transcript = outlet.querySelector("#session-transcript-scroll");
    if (transcript) {
      transcript.scrollTop = state.stickToBottom ? transcript.scrollHeight : previousScroll;
      state.stickToBottom = false;
    }
  }

  async function runSearch(sessionId, turnId, query, web) {
    patchTurn(sessionId, turnId, { status: "loading", response: undefined, error: undefined });
    state.stickToBottom = true;
    render();
    try {
      const response = await api.search(query, 20, web);
      patchTurn(sessionId, turnId, {
        status: "success",
        response,
        toolDraft: response.mode === "tool-form" ? defaultToolDraft(response) : undefined,
      });
    } catch (error) {
      patchTurn(sessionId, turnId, { status: "error", error: message(error, "Request failed") });
    }
    state.stickToBottom = true;
    render();
  }

  function submitQuery(raw, web = state.web) {
    const query = raw.trim();
    if (!query) return;
    const session = active();
    const now = Date.now();
    const turn = {
      id: crypto.randomUUID(),
      query,
      web,
      status: "loading",
      createdAt: now,
      updatedAt: now,
    };
    session.turns.push(turn);
    if (session.turns.length === 1) session.title = titleFromQuery(query);
    session.updatedAt = now;
    state.input = "";
    state.stickToBottom = true;
    saveStore(state.store);
    runSearch(session.id, turn.id, query, web);
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

  function toolRequest(turn, form) {
    const tool = turn.response?.toolInfo;
    return {
      tool,
      args: tool ? argsFromForm(form, tool) : {},
      overrides: requestOverrides(turn),
    };
  }

  // ── Search feedback ────────────────────────────────────────────────────────────────────────
  // Implicit signals are deduped per session load: the server's unique key already collapses
  // repeats, but re-sending on every toggle would burn requests for nothing.
  const sentSignals = new Set();

  const numberOr = (value, fallback) => (Number.isFinite(Number(value)) ? Number(value) : fallback);

  /** The impression that produced the results the target sits inside, or "" if learning is off. */
  function impressionFor(target) {
    const turn = turnById(active(), target.closest(".session-turn")?.dataset.turnId);
    return turn?.response?.impressionId || "";
  }

  /** Fire-and-forget: feedback must never interrupt what the user was doing. */
  function sendSignals(target, events) {
    const impressionId = impressionFor(target);
    if (!impressionId || !events.length) return;
    const fresh = events.filter((event) => {
      const key = `${impressionId}:${event.chunkId}:${event.signal}`;
      if (sentSignals.has(key)) return false;
      sentSignals.add(key);
      return true;
    });
    if (!fresh.length) return;
    api.sendFeedback(impressionId, fresh).catch(() => {});
  }

  function sendExpandSignals(target, content) {
    const events = [...content.querySelectorAll("[data-action='rate-result'][data-value='1']")]
      .map((button) => ({
        chunkId: button.dataset.chunk,
        rank: numberOr(button.dataset.rank, 0),
        signal: "EXPAND",
      }));
    sendSignals(target, events);
  }

  /**
   * Clicking the lit thumb clears the vote (value 0), which the server stores as a neutral RATING
   * so the earlier vote is retracted rather than silently kept.
   */
  function rateResult(target) {
    const session = active();
    const turnId = target.closest(".session-turn")?.dataset.turnId;
    const turn = turnById(session, turnId);
    if (!turn) return;
    const chunkId = target.dataset.chunk;
    const clicked = numberOr(target.dataset.value, 0);
    const votes = { ...(turn.votes || {}) };
    const next = votes[chunkId] === clicked ? 0 : clicked;
    if (next === 0) delete votes[chunkId];
    else votes[chunkId] = next;

    patchTurn(session.id, turnId, { votes });
    render();

    const impressionId = turn.response?.impressionId;
    if (!impressionId) return;
    // Ratings bypass the dedupe set: flipping and clearing a vote must always reach the server.
    api.sendFeedback(impressionId, [{
      chunkId,
      rank: numberOr(target.dataset.rank, 0),
      signal: "RATING",
      value: next,
    }]).catch(() => {});
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
      if (active().turns.length) {
        const session = freshSession();
        state.store.sessions.unshift(session);
        state.store.activeId = session.id;
      }
      state.input = "";
      state.web = false;
      saveStore(state.store);
      render();
    } else if (action === "select-session") {
      state.store.activeId = id;
      state.input = "";
      state.web = active().turns.at(-1)?.web || false;
      state.historyOpen = false;
      saveStore(state.store);
      state.stickToBottom = true;
      render();
    } else if (action === "delete-session") {
      state.store.sessions = state.store.sessions.filter((session) => session.id !== id);
      if (!state.store.sessions.length) state.store.sessions.push(freshSession());
      if (state.store.activeId === id) state.store.activeId = state.store.sessions[0].id;
      state.input = "";
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
      state.autocompleteIndex = -1;
      render();
      outlet.querySelector("#workspace-search-input")?.focus();
    } else if (action === "toggle-result") {
      const group = target.closest(".evidence-source");
      const content = group.querySelector(".file-group-chunks");
      const chevron = target.querySelector(".file-group-chevron");
      content.hidden = !content.hidden;
      target.setAttribute("aria-expanded", String(!content.hidden));
      chevron.classList.toggle("chev-open", !content.hidden);
      // Only an opening toggle is a signal, and only a real one: the first group renders
      // pre-expanded, so counting it would credit every search with an expand nobody performed.
      if (!content.hidden) sendExpandSignals(target, content);
    } else if (action === "rate-result") {
      rateResult(target);
    } else if (action === "open-source") {
      // No preventDefault — the link must still open. The signal is best-effort alongside it.
      sendSignals(target, [{ chunkId: target.dataset.chunk, rank: numberOr(target.dataset.rank, 0), signal: "OPEN" }]);
    } else if (action === "copy-result") {
      const turn = turnById(active(), target.dataset.turnId);
      await navigator.clipboard.writeText(turn?.response?.result?.body || "");
      target.textContent = "Copied";
      // Query-level signal: a tool response has no chunk to attribute the copy to.
      sendSignals(target, [{ chunkId: "", rank: 0, signal: "COPY" }]);
    } else if (action === "response-view") {
      patchTurn(active().id, target.dataset.turnId, { responseView: target.dataset.view });
      render();
    } else if (action === "request-mode") {
      updateTurnDraft(target.dataset.turnId, { mode: target.dataset.mode, preview: null, previewError: "" });
      render();
    } else if (action === "preview-tool") {
      const session = active();
      const turn = turnById(session, target.dataset.turnId);
      const form = target.closest("form");
      if (!turn || !form) return;
      const request = toolRequest(turn, form);
      updateTurnDraft(turn.id, { previewLoading: true, previewError: "", preview: null });
      render();
      try {
        const preview = await api.previewTool(request.tool.id, request.args, request.overrides);
        updateTurnDraft(turn.id, { previewLoading: false, preview });
      } catch (error) {
        updateTurnDraft(turn.id, { previewLoading: false, previewError: message(error, "Could not preview request") });
      }
      render();
    } else if (action === "confirm-tool" || action === "reject-tool") {
      const session = active();
      const turn = turnById(session, target.dataset.turnId);
      if (!turn) return;
      try {
        const result = await (action === "confirm-tool" ? api.confirmTool(target.dataset.token) : api.rejectTool(target.dataset.token));
        patchTurn(session.id, turn.id, {
          response: {
            ...turn.response,
            mode: "tool-result",
            result: result.result,
            message: result.state === "REJECTED" ? "Request rejected. No external changes were made." : undefined,
          },
        });
      } catch (error) {
        patchTurn(session.id, turn.id, { status: "error", error: message(error, "Workflow action failed") });
      }
      state.stickToBottom = true;
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
      state.autocompleteIndex = -1;
      const region = event.target.closest(".search-composer-input");
      region.querySelector(".search-autocomplete")?.remove();
      region.insertAdjacentHTML("beforeend", renderAutocomplete());
    } else if (event.target.dataset.toolParam) {
      const turnId = event.target.closest("[data-tool-run-form]")?.dataset.turnId;
      const turn = turnById(active(), turnId);
      if (!turn) return;
      const values = { ...(turn.toolDraft?.values || {}) };
      values[event.target.dataset.toolParam] = event.target.value;
      updateTurnDraft(turnId, { values, preview: null, previewError: "" });
    } else if (event.target.dataset.toolDraft) {
      const turnId = event.target.closest("[data-tool-run-form]")?.dataset.turnId;
      if (turnId) updateTurnDraft(turnId, { [event.target.dataset.toolDraft]: event.target.value, preview: null, previewError: "" });
    }
  }, { signal: abort.signal });
  outlet.addEventListener("keydown", (event) => {
    if (event.target.id !== "workspace-search-input") return;
    if (event.key === "Escape") {
      state.autocompleteIndex = -1;
      outlet.querySelector("#search-autocomplete")?.remove();
      event.target.setAttribute("aria-expanded", "false");
      return;
    }
    const options = [...outlet.querySelectorAll("#search-autocomplete [data-autocomplete-index]")];
    if (!options.length) return;
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const direction = event.key === "ArrowDown" ? 1 : -1;
      state.autocompleteIndex = state.autocompleteIndex < 0
        ? (direction > 0 ? 0 : options.length - 1)
        : (state.autocompleteIndex + direction + options.length) % options.length;
      options.forEach((option, index) => {
        const selected = index === state.autocompleteIndex;
        option.classList.toggle("is-active", selected);
        option.setAttribute("aria-selected", String(selected));
        if (selected) option.scrollIntoView({ block: "nearest" });
      });
    } else if (event.key === "Enter" && state.autocompleteIndex >= 0) {
      event.preventDefault();
      options[state.autocompleteIndex]?.click();
    }
  }, { signal: abort.signal });
  outlet.addEventListener("change", (event) => {
    if (event.target.name === "web") {
      state.web = event.target.checked;
    } else if (event.target.dataset.toolParam || event.target.dataset.toolDraft) {
      event.target.dispatchEvent(new Event("input", { bubbles: true }));
    }
  }, { signal: abort.signal });
  outlet.addEventListener("submit", async (event) => {
    if (event.target.id === "search-form") {
      event.preventDefault();
      submitQuery(new FormData(event.target).get("query") || "");
    } else if (event.target.matches("[data-tool-run-form]")) {
      event.preventDefault();
      const session = active();
      const turn = turnById(session, event.target.dataset.turnId);
      const request = turn ? toolRequest(turn, event.target) : null;
      const tool = request?.tool;
      if (!tool) return;
      try {
        const result = await api.invokeTool(tool.id, request.args, request.overrides);
        patchTurn(session.id, turn.id, {
          response: "confirmationToken" in result
            ? { ...turn.response, mode: "tool-confirm", ...result }
            : { ...turn.response, mode: "tool-result", result },
          responseView: "preview",
        });
      } catch (error) {
        patchTurn(session.id, turn.id, { response: { ...turn.response, error: message(error, "Tool failed") } });
      }
      state.stickToBottom = true;
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
  Promise.all([api.listPlugins(), api.listTools(), api.listGroups()])
    .then(async ([plugins, tools, groups]) => {
      state.plugins = plugins;
      state.tools = tools;
      state.groups = groups;
      const details = await Promise.allSettled(groups.map((group) => api.getGroup(group.id)));
      state.groupTools = Object.fromEntries(details.flatMap((result) =>
        result.status === "fulfilled" ? [[result.value.slug, result.value.tools || []]] : []));
      render();
    })
    .catch(() => {});

  const query = context.params.get("q")?.trim();
  if (query) {
    const requestedWeb = context.params.get("web") === "1";
    history.replaceState({}, "", "/");
    state.input = query;
    setTimeout(() => submitQuery(query, requestedWeb), 0);
  } else {
    state.input = "";
    state.stickToBottom = true;
    render();
    setTimeout(() => outlet.querySelector("#workspace-search-input")?.focus(), 0);
  }

  return () => abort.abort();
}
