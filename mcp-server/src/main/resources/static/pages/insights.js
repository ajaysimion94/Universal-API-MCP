import { api } from "../api.js";
import { banner, escapeAttr, escapeHtml, formatDate, icon, markdown, message, on } from "../ui.js";

const EXAMPLE = `---
title: API activity
params:
  minUser: { type: number, default: 1 }
---

# API activity

\`\`\`rql
let posts = request "List all posts"
  |> where userId >= $minUser;

let by_user = posts
  |> group by userId agg count(*) as posts
  |> order by posts desc;
\`\`\`

<KpiRow>
  <Stat value={count(posts)} label="Posts" />
  <Stat value={count(by_user)} label="Active users" />
</KpiRow>

<BarChart data={by_user} x="userId" y="posts" title="Posts per user" />
<DataTable data={posts} columns={["id", "userId", "title"]} />
`;

// ── expression language ─────────────────────────────────────────────────────
// The small value-expression language documented in docs/query-language-reference.md
// §6.3 ("Value expressions"): count/sum/avg/min/max, dataset[.field] lookups, string/number/
// boolean literals, "+" concatenation, and nestable if/then/else with and/or/parentheses.
// Comparisons compare numerically first, then case-insensitively — the same rule RqlValues.compare
// uses server-side, so a Stat/Text/QuickTable condition reads the same way the query engine would
// evaluate it.

const AGGREGATE_FUNCTIONS = new Set(["count", "sum", "avg", "min", "max"]);
const COMPARATORS = new Set(["=", "==", "!=", "<>", ">", ">=", "<", "<="]);

function tokenizeExpr(source) {
  const pattern = /\s*(?:(>=|<=|==|!=|<>|=|>|<|\+|\(|\)|,)|("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|([0-9]+(?:\.[0-9]+)?)|([A-Za-z_][\w.-]*))/g;
  const tokens = [];
  let index = 0;
  while (index < source.length) {
    pattern.lastIndex = index;
    const match = pattern.exec(source);
    if (!match || match.index !== index) break;
    index = pattern.lastIndex;
    if (match[1]) tokens.push({ type: "op", value: match[1] });
    else if (match[2]) tokens.push({ type: "string", value: match[2].slice(1, -1).replace(/\\(.)/g, "$1") });
    else if (match[3]) tokens.push({ type: "number", value: Number(match[3]) });
    else if (match[4]) tokens.push({ type: "word", value: match[4] });
  }
  return tokens;
}

// avg() is tagged rather than pre-rounded so a comparison ("if avg(x) > 50") still uses the exact
// mean, while display/concatenation formats it to one decimal as documented.
function isAvg(value) {
  return value !== null && typeof value === "object" && value.__avg === true;
}

function numeric(value) {
  const raw = isAvg(value) ? value.value : value;
  if (typeof raw === "number") return raw;
  if (raw === null || raw === undefined || raw === "" || typeof raw === "boolean") return null;
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : null;
}

function display(value) {
  if (value === null || value === undefined || value === "") return "—";
  if (isAvg(value)) return value.value.toFixed(1);
  if (typeof value === "boolean") return value ? "true" : "false";
  return String(value);
}

function aggregate(fn, argument, datasets) {
  if (fn === "count") return datasets[argument]?.rows?.length ?? undefined;
  const dot = argument.indexOf(".");
  if (dot < 0) return undefined;
  const rows = datasets[argument.slice(0, dot)]?.rows;
  if (!rows) return undefined;
  const field = argument.slice(dot + 1);
  const values = rows.map((row) => Number(row[field])).filter(Number.isFinite);
  if (!values.length) return fn === "sum" || fn === "avg" ? 0 : undefined;
  if (fn === "sum") return values.reduce((total, value) => total + value, 0);
  if (fn === "avg") return { __avg: true, value: values.reduce((total, value) => total + value, 0) / values.length };
  if (fn === "min") return Math.min(...values);
  return Math.max(...values);
}

function lookup(word, datasets) {
  const dot = word.indexOf(".");
  if (dot < 0) return datasets[word]?.rows?.length ?? undefined;
  const dataset = datasets[word.slice(0, dot)];
  const row = dataset?.rows?.[0];
  return row ? row[word.slice(dot + 1)] : undefined;
}

function truthy(value) {
  if (typeof value === "boolean") return value;
  if (value === null || value === undefined) return false;
  const number = numeric(value);
  if (number !== null) return number !== 0;
  return String(display(value)).length > 0;
}

function compareValues(left, right, operator) {
  const leftNumber = numeric(left);
  const rightNumber = numeric(right);
  const result = leftNumber !== null && rightNumber !== null
    ? (leftNumber < rightNumber ? -1 : leftNumber > rightNumber ? 1 : 0)
    : display(left).localeCompare(display(right), undefined, { sensitivity: "base" });
  switch (operator) {
    case "=": case "==": return result === 0;
    case "!=": case "<>": return result !== 0;
    case ">": return result > 0;
    case ">=": return result >= 0;
    case "<": return result < 0;
    case "<=": return result <= 0;
    default: return false;
  }
}

function parseExpr(source, datasets) {
  const tokens = tokenizeExpr(source);
  let pos = 0;
  const peek = () => tokens[pos];
  const next = () => tokens[pos++];
  const isWord = (token, value) => Boolean(token) && token.type === "word" && token.value.toLowerCase() === value;

  function ifExpr() {
    if (isWord(peek(), "if")) {
      next();
      const condition = orExpr();
      if (!isWord(peek(), "then")) throw new Error("Expected 'then'");
      next();
      const thenValue = ifExpr();
      let elseValue;
      if (isWord(peek(), "else")) {
        next();
        elseValue = ifExpr();
      }
      return truthy(condition) ? thenValue : elseValue;
    }
    return orExpr();
  }

  function orExpr() {
    let left = andExpr();
    while (isWord(peek(), "or")) {
      next();
      left = truthy(left) || truthy(andExpr());
    }
    return left;
  }

  function andExpr() {
    let left = comparison();
    while (isWord(peek(), "and")) {
      next();
      left = truthy(left) && truthy(comparison());
    }
    return left;
  }

  function comparison() {
    const left = concat();
    const token = peek();
    if (token && token.type === "op" && COMPARATORS.has(token.value)) {
      next();
      return compareValues(left, concat(), token.value);
    }
    return left;
  }

  function concat() {
    let left = primary();
    while (peek() && peek().type === "op" && peek().value === "+") {
      next();
      left = display(left) + display(primary());
    }
    return left;
  }

  function primary() {
    const token = peek();
    if (!token) return undefined;
    if (token.type === "op" && token.value === "(") {
      next();
      const value = ifExpr();
      if (peek() && peek().value === ")") next();
      return value;
    }
    if (token.type === "string") { next(); return token.value; }
    if (token.type === "number") { next(); return token.value; }
    if (token.type === "word") {
      const lower = token.value.toLowerCase();
      if (lower === "true") { next(); return true; }
      if (lower === "false") { next(); return false; }
      if (AGGREGATE_FUNCTIONS.has(lower) && tokens[pos + 1] && tokens[pos + 1].value === "(") {
        next();
        next();
        const argument = next();
        if (peek() && peek().value === ")") next();
        return aggregate(lower, argument ? String(argument.value) : "", datasets);
      }
      next();
      return lookup(token.value, datasets);
    }
    next();
    return undefined;
  }

  return ifExpr();
}

function evaluate(expression, datasets) {
  const source = String(expression ?? "").replace(/^\{|\}$/g, "").trim();
  if (!source) return "—";
  try {
    return display(parseExpr(source, datasets || {}));
  } catch {
    return "—";
  }
}

// ── array/column literal parsing (rows, headers, columns props) ────────────

function splitTopLevel(text) {
  const parts = [];
  let depth = 0;
  let quote = null;
  let current = "";
  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (quote) {
      current += char;
      if (char === quote && text[i - 1] !== "\\") quote = null;
      continue;
    }
    if (char === '"' || char === "'") { quote = char; current += char; continue; }
    if (char === "(" || char === "[") depth++;
    else if (char === ")" || char === "]") depth--;
    if (char === "," && depth === 0) { parts.push(current.trim()); current = ""; continue; }
    current += char;
  }
  if (current.trim()) parts.push(current.trim());
  return parts;
}

function parseArrayLiteral(source) {
  const trimmed = String(source ?? "").trim();
  const inner = trimmed.startsWith("[") && trimmed.endsWith("]") ? trimmed.slice(1, -1) : trimmed;
  return splitTopLevel(inner);
}

function unquoteLiteral(text) {
  const trimmed = String(text ?? "").trim();
  const match = /^"([\s\S]*)"$/.exec(trimmed) || /^'([\s\S]*)'$/.exec(trimmed);
  if (!match) return trimmed;
  return match[1].replace(/\\(["'\\])/g, "$1");
}

function parseStringArray(source) {
  return parseArrayLiteral(source).map(unquoteLiteral);
}

function parseRows(source, datasets) {
  return parseArrayLiteral(source).map((row) => parseArrayLiteral(row).map((cell) => evaluate(cell, datasets)));
}

// <DataTable columns={["id AS \"Order\"", "total"]}> renames the header without changing which
// field is read for that column — the `AS` clause is parsed here, not stripped away like a plain string.
function parseColumnSpecs(source) {
  return parseArrayLiteral(source).map((raw) => {
    const clean = unquoteLiteral(raw);
    const renamed = /^(.+?)\s+AS\s+(.+)$/i.exec(clean);
    if (renamed) return { field: renamed[1].trim(), label: unquoteLiteral(renamed[2].trim()) };
    return { field: clean, label: clean };
  });
}

function datasetName(component) {
  return component.props?.data?.replace(/^\{|\}$/g, "").trim();
}

// {{ expr }} inside prose uses the same value-expression language the components do. Substitution
// happens before markdown(), which escapes, so an interpolated value can never inject markup.
function interpolate(text, datasets) {
  return String(text ?? "").replace(/\{\{([^}]*)\}\}/g, (_match, expression) => evaluate(expression, datasets));
}

// Boolean cells colour themselves from their value (green/red) rather than being authored —
// documented under "Colour" in query-language-reference.md — so this is shared by every table
// renderer (DataTable, QuickTable, LabelTable) instead of each printing "true"/"false" as text.
function formatCell(value) {
  if (value === "" || value === null || value === undefined) return "—";
  if (typeof value === "boolean") return `<span class="insight-flag is-${value}">${value}</span>`;
  if (typeof value === "object") return escapeHtml(JSON.stringify(value));
  return escapeHtml(String(value));
}

function dataTable(dataset, component) {
  const fallbackFields = dataset.columns?.length ? dataset.columns : Object.keys(dataset.rows?.[0] || {});
  const columns = component.props.columns
    ? parseColumnSpecs(component.props.columns)
    : fallbackFields.map((field) => ({ field, label: field }));
  return `<section class="insight-card">
    <header class="insight-card-heading"><div><p class="insight-card-kicker">Dataset</p><h2>${escapeHtml(component.props.title || datasetName(component))}</h2></div><span>${dataset.rows.length} rows</span></header>
    <div class="insight-table-scroll"><table class="insight-data-table"><thead><tr>${columns.map((column) => `<th>${escapeHtml(column.label)}</th>`).join("")}</tr></thead><tbody>${dataset.rows.slice(0, 100).map((row) => `<tr>${columns.map((column) => `<td>${formatCell(row[column.field])}</td>`).join("")}</tr>`).join("")}</tbody></table>${dataset.rows.length > 100 ? `<p class="insight-table-note">Showing the first 100 of ${dataset.rows.length} rows.</p>` : ""}</div>
  </section>`;
}

// QuickTable/LabelTable — inline tables written directly in the document via a `rows` literal
// rather than a `let` dataset. QuickTable defaults to a Label/Value header; LabelTable has none
// unless `headers` is given.
function inlineTable(component, datasets) {
  const rows = parseRows(component.props.rows, datasets);
  const explicitHeaders = component.props.headers ? parseStringArray(component.props.headers) : null;
  const headers = explicitHeaders || (component.type === "QuickTable" ? ["Label", "Value"] : null);
  return `<section class="insight-card insight-inline-table">
    <header class="insight-card-heading"><div><p class="insight-card-kicker">Table</p><h2>${escapeHtml(component.props.title || (component.type === "QuickTable" ? "Summary" : "Details"))}</h2></div></header>
    <div class="insight-table-scroll"><table class="insight-data-table">${headers ? `<thead><tr>${headers.map((header) => `<th>${escapeHtml(header)}</th>`).join("")}</tr></thead>` : ""}<tbody>${rows.map((row) => `<tr>${row.map((cell) => `<td>${formatCell(cell)}</td>`).join("")}</tr>`).join("")}</tbody></table></div>
  </section>`;
}

// <Metrics /> — aggregate execution stats, computed from the same requests/datasets payload the
// <Status /> table reads, rather than anything the document author supplies.
function metricsBlock(data) {
  const requests = data.requests || [];
  const succeeded = requests.filter((request) => request.success).length;
  const rows = Object.values(data.datasets || {}).reduce((total, dataset) => total + (dataset.rows?.length || 0), 0);
  const duration = requests.reduce((total, request) => total + (request.cached ? 0 : (request.durationMs || 0)), 0);
  const metrics = [
    ["Requests", requests.length],
    ["Succeeded", succeeded],
    ["Failed", requests.length - succeeded],
    ["Rows returned", rows],
    ["Total duration", `${duration} ms`],
  ];
  return `<section class="insight-card insight-metrics">
    <header class="insight-card-heading"><div><p class="insight-card-kicker">Execution</p><h2>Run metrics</h2></div></header>
    <div class="insight-metric-rows">${metrics.map(([label, value]) => `<div class="insight-kv"><span class="insight-kv-label">${escapeHtml(label)}</span><span class="insight-kv-value">${escapeHtml(String(value))}</span></div>`).join("")}</div>
  </section>`;
}

const MAX_BARS = 24;

function barChart(dataset, component) {
  const x = component.props.x;
  const y = component.props.y;
  // Chartable rows are counted before the cap so the header reports the real category count and
  // the truncation is stated, rather than the chart silently presenting a subset as the whole.
  const chartable = dataset.rows.map((row) => ({ label: String(row[x] ?? "—"), value: Number(row[y]) })).filter((point) => Number.isFinite(point.value));
  const points = chartable.slice(0, MAX_BARS);
  const width = 700;
  const height = 280;
  const left = 48;
  const right = 18;
  const top = 24;
  const bottom = 58;
  const plotWidth = width - left - right;
  const plotHeight = height - top - bottom;
  const maximum = Math.max(1, ...points.map((point) => point.value));
  const slot = points.length ? plotWidth / points.length : plotWidth;
  const barWidth = Math.max(7, Math.min(40, slot - 8));
  const bars = points.map((point, index) => {
    const barHeight = point.value / maximum * plotHeight;
    const barX = left + index * slot + (slot - barWidth) / 2;
    const barY = top + plotHeight - barHeight;
    return `<g><rect x="${barX}" y="${barY}" width="${barWidth}" height="${barHeight}" rx="4" class="insight-bar"></rect><text x="${barX + barWidth / 2}" y="${barY - 6}" text-anchor="middle" class="insight-value-label">${escapeHtml(point.value)}</text><text x="${barX + barWidth / 2}" y="${top + plotHeight + 20}" text-anchor="end" transform="rotate(-35 ${barX + barWidth / 2} ${top + plotHeight + 20})" class="insight-axis-label">${escapeHtml(point.label)}</text></g>`;
  }).join("");
  // The twin is the accessible route to the values, so it carries every chartable row (to the same
  // 100-row ceiling <DataTable> uses), not just the bars that fit on the axis.
  const twinRows = chartable.slice(0, 100);
  const twin = chartable.length ? `<details class="insight-table-twin"><summary>Show chart data table</summary>
    <div class="insight-table-scroll"><table class="insight-data-table"><thead><tr><th>${escapeHtml(x)}</th><th>${escapeHtml(y)}</th></tr></thead><tbody>${twinRows.map((point) => `<tr><td>${escapeHtml(point.label)}</td><td>${escapeHtml(point.value)}</td></tr>`).join("")}</tbody></table>${chartable.length > twinRows.length ? `<p class="insight-table-note">Showing the first ${twinRows.length} of ${chartable.length} rows.</p>` : ""}</div>
  </details>` : "";
  const truncated = chartable.length > points.length
    ? `<p class="insight-table-note">Charting the first ${points.length} of ${chartable.length} categories — open the data table for the rest.</p>`
    : "";
  return `<section class="insight-card insight-chart-card"><header class="insight-card-heading"><div><p class="insight-card-kicker">Comparison</p><h2>${escapeHtml(component.props.title || `${y} by ${x}`)}</h2></div><span>${chartable.length} categories</span></header>
    ${points.length ? `<svg class="insight-bar-chart" viewBox="0 0 ${width} ${height}" role="img">${[0, .5, 1].map((tick) => { const position = top + plotHeight - tick * plotHeight; return `<g><line x1="${left}" x2="${width - right}" y1="${position}" y2="${position}" class="insight-grid-line"></line><text x="${left - 9}" y="${position + 4}" text-anchor="end" class="insight-axis-label">${Math.round(maximum * tick)}</text></g>`; }).join("")}${bars}</svg>` : '<p class="insight-no-data">No numeric rows to chart.</p>'}
    ${truncated}
    ${twin}
  </section>`;
}

function renderInsight(data) {
  const blocks = [];
  const stats = [];
  const flushStats = () => {
    if (!stats.length) return;
    blocks.push(`<div class="insight-kpi-row">${stats.splice(0).map((component) => `<section class="insight-stat"><span>${escapeHtml(component.props.label || "Metric")}</span><strong>${escapeHtml(evaluate(component.props.value, data.datasets))}</strong></section>`).join("")}</div>`);
  };
  (data.outline || []).forEach((component) => {
    if (component.type === "Stat") {
      stats.push(component);
      return;
    }
    if (["KpiRow", "Filter"].includes(component.type)) return;
    flushStats();
    const dataset = data.datasets?.[datasetName(component)];
    if (component.type === "Prose") blocks.push(`<div class="insight-prose">${markdown(interpolate(component.props.value, data.datasets))}</div>`);
    else if (component.type === "DataTable" && dataset) blocks.push(dataTable(dataset, component));
    else if (component.type === "BarChart" && dataset) blocks.push(barChart(dataset, component));
    else if (component.type === "Text") blocks.push(`<p class="insight-text">${escapeHtml(evaluate(component.props.value, data.datasets))}</p>`);
    else if (["KeyValue", "LabelValue"].includes(component.type)) blocks.push(`<div class="insight-kv ${component.type === "LabelValue" ? "is-plain" : ""}"><span class="insight-kv-label">${escapeHtml(component.props.label || "Value")}</span><span class="insight-kv-value">${escapeHtml(evaluate(component.props.value, data.datasets))}</span></div>`);
    else if (["QuickTable", "LabelTable"].includes(component.type)) blocks.push(inlineTable(component, data.datasets));
    else if (component.type === "Metrics") blocks.push(metricsBlock(data));
    else if (component.type === "Status" && data.requests?.length) blocks.push(`<section class="insight-card"><header class="insight-card-heading"><div><p class="insight-card-kicker">Execution</p><h2>Request status</h2></div></header><div class="insight-table-scroll"><table class="insight-data-table"><thead><tr><th>Request</th><th>Method</th><th>Status</th><th>Duration</th></tr></thead><tbody>${data.requests.map((request) => `<tr><td>${escapeHtml(request.request)}</td><td class="mono">${escapeHtml(request.method)}</td><td class="mono insight-status-code is-${request.success ? "ok" : "failed"}">${request.status}</td><td class="mono">${request.cached ? "cached" : `${request.durationMs} ms`}</td></tr>`).join("")}</tbody></table></div></section>`);
  });
  flushStats();
  return `<div class="insight-rendered">${blocks.join("") || '<div class="insight-preview-empty"><h2>No renderable components</h2><p>Add a Stat, BarChart, or DataTable component to the document.</p></div>'}</div>`;
}

const STORE_KEY = "mcp.insights.workspace.v1";

/**
 * Which insight was open and whether the editor was showing. Only these two preferences are local —
 * the run result itself lives on the insight, so it follows the document across browsers.
 */
function loadStore() {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORE_KEY));
    return {
      // Validated on read: this id is interpolated into a URL and into a data-id attribute.
      lastInsightId: typeof parsed?.lastInsightId === "string" && parsed.lastInsightId.length <= 64
        ? parsed.lastInsightId
        : null,
      editing: parsed?.editing === true,
    };
  } catch {
    // The workspace stays fully usable when browser storage is unavailable.
  }
  return { lastInsightId: null, editing: false };
}

function saveStore(store) {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify({
      lastInsightId: store.lastInsightId || null,
      editing: Boolean(store.editing),
    }));
  } catch {
    // Remembering the last insight is an optional convenience.
  }
}

export async function mount(outlet) {
  const state = {
    source: EXAMPLE,
    connections: [],
    connectionId: "",
    analysis: null,
    data: null,
    parameters: {},
    running: false,
    error: "",
    saved: [],
    activeId: null,
    name: "Untitled insight",
    saving: false,
    // The document as last saved, so "edited since this run" is a plain comparison.
    savedSource: "",
    editing: false,
    lastRunAt: null,
    // True only for a run in this session; a hydrated snapshot is labelled differently.
    dataFresh: false,
    runNote: "",
    opening: false,
  };
  const abort = new AbortController();
  let analysisTimer = 0;

  function diagnostics() {
    const items = state.analysis?.diagnostics || [];
    if (!items.length) return '<p class="insight-analysis-ok">Document checks clean.</p>';
    return `<div class="insight-diagnostics">${items.slice(0, 4).map((item) => `<p class="is-${item.severity.toLowerCase()}">${icon("alert", 14)}<b>${escapeHtml(item.code)}</b> ${escapeHtml(item.message)}</p>`).join("")}${items.length > 4 ? `<span>+${items.length - 4} more checks</span>` : ""}</div>`;
  }

  function parameterControls() {
    const params = state.analysis?.params || state.data?.params || [];
    if (!params.length) return "";
    return `<div class="insight-params" aria-label="Insight parameters">${params.map((param) => `<label><span>${escapeHtml(param.name)}</span><input data-param="${escapeAttr(param.name)}" type="${param.type === "number" ? "number" : "text"}" value="${escapeAttr(state.parameters[param.name] ?? param.defaultValue ?? "")}"></label>`).join("")}</div>`;
  }

  /** When the result was produced, and whether it can still be trusted to match the document. */
  function previewStatus() {
    if (!state.data) return "Run to fetch API data";
    const when = state.lastRunAt ? formatDate(state.lastRunAt) : "";
    const edited = state.activeId && state.source !== state.savedSource
      ? " · Document edited since this run"
      : "";
    if (state.dataFresh) return `${when ? `Ran ${escapeHtml(when)}` : "Last successful run"}${edited}`;
    return `<span class="insight-stale-chip">Saved result</span>${when ? ` · ran ${escapeHtml(when)}` : ""}${edited}`;
  }

  /** Errors live in the editor footer, which is hidden by default — surface a count regardless. */
  function hiddenDiagnosticsNote() {
    if (state.editing) return "";
    const errors = (state.analysis?.diagnostics || []).filter((item) => item.severity === "ERROR");
    if (!errors.length) return "";
    return `<div class="insight-run-error">${icon("alert", 15)} ${errors.length} issue${errors.length === 1 ? "" : "s"} in the document — press Edit to see ${errors.length === 1 ? "it" : "them"}.</div>`;
  }

  function editorPanel() {
    return `<section class="insight-editor-panel"><header><span>insight.rqd</span><small>Markdown · RQL · components</small></header><textarea id="insight-source" class="insight-plain-editor" spellcheck="false">${escapeHtml(state.source)}</textarea><footer>${diagnostics()}</footer></section>`;
  }

  function render() {
    const usable = state.connections.filter((connection) => connection.status === "CONNECTED");
    // Preserve the caret across the full-innerHTML repaint; the debounced analyze() re-renders
    // mid-typing, and the editor toggle adds another path that would otherwise jump to the end.
    const editor = outlet.querySelector("#insight-source");
    const caret = editor
      ? { start: editor.selectionStart, end: editor.selectionEnd, scroll: editor.scrollTop }
      : null;
    outlet.innerHTML = `<section class="insight-page" aria-labelledby="insight-page-title">
      <header class="insight-page-header"><div><p class="eyebrow">${icon("file", 14)} Insight workspace</p><h1 id="insight-page-title">Insights</h1><p>Write RQL beside the view it drives. One insight can read from several connected apps.</p></div>
        <div class="insight-actions"><label class="insight-name-field"><span>Name</span><input id="insight-name" value="${escapeAttr(state.name)}"></label><label class="insight-connection-picker"><span>Default app</span><select id="insight-connection"><option value="">All connected apps</option>${usable.map((connection) => `<option value="${escapeAttr(connection.id)}" ${state.connectionId === connection.id ? "selected" : ""}>${escapeHtml(connection.name)}</option>`).join("")}</select></label><button class="btn ${state.editing ? "is-active" : ""}" type="button" data-action="toggle-editor" aria-pressed="${state.editing}">${icon("file", 15)} Edit</button><button class="btn" type="button" data-action="save-insight" ${state.saving ? "disabled" : ""}>${icon("download", 15)} ${state.saving ? "Saving…" : "Save"}</button><button class="insight-run-button" type="button" data-action="run-insight" ${state.running ? "disabled" : ""}>${icon("play", 15)} ${state.running ? "Running…" : "Run insight"}</button></div>
      </header>
      ${!usable.length ? '<div class="insight-connection-note">Import and connect an API collection on Connections to run an insight.</div>' : ""}
      ${hiddenDiagnosticsNote()}
      ${parameterControls()}
      <div class="insight-workspace ${state.editing ? "is-editing" : ""}">
        <nav class="insight-library" aria-label="Saved insights"><header><span>Saved insights</span><button class="btn btn-sm" type="button" data-action="new-insight">New</button></header>${state.saved.length ? `<ul>${state.saved.map((insight) => `<li><button class="insight-library-item ${insight.id === state.activeId ? "is-active" : ""}" type="button" data-action="open-insight" data-id="${escapeAttr(insight.id)}"><strong>${escapeHtml(insight.name)}</strong>${insight.description ? `<small>${escapeHtml(insight.description)}</small>` : ""}</button><button class="insight-library-delete" type="button" data-action="delete-insight" data-id="${escapeAttr(insight.id)}" aria-label="Delete ${escapeAttr(insight.name)}">${icon("trash", 13)}</button></li>`).join("")}</ul>` : '<p class="insight-library-empty">Nothing saved yet. Build one, then press Save.</p>'}</nav>
        ${state.editing ? editorPanel() : ""}
        <section class="insight-preview-panel" aria-live="polite"><header><span>Live insight</span><small>${previewStatus()}</small></header>${state.error ? `<div class="insight-run-error">${icon("alert", 15)} ${escapeHtml(state.error)}</div>` : ""}${state.runNote ? `<p class="insight-run-note">${escapeHtml(state.runNote)}</p>` : ""}${state.data ? renderInsight(state.data) : state.opening ? '<div class="insight-preview-empty"><h2>Opening…</h2></div>' : `<div class="insight-preview-empty">${icon("file", 24)}<h2>Ready for a query</h2><p>Run the document to fetch data and render its datasets.</p></div>`}</section>
      </div>
    </section>`;
    if (caret) {
      const restored = outlet.querySelector("#insight-source");
      if (restored) {
        restored.setSelectionRange(caret.start, caret.end);
        restored.scrollTop = caret.scroll;
      }
    }
  }

  async function analyze() {
    try {
      state.analysis = await api.analyzeInsight({
        source: state.source,
        connectionId: state.connectionId || undefined,
      });
      (state.analysis.params || []).forEach((param) => {
        if (!(param.name in state.parameters)) state.parameters[param.name] = param.defaultValue;
      });
    } catch (error) {
      state.analysis = {
        diagnostics: [{ severity: "ERROR", code: "RQI500", message: message(error, "Analysis failed") }],
        params: [],
        outline: [],
      };
    }
    render();
  }

  async function refresh() {
    const [connections, saved] = await Promise.all([
      api.listConnections().catch(() => []),
      api.listInsights().catch(() => []),
    ]);
    state.connections = connections;
    state.saved = saved;
    if (!state.connectionId) state.connectionId = connections.find((item) => item.status === "CONNECTED")?.id || "";
    render();
  }

  /**
   * Fetches the full row rather than reusing the library entry: listInsights() deliberately omits
   * the run snapshot, so state.saved never carries lastRun and reading from it would leave the
   * preview permanently empty.
   */
  async function openInsight(id) {
    const insight = await api.getInsight(id);
    state.activeId = insight.id;
    state.name = insight.name;
    state.source = insight.source;
    state.savedSource = insight.source;
    state.connectionId = insight.connectionId || "";
    state.data = insight.lastRun || null;
    state.lastRunAt = insight.lastRunAt || null;
    state.dataFresh = false;
    state.runNote = "";
    state.error = "";
    // Cleared so the next analyze() re-seeds defaults from this document instead of leaking
    // parameter names from the previously open one.
    state.parameters = {};
    saveStore({ lastInsightId: insight.id, editing: state.editing });
  }

  /** Last insight worked on, else the most recently updated, else the empty new-document state. */
  async function restoreLastOpened() {
    const stored = loadStore();
    state.editing = stored.editing;
    const wanted = state.saved.find((item) => item.id === stored.lastInsightId) || state.saved[0];
    if (!wanted) {
      saveStore({ lastInsightId: null, editing: state.editing });
      return;
    }
    try {
      await openInsight(wanted.id);
    } catch {
      // Deleted from another browser: fall back to the empty state rather than an error.
      saveStore({ lastInsightId: null, editing: state.editing });
    }
  }

  on(outlet, "click", "[data-action]", async (_event, target) => {
    const { action, id } = target.dataset;
    if (action === "run-insight") {
      state.running = true;
      state.error = "";
      render();
      try {
        const payload = {
          source: state.source,
          connectionId: state.connectionId || undefined,
          parameters: state.parameters,
        };
        if (state.activeId) {
          // Saved insight: the server runs it and keeps the result on the row.
          const result = await api.runInsight(state.activeId, payload);
          state.data = result.data;
          state.lastRunAt = result.ranAt || new Date().toISOString();
          state.runNote = result.stored ? "" : (result.storeNote || "");
        } else {
          // Unsaved draft: no id to store against, so this stays a pure evaluation.
          state.data = await api.loadInsightData(payload);
          state.lastRunAt = new Date().toISOString();
          state.runNote = "Save this insight to keep its result.";
        }
        state.dataFresh = true;
        state.analysis = { ...state.analysis, diagnostics: state.data.diagnostics, params: state.data.params, outline: state.data.outline };
      } catch (error) {
        state.error = message(error, "The insight could not be loaded.");
      } finally {
        state.running = false;
        render();
      }
    } else if (action === "toggle-editor") {
      state.editing = !state.editing;
      saveStore({ lastInsightId: state.activeId, editing: state.editing });
      render();
    } else if (action === "save-insight") {
      state.saving = true;
      render();
      try {
        const payload = { name: state.name, source: state.source, connectionId: state.connectionId || null };
        const wasNew = !state.activeId;
        const stored = state.activeId ? await api.updateInsight(state.activeId, payload) : await api.createInsight(payload);
        state.activeId = stored.id;
        state.savedSource = state.source;
        state.saved = await api.listInsights();
        saveStore({ lastInsightId: stored.id, editing: state.editing });
        // A draft's result was computed before the insight had an id, so nothing was stored for it.
        if (wasNew && state.data) state.runNote = "Run again to save this result with the insight.";
      } catch (error) {
        state.error = message(error, "Save failed");
      } finally {
        state.saving = false;
        render();
      }
    } else if (action === "new-insight") {
      state.activeId = null;
      state.name = "Untitled insight";
      state.source = EXAMPLE;
      state.savedSource = "";
      state.data = null;
      state.lastRunAt = null;
      state.dataFresh = false;
      state.runNote = "";
      state.error = "";
      state.parameters = {};
      saveStore({ lastInsightId: null, editing: state.editing });
      render();
      analyze();
    } else if (action === "open-insight") {
      if (!id || id === state.activeId) return;
      try {
        await openInsight(id);
      } catch (error) {
        state.error = message(error, "That insight could not be opened.");
      }
      render();
      analyze();
    } else if (action === "delete-insight") {
      const insight = state.saved.find((item) => item.id === id);
      if (!insight || !confirm(`Delete "${insight.name}"?`)) return;
      try {
        await api.deleteInsight(id);
        if (state.activeId === id) {
          state.activeId = null;
          state.name = "Untitled insight";
          state.source = EXAMPLE;
          state.savedSource = "";
          state.data = null;
          state.lastRunAt = null;
          state.dataFresh = false;
          state.runNote = "";
          state.parameters = {};
          saveStore({ lastInsightId: null, editing: state.editing });
        }
        state.saved = await api.listInsights();
      } catch (error) {
        state.error = message(error, "Delete failed");
      }
      render();
    } else if (action === "dismiss-banner") {
      state.error = "";
      render();
    }
  });

  outlet.addEventListener("input", (event) => {
    if (event.target.id === "insight-source") {
      state.source = event.target.value;
      clearTimeout(analysisTimer);
      analysisTimer = window.setTimeout(analyze, 320);
    } else if (event.target.id === "insight-name") {
      state.name = event.target.value;
    } else if (event.target.dataset.param) {
      state.parameters[event.target.dataset.param] = event.target.type === "number" ? Number(event.target.value) : event.target.value;
    }
  }, { signal: abort.signal });
  outlet.addEventListener("change", (event) => {
    if (event.target.id === "insight-connection") {
      state.connectionId = event.target.value;
      analyze();
    }
  }, { signal: abort.signal });

  render();
  await refresh();
  state.opening = true;
  render();
  await restoreLastOpened();
  state.opening = false;
  render();
  analyze();
  return () => {
    abort.abort();
    clearTimeout(analysisTimer);
  };
}
