import { api } from "../api.js";
import { escapeAttr, escapeHtml, formatDate, icon, markdown, message, on } from "../ui.js";

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

function isNumericValue(value) {
  if (typeof value === "number") return Number.isFinite(value);
  if (typeof value !== "string" || !value.trim()) return false;
  return Number.isFinite(Number(value));
}

// Boolean cells colour themselves from their value (green/red) rather than being authored —
// documented under "Colour" in query-language-reference.md — so this is shared by every table
// renderer (DataTable, QuickTable, LabelTable) instead of each printing "true"/"false" as text.
// Cells are clipped to a max width by the stylesheet, so the untruncated value is carried on
// `title` — otherwise a long path or description is simply unreadable in the table.
function tableCell(value, numericColumn) {
  if (value === "" || value === null || value === undefined) return '<td class="is-empty">—</td>';
  if (typeof value === "boolean") return `<td><span class="insight-flag is-${value}">${value}</span></td>`;
  const text = typeof value === "object" ? JSON.stringify(value) : String(value);
  const numeric = numericColumn ?? isNumericValue(value);
  return `<td class="${numeric ? "is-numeric" : ""}" title="${escapeAttr(text)}">${escapeHtml(text)}</td>`;
}

// Whether a column holds figures, decided once for the whole column rather than per cell: a
// right-aligned tabular column only reads as a column if every row aligns the same way, and a
// single null or blank in an otherwise numeric field must not flip the alignment mid-table.
function isNumericColumn(rows, field) {
  let seen = 0;
  for (const row of rows) {
    const value = row[field];
    if (value === null || value === undefined || value === "") continue;
    if (!isNumericValue(value)) return false;
    if (++seen >= 20) break;
  }
  return seen > 0;
}

function dataTable(dataset, component) {
  const fallbackFields = dataset.columns?.length ? dataset.columns : Object.keys(dataset.rows?.[0] || {});
  const columns = component.props.columns
    ? parseColumnSpecs(component.props.columns)
    : fallbackFields.map((field) => ({ field, label: field }));
  const rows = dataset.rows.slice(0, 100);
  const numeric = columns.map((column) => isNumericColumn(rows, column.field));
  return `<section class="insight-card">
    <header class="insight-card-heading"><div><p class="insight-card-kicker">Dataset</p><h2>${escapeHtml(component.props.title || datasetName(component))}</h2></div><span>${dataset.rows.length} rows</span></header>
    <div class="insight-table-scroll"><table class="insight-data-table"><thead><tr>${columns.map((column, index) => `<th class="${numeric[index] ? "is-numeric" : ""}">${escapeHtml(column.label)}</th>`).join("")}</tr></thead><tbody>${rows.map((row) => `<tr>${columns.map((column, index) => tableCell(row[column.field], numeric[index])).join("")}</tr>`).join("")}</tbody></table></div>${dataset.rows.length > 100 ? `<p class="insight-table-note">Showing the first 100 of ${dataset.rows.length} rows.</p>` : ""}
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
    <div class="insight-table-scroll"><table class="insight-data-table">${headers ? `<thead><tr>${headers.map((header) => `<th>${escapeHtml(header)}</th>`).join("")}</tr></thead>` : ""}<tbody>${rows.map((row) => `<tr>${row.map((cell) => tableCell(cell)).join("")}</tr>`).join("")}</tbody></table></div>
  </section>`;
}

/**
 * <Status /> — one row per request the run issued, each with a bar scaled to the slowest of them.
 * A column of millisecond figures makes you compare numbers to find the expensive call; the bar makes
 * it the longest thing on screen. Cached requests cost nothing and are labelled rather than plotted,
 * so a cache hit cannot read as a fast request.
 */
function statusBlock(requests) {
  const slowest = Math.max(1, ...requests.map((request) => (request.cached ? 0 : request.durationMs || 0)));
  return `<section class="insight-card"><header class="insight-card-heading"><div><p class="insight-card-kicker">Execution</p><h2>Request status</h2></div><span>${plural(requests.length, "request")}</span></header>
    <div class="insight-table-scroll"><table class="insight-data-table"><thead><tr><th>Request</th><th>Method</th><th>Status</th><th class="is-numeric">Duration</th><th class="insight-duration-column"></th></tr></thead><tbody>${requests.map((request) => {
      const milliseconds = request.cached ? 0 : request.durationMs || 0;
      const share = Math.round(milliseconds / slowest * 100);
      return `<tr><td title="${escapeAttr(request.request)}">${escapeHtml(request.request)}</td><td class="mono">${escapeHtml(request.method)}</td><td class="mono insight-status-code is-${request.success ? "ok" : "failed"}">${request.status}</td><td class="mono is-numeric">${request.cached ? "cached" : `${milliseconds} ms`}</td>
        <td class="insight-duration-column">${request.cached ? "" : `<span class="insight-duration-track"><span class="insight-duration-bar ${request.success ? "" : "is-failed"}" style="width: ${share}%"></span></span>`}</td></tr>`;
    }).join("")}</tbody></table></div>
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
// A line carries far more categories than a bar before it stops being readable, and a pie stops
// being readable almost immediately — the palette's eight series is the honest ceiling there.
const MAX_LINE_POINTS = 60;
const MAX_SLICES = 8;
const MAX_AXIS_LABEL = 12;

// Every chart reads the same data/x/y triple, so the row→point projection, the truncation notice and
// the accessible table twin are shared rather than restated per chart — the twin in particular is the
// only route to exact values for a screen reader, and it must behave identically across chart types.
function chartPoints(dataset, component) {
  const x = component.props.x;
  const y = component.props.y;
  return dataset.rows
    .map((row) => {
      const raw = row[y];
      // Number(null), Number(""), and Number(false) all become zero, but those are missing or
      // non-measure values rather than real observations. Keep chart semantics aligned with the
      // table's numeric-column test and drop them instead of inventing a zero-height point.
      const value = raw === null || raw === undefined || raw === "" || typeof raw === "boolean"
        || (typeof raw !== "number" && typeof raw !== "string")
        || (typeof raw === "string" && !raw.trim())
        ? NaN
        : Number(raw);
      return { label: String(row[x] ?? "—"), value };
    })
    .filter((point) => Number.isFinite(point.value));
}

function chartTwin(points, component) {
  if (!points.length) return "";
  const rows = points.slice(0, 100);
  return `<details class="insight-table-twin"><summary>Show chart data table</summary>
    <div class="insight-table-scroll"><table class="insight-data-table"><thead><tr><th>${escapeHtml(component.props.x)}</th><th class="is-numeric">${escapeHtml(component.props.y)}</th></tr></thead><tbody>${rows.map((point) => `<tr>${tableCell(point.label, false)}${tableCell(point.value, true)}</tr>`).join("")}</tbody></table></div>${points.length > rows.length ? `<p class="insight-table-note">Showing the first ${rows.length} of ${points.length} rows.</p>` : ""}
  </details>`;
}

function chartTruncation(charted, total, noun) {
  return charted < total
    ? `<p class="insight-table-note">Charting the first ${charted} of ${total} ${noun} — open the data table for the rest.</p>`
    : "";
}

function chartFrame(component, kicker, title, count, countNoun, body, truncation, twin) {
  return `<section class="insight-card insight-chart-card"><header class="insight-card-heading"><div><p class="insight-card-kicker">${escapeHtml(kicker)}</p><h2>${escapeHtml(title)}</h2></div><span>${count} ${escapeHtml(countNoun)}</span></header>
    ${body}
    ${truncation}
    ${twin}
  </section>`;
}

// Axis labels are rotated, so their length is consumed as vertical space below the plot. Capping
// the character count is what keeps them inside the viewBox — the SVG paints outside its box
// (overflow: visible, needed for value labels above the tallest bar), so an uncapped category name
// would run over whatever card is rendered next rather than being clipped.
function axisLabel(text) {
  return text.length > MAX_AXIS_LABEL ? `${text.slice(0, MAX_AXIS_LABEL - 1)}…` : text;
}

function barChart(dataset, component) {
  const x = component.props.x;
  const y = component.props.y;
  const title = component.props.title || `${y} by ${x}`;
  // Chartable rows are counted before the cap so the header reports the real category count and
  // the truncation is stated, rather than the chart silently presenting a subset as the whole.
  const chartable = chartPoints(dataset, component);
  const points = chartable.slice(0, MAX_BARS);
  // The SVG scales to its container, and scaling also multiplies the 11px label type — at 700 wide
  // in a full-width card that landed near 1.7×, so axis and value labels rendered at ~19px and the
  // chart towered over every other card. Authoring at the width the card actually gets (capped to
  // the same value in CSS) keeps the chart at roughly 1:1 and its type at the size it was set in.
  const width = 1000;
  const height = 330;
  const left = 48;
  const right = 18;
  const top = 26;
  const bottom = 76;
  const plotWidth = width - left - right;
  const plotHeight = height - top - bottom;
  const maximum = Math.max(1, ...points.map((point) => point.value));
  const slot = points.length ? plotWidth / points.length : plotWidth;
  const barWidth = Math.max(7, Math.min(40, slot - 8));
  // Printed values turn to overlapping mush once the bars are narrow; past that density the hover
  // tooltip and the data table twin are the way to read exact numbers.
  const showValues = slot >= 42;
  const baseline = top + plotHeight;
  const bars = points.map((point, index) => {
    const barHeight = point.value / maximum * plotHeight;
    const barX = left + index * slot + (slot - barWidth) / 2;
    const barY = baseline - barHeight;
    const center = barX + barWidth / 2;
    return `<g class="insight-bar-group"><title>${escapeHtml(point.label)}: ${escapeHtml(point.value)}</title><rect x="${barX}" y="${top}" width="${barWidth}" height="${plotHeight}" class="insight-bar-hit"></rect><rect x="${barX}" y="${barY}" width="${barWidth}" height="${barHeight}" rx="4" class="insight-bar"></rect>${showValues ? `<text x="${center}" y="${barY - 6}" text-anchor="middle" class="insight-value-label">${escapeHtml(point.value)}</text>` : ""}<text x="${center}" y="${baseline + 16}" text-anchor="end" transform="rotate(-35 ${center} ${baseline + 16})" class="insight-axis-label">${escapeHtml(axisLabel(point.label))}</text></g>`;
  }).join("");
  // The twin is the accessible route to the values, so it carries every chartable row (to the same
  // 100-row ceiling <DataTable> uses), not just the bars that fit on the axis.
  const body = points.length
    ? `<svg class="insight-bar-chart" viewBox="0 0 ${width} ${height}" role="img" aria-label="${escapeAttr(`${title} — bar chart of ${points.length} categories, highest value ${maximum}. The same figures are listed in the chart data table below.`)}">${gridLines(left, width - right, baseline, plotHeight, maximum)}${bars}</svg>`
    : '<p class="insight-no-data">No numeric rows to chart.</p>';
  return chartFrame(component, "Comparison", title, chartable.length, "categories", body,
    chartTruncation(points.length, chartable.length, "categories"), chartTwin(chartable, component));
}

/** Horizontal reference lines at 0 / 50 / 100% of the scale, shared by the bar and line charts. */
function gridLines(left, right, baseline, plotHeight, maximum) {
  return [0, .5, 1].map((tick) => {
    const position = baseline - tick * plotHeight;
    return `<g><line x1="${left}" x2="${right}" y1="${position}" y2="${position}" class="insight-grid-line"></line><text x="${left - 9}" y="${position + 4}" text-anchor="end" class="insight-axis-label">${Math.round(maximum * tick)}</text></g>`;
  }).join("");
}

function lineChart(dataset, component) {
  const x = component.props.x;
  const y = component.props.y;
  const title = component.props.title || `${y} over ${x}`;
  const chartable = chartPoints(dataset, component);
  const points = chartable.slice(0, MAX_LINE_POINTS);
  const width = 1000;
  const height = 330;
  const left = 48;
  const right = 18;
  const top = 26;
  const bottom = 76;
  const plotWidth = width - left - right;
  const plotHeight = height - top - bottom;
  const maximum = Math.max(1, ...points.map((point) => point.value));
  const baseline = top + plotHeight;
  // A single point has no interval to spread across, so it sits mid-plot rather than hugging the axis.
  const step = points.length > 1 ? plotWidth / (points.length - 1) : 0;
  const positionOf = (index) => (points.length > 1 ? left + index * step : left + plotWidth / 2);
  const coordinates = points.map((point, index) => ({
    x: positionOf(index),
    y: baseline - point.value / maximum * plotHeight,
    point,
  }));
  const path = coordinates.map((coordinate, index) => `${index ? "L" : "M"}${coordinate.x.toFixed(1)} ${coordinate.y.toFixed(1)}`).join(" ");
  // Sixty labels cannot fit on one axis, so only every nth is drawn — the twin carries the rest.
  const labelEvery = Math.max(1, Math.ceil(points.length / 12));
  const markers = coordinates.map((coordinate, index) => `<g class="insight-point-group"><title>${escapeHtml(coordinate.point.label)}: ${escapeHtml(coordinate.point.value)}</title><circle cx="${coordinate.x.toFixed(1)}" cy="${coordinate.y.toFixed(1)}" r="9" class="insight-point-hit"></circle><circle cx="${coordinate.x.toFixed(1)}" cy="${coordinate.y.toFixed(1)}" r="3" class="insight-point"></circle>${index % labelEvery === 0 ? `<text x="${coordinate.x.toFixed(1)}" y="${baseline + 16}" text-anchor="end" transform="rotate(-35 ${coordinate.x.toFixed(1)} ${baseline + 16})" class="insight-axis-label">${escapeHtml(axisLabel(coordinate.point.label))}</text>` : ""}</g>`).join("");
  const body = points.length
    ? `<svg class="insight-bar-chart" viewBox="0 0 ${width} ${height}" role="img" aria-label="${escapeAttr(`${title} — line chart of ${points.length} points, highest value ${maximum}. The same figures are listed in the chart data table below.`)}">${gridLines(left, width - right, baseline, plotHeight, maximum)}<path d="${path}" class="insight-line" fill="none"></path>${markers}</svg>`
    : '<p class="insight-no-data">No numeric rows to chart.</p>';
  return chartFrame(component, "Trend", title, chartable.length, "points", body,
    chartTruncation(points.length, chartable.length, "points"), chartTwin(chartable, component));
}

/** Point on a circle, in the SVG coordinate system, measuring clockwise from twelve o'clock. */
function polar(centre, radius, fraction) {
  const angle = (fraction - 0.25) * Math.PI * 2;
  return `${(centre + radius * Math.cos(angle)).toFixed(2)} ${(centre + radius * Math.sin(angle)).toFixed(2)}`;
}

function pieChart(dataset, component) {
  const title = component.props.title || `${component.props.y} by ${component.props.x}`;
  // A negative or zero slice has no arc to draw, so those rows are dropped from the ring rather
  // than silently distorting every other slice's share of the total.
  const chartable = chartPoints(dataset, component).filter((point) => point.value > 0);
  const ranked = [...chartable].sort((first, second) => second.value - first.value);
  // Beyond the palette every extra slice would reuse a colour, so the tail is summed into one
  // honest "Other" wedge instead — the twin still lists each row separately.
  const head = ranked.slice(0, MAX_SLICES - 1);
  const tail = ranked.slice(MAX_SLICES - 1);
  const slices = tail.length > 1
    ? [...head, { label: `Other (${tail.length})`, value: tail.reduce((total, point) => total + point.value, 0) }]
    : ranked.slice(0, MAX_SLICES);
  const total = slices.reduce((sum, slice) => sum + slice.value, 0);
  const size = 260;
  const centre = size / 2;
  const outer = 112;
  const inner = 62;
  let cursor = 0;
  const wedges = slices.map((slice, index) => {
    const fraction = total ? slice.value / total : 0;
    const start = cursor;
    cursor += fraction;
    const series = `var(--series-${(index % 8) + 1})`;
    const label = `<title>${escapeHtml(slice.label)}: ${escapeHtml(slice.value)} (${(fraction * 100).toFixed(1)}%)</title>`;
    // A lone slice is a full turn, where an arc's start and end coincide and the path collapses —
    // drawn as a stroked circle instead so "one category" still renders as a complete ring.
    if (slices.length === 1) {
      return `<g class="insight-slice-group">${label}<circle cx="${centre}" cy="${centre}" r="${(outer + inner) / 2}" fill="none" stroke="${series}" stroke-width="${outer - inner}"></circle></g>`;
    }
    const large = fraction > 0.5 ? 1 : 0;
    const path = `M${polar(centre, outer, start)} A${outer} ${outer} 0 ${large} 1 ${polar(centre, outer, cursor)} L${polar(centre, inner, cursor)} A${inner} ${inner} 0 ${large} 0 ${polar(centre, inner, start)} Z`;
    return `<g class="insight-slice-group">${label}<path d="${path}" fill="${series}" class="insight-slice"></path></g>`;
  }).join("");
  const legend = slices.map((slice, index) => `<li><span class="insight-legend-swatch" style="background: var(--series-${(index % 8) + 1})"></span><span class="insight-legend-label" title="${escapeAttr(slice.label)}">${escapeHtml(slice.label)}</span><span class="insight-legend-value">${escapeHtml(slice.value)}</span><span class="insight-legend-share">${total ? (slice.value / total * 100).toFixed(1) : "0.0"}%</span></li>`).join("");
  const body = slices.length
    ? `<div class="insight-pie-layout"><svg class="insight-pie-chart" viewBox="0 0 ${size} ${size}" role="img" aria-label="${escapeAttr(`${title} — pie chart of ${slices.length} slices totalling ${total}. The same figures are listed in the chart data table below.`)}">${wedges}<text x="${centre}" y="${centre - 2}" text-anchor="middle" class="insight-pie-total">${escapeHtml(total)}</text><text x="${centre}" y="${centre + 16}" text-anchor="middle" class="insight-pie-total-label">total</text></svg><ul class="insight-legend">${legend}</ul></div>`
    : '<p class="insight-no-data">No positive numeric rows to chart.</p>';
  return chartFrame(component, "Composition", title, chartable.length, "categories", body, "",
    chartTwin(chartable, component));
}

/** A component's stable identity across re-analysis: where its tag starts in the document source. */
function componentKey(component) {
  return component.span?.startOffset ?? -1;
}

/**
 * Renders the outline against the last run's datasets. Passing `design` turns every block into a
 * selection target for the design panes; without it the markup is exactly what the read-only preview
 * has always produced, so viewing an insight is unaffected by the editing surface existing.
 *
 * <p>Blocks are keyed by source offset rather than by their index in the outline: a re-analysis can
 * split or merge the prose around an edit, which shifts every index after it, and the selection would
 * silently jump to a different component.
 */
function renderInsight(data, design = null) {
  const blocks = [];
  const stats = [];
  const isSelected = (component) => Boolean(design) && componentKey(component) === design.selected;
  const selectAttrs = (component) => (design
    ? ` data-action="select-block" data-offset="${componentKey(component)}" tabindex="0" role="button" aria-pressed="${isSelected(component)}"`
    : "");
  const selectable = (component, html) => (design
    ? `<div class="insight-block ${isSelected(component) ? "is-selected" : ""}"${selectAttrs(component)} aria-label="${escapeAttr(component.type)}"><span class="insight-block-tag">${escapeHtml(component.type)}</span>${html}</div>`
    : html);
  const flushStats = () => {
    if (!stats.length) return;
    blocks.push(`<div class="insight-kpi-row">${stats.splice(0).map((component) => `<section class="insight-stat ${isSelected(component) ? "is-selected" : ""}"${selectAttrs(component)}><span>${escapeHtml(component.props.label || "Metric")}</span><strong>${escapeHtml(evaluate(component.props.value, data.datasets))}</strong></section>`).join("")}</div>`);
  };
  (data.outline || []).forEach((component) => {
    if (component.type === "Stat") {
      stats.push(component);
      return;
    }
    if (["KpiRow", "Filter"].includes(component.type)) return;
    flushStats();
    const dataset = data.datasets?.[datasetName(component)];
    const push = (html) => blocks.push(selectable(component, html));
    if (component.type === "Prose") push(`<div class="insight-prose">${markdown(interpolate(component.props.value, data.datasets))}</div>`);
    else if (component.type === "DataTable" && dataset) push(dataTable(dataset, component));
    else if (component.type === "BarChart" && dataset) push(barChart(dataset, component));
    else if (component.type === "LineChart" && dataset) push(lineChart(dataset, component));
    else if (component.type === "PieChart" && dataset) push(pieChart(dataset, component));
    else if (component.type === "Text") push(`<p class="insight-text">${escapeHtml(evaluate(component.props.value, data.datasets))}</p>`);
    else if (["KeyValue", "LabelValue"].includes(component.type)) push(`<div class="insight-kv ${component.type === "LabelValue" ? "is-plain" : ""}"><span class="insight-kv-label">${escapeHtml(component.props.label || "Value")}</span><span class="insight-kv-value">${escapeHtml(evaluate(component.props.value, data.datasets))}</span></div>`);
    else if (["QuickTable", "LabelTable"].includes(component.type)) push(inlineTable(component, data.datasets));
    else if (component.type === "Metrics") push(metricsBlock(data));
    else if (component.type === "Status" && data.requests?.length) push(statusBlock(data.requests));
    // A chart or table whose dataset has not been fetched yet would otherwise vanish from the canvas,
    // leaving nothing to select and no hint that the document is waiting on a run.
    else if (design && CATALOG[component.type]?.needsData) push(placeholderBlock(component));
  });
  flushStats();
  const empty = design
    ? '<div class="insight-preview-empty"><h2>Empty canvas</h2><p>Pick a visual from the Visualizations pane to place it in the document.</p></div>'
    : '<div class="insight-preview-empty"><h2>No renderable components</h2><p>Add a Stat, BarChart, or DataTable component to the document.</p></div>';
  return `<div class="insight-rendered ${design ? "is-design" : ""}">${blocks.join("") || empty}</div>`;
}

/** Stands in for a data component whose dataset is missing, so it stays visible and selectable. */
function placeholderBlock(component) {
  const name = datasetName(component);
  return `<section class="insight-card insight-placeholder"><header class="insight-card-heading"><div><p class="insight-card-kicker">${escapeHtml(CATALOG[component.type].label)}</p><h2>${escapeHtml(component.props.title || name || "Unbound visual")}</h2></div></header>
    <p class="insight-no-data">${name ? `No data for <code>${escapeHtml(name)}</code> yet — run the insight.` : "Bind a dataset in the Format pane."}</p>
  </section>`;
}

// ── the design surface ──────────────────────────────────────────────────────
// A document stays the source of truth in both directions: the design panes never hold a separate
// model of the dashboard, they read the parsed outline and write back into the .rqd text. That is
// what keeps a click-built insight as diffable and reviewable as a hand-written one — the property
// this document format was chosen for (docs/dashboard-design.md §1).

/**
 * Every component the design panes can place, bind, and format. This mirrors
 * {@code InsightDocumentParser.KNOWN_PROPS}, which stays the authority on what each component
 * actually reads; a prop absent here is simply not offered visually and remains editable in Code.
 */
const CATALOG = {
  BarChart: {
    label: "Bar chart", icon: "chartBar", group: "Charts", needsData: true,
    wells: [{ prop: "x", label: "Axis", hint: "Category" }, { prop: "y", label: "Values", hint: "Number", numeric: true }],
    format: [{ prop: "title", label: "Title", kind: "text" }],
  },
  LineChart: {
    label: "Line chart", icon: "chartLine", group: "Charts", needsData: true,
    wells: [{ prop: "x", label: "Axis", hint: "Category" }, { prop: "y", label: "Values", hint: "Number", numeric: true }],
    format: [{ prop: "title", label: "Title", kind: "text" }],
  },
  PieChart: {
    label: "Pie chart", icon: "chartPie", group: "Charts", needsData: true,
    wells: [{ prop: "x", label: "Legend", hint: "Category" }, { prop: "y", label: "Values", hint: "Number", numeric: true }],
    format: [{ prop: "title", label: "Title", kind: "text" }],
  },
  DataTable: {
    label: "Table", icon: "table", group: "Charts", needsData: true,
    wells: [{ prop: "columns", label: "Columns", hint: "Any field", multi: true }],
    format: [{ prop: "title", label: "Title", kind: "text" }],
  },
  Stat: {
    label: "KPI card", icon: "kpi", group: "Cards",
    format: [{ prop: "label", label: "Label", kind: "text" }, { prop: "value", label: "Value", kind: "expr" }],
  },
  KeyValue: {
    label: "Key / value", icon: "text", group: "Cards",
    format: [{ prop: "label", label: "Label", kind: "text" }, { prop: "value", label: "Value", kind: "expr" }],
  },
  LabelValue: {
    label: "Label / value", icon: "text", group: "Cards",
    format: [{ prop: "label", label: "Label", kind: "text" }, { prop: "value", label: "Value", kind: "expr" }],
  },
  Text: {
    label: "Text", icon: "text", group: "Cards",
    format: [{ prop: "value", label: "Value", kind: "expr" }],
  },
  QuickTable: {
    label: "Quick table", icon: "table", group: "Cards",
    format: [{ prop: "title", label: "Title", kind: "text" }, { prop: "headers", label: "Headers", kind: "expr" }, { prop: "rows", label: "Rows", kind: "expr" }],
  },
  LabelTable: {
    label: "Label table", icon: "table", group: "Cards",
    format: [{ prop: "title", label: "Title", kind: "text" }, { prop: "headers", label: "Headers", kind: "expr" }, { prop: "rows", label: "Rows", kind: "expr" }],
  },
  Metrics: { label: "Run metrics", icon: "kpi", group: "Execution", format: [] },
  Status: { label: "Request status", icon: "kpi", group: "Execution", format: [] },
  // Rendered and selectable, but never offered in the picker — prose is written in Code.
  Prose: { label: "Prose", icon: "text", group: null, format: [] },
};

const PICKER_GROUPS = ["Charts", "Cards", "Execution"];

/** Props written as `{expression}`; everything else is written as `"text"`. */
const BRACED_PROPS = new Set(["data", "value", "rows", "headers", "columns"]);

const TAG_PROP = /([A-Za-z][A-Za-z0-9]*)\s*=\s*(?:\{([^{}]*)}|"([^"]*)")/g;

/** Mirrors the parser's prop regex so a locally edited tag repaints without a server round-trip. */
function parseTagProps(tag) {
  const props = {};
  for (const match of tag.matchAll(TAG_PROP)) props[match[1]] = match[2] === undefined ? match[3] : match[2];
  return props;
}

function propPattern(name) {
  return new RegExp(`\\s${name}\\s*=\\s*(?:\\{[^{}]*}|"[^"]*")`);
}

/**
 * Sets, replaces, or (with an empty value) removes one prop inside a component's raw tag text,
 * leaving every other character of that tag untouched. Regenerating the tag from its parsed props
 * would be shorter and would also rewrite the author's prop order, spacing, and quoting on every
 * click — an edit made in the Format pane should read as a one-prop diff, not a reformat.
 */
function setTagProp(tag, name, value) {
  const pattern = propPattern(name);
  if (value === null || value === undefined || value === "") return tag.replace(pattern, "");
  // The parser reads a quoted prop as `"([^"]*)"`, so an embedded quote would truncate the value.
  const written = BRACED_PROPS.has(name) ? `{${value}}` : `"${String(value).replaceAll('"', "")}"`;
  if (pattern.test(tag)) return tag.replace(pattern, ` ${name}=${written}`);
  return tag.replace(/\s*(\/?)>$/, (_match, slash) => ` ${name}=${written}${slash ? " />" : ">"}`);
}

function datasetFields(dataset) {
  if (!dataset) return [];
  return dataset.columns?.length ? dataset.columns : Object.keys(dataset.rows?.[0] || {});
}

/**
 * The tag a freshly placed component starts as. Bindings are guessed from the dataset — a category
 * on the axis and the first numeric column as the measure — so a new chart draws something real
 * immediately, the way dropping a visual in a BI tool does, rather than rendering an empty frame.
 */
function newComponentSource(type, name, dataset) {
  const fields = datasetFields(dataset);
  const numeric = fields.filter((field) => isNumericColumn(dataset?.rows || [], field));
  // An id is numeric and never a measure — plotting one produces a chart of row numbers. It is still
  // the fallback when a dataset has nothing else to sum, so the visual is never left unbound.
  const measure = numeric.find((field) => !/(^|_)id$/i.test(field)) || numeric[0] || fields[1] || fields[0] || "value";
  const category = fields.find((field) => field !== measure && !numeric.includes(field))
    || fields.find((field) => field !== measure)
    || fields[0] || "field";
  const rows = name ? `count(${name})` : "0";
  switch (type) {
    case "BarChart":
    case "LineChart":
    case "PieChart":
      return `<${type} data={${name}} x="${category}" y="${measure}" />`;
    case "DataTable":
      return `<DataTable data={${name}} />`;
    case "Stat":
      return `<Stat value={${rows}} label="${name || "Metric"}" />`;
    case "Text":
      return `<Text value={${name ? `"${name}: " + ${rows}` : '"Text"'}} />`;
    case "KeyValue":
    case "LabelValue":
      return `<${type} label="${name || "Label"}" value={${rows}} />`;
    case "QuickTable":
    case "LabelTable":
      return `<${type} rows={[["${name || "Metric"}", ${rows}]]} />`;
    default:
      return `<${type} />`;
  }
}

const STORE_KEY = "mcp.insights.workspace.v1";

// Run/save are reachable from the editor without leaving the keyboard — this page is used the way
// an IDE is, and the alternative is a mouse trip to the toolbar after every edit.
const IS_APPLE = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
const SHORTCUT_KEY = IS_APPLE ? "⌘" : "Ctrl+";

// ── status bar ──────────────────────────────────────────────────────────────

/** Compact enough for a status strip, where a full locale timestamp would crowd out everything else. */
function relativeTime(value) {
  if (!value) return "";
  const then = new Date(value).valueOf();
  if (Number.isNaN(then)) return "";
  const seconds = Math.round((Date.now() - then) / 1000);
  if (seconds < 10) return "just now";
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.round(hours / 24)}d ago`;
}

/** Elapsed time for a live counter — always in seconds, so the readout does not change units as it runs. */
function formatElapsed(milliseconds) {
  const seconds = Math.max(0, milliseconds) / 1000;
  if (seconds < 60) return `${seconds.toFixed(1)}s`;
  return `${Math.floor(seconds / 60)}m ${String(Math.floor(seconds % 60)).padStart(2, "0")}s`;
}

/** A settled duration, which unlike the live counter should read in whatever unit is meaningful. */
function formatDuration(milliseconds) {
  const value = Math.max(0, milliseconds);
  if (value < 1000) return `${Math.round(value)} ms`;
  return formatElapsed(value);
}

function plural(count, noun) {
  return `${count} ${noun}${count === 1 ? "" : "s"}`;
}

/**
 * Derives the whole status bar from workspace state — every segment's value *and* its tone, which is
 * the part worth getting right: a bar that reports "clean" while the document has errors, or "saved"
 * while there are unsaved edits, is worse than no bar at all.
 *
 * <p>Pure, and returns descriptors rather than markup, so those conditional rules can be asserted
 * directly. A segment returning null is omitted — the bar only ever states what actually applies.
 *
 * @returns {Array<{key: string, icon: string, label: string, value: string, tone: string,
 *   title?: string, action?: string, live?: boolean}>} in display order; `tone` is one of
 *   ok / warn / error / active / muted.
 */
function statusSegments(state) {
  const datasets = state.data?.datasets || {};
  const datasetNames = Object.keys(datasets);
  const rowCount = datasetNames.reduce((total, name) => total + (datasets[name].rows?.length || 0), 0);
  const requests = state.data?.requests || [];
  const failed = requests.filter((request) => !request.success).length;
  const cached = requests.filter((request) => request.cached).length;
  const durationMs = requests.reduce((total, request) => total + (request.cached ? 0 : request.durationMs || 0), 0);
  const diagnostics = state.analysis?.diagnostics || [];
  const errors = diagnostics.filter((item) => item.severity === "ERROR").length;
  const warnings = diagnostics.filter((item) => item.severity === "WARNING").length;
  const params = state.analysis?.params || state.data?.params || [];
  const edited = Boolean(state.data && state.runRevision !== null && state.changeRevision !== state.runRevision);

  const segments = [];

  // Run — the headline, and the only segment that is always present.
  if (state.running) {
    segments.push({ key: "run", icon: "play", label: "Run", value: "Running", tone: "active", live: true });
  } else if (state.error) {
    segments.push({ key: "run", icon: "alert", label: "Run", value: "Failed", tone: "error", title: state.error });
  } else if (!state.data) {
    segments.push({ key: "run", icon: "play", label: "Run", value: "Not run", tone: "muted",
      title: "This document has not been run yet." });
  } else if (state.dataFresh) {
    segments.push({ key: "run", icon: "check", label: "Run", value: relativeTime(state.lastRunAt) || "Just ran",
      tone: "ok", title: state.lastRunAt ? `Ran ${formatDate(state.lastRunAt)}` : "" });
  } else {
    // A restored snapshot can be arbitrarily old, so it is never reported as a fresh result.
    segments.push({ key: "run", icon: "file", label: "Saved result", value: relativeTime(state.lastRunAt) || "stored",
      tone: "muted", title: state.lastRunAt ? `Ran ${formatDate(state.lastRunAt)}` : "" });
  }

  // Only meaningful once there is a result to have gone stale.
  if (edited && state.data) {
    segments.push({ key: "freshness", icon: "alert", label: "Result", value: "Stale", tone: "warn",
      title: "The document has changed since this result was produced — run it again." });
  }

  if (state.data) {
    segments.push({ key: "data", icon: "table", label: "Data",
      value: datasetNames.length ? `${plural(datasetNames.length, "dataset")} · ${rowCount.toLocaleString()} rows` : "No datasets",
      tone: datasetNames.length ? (rowCount ? "ok" : "warn") : "muted",
      title: datasetNames.length && !rowCount ? "Every dataset came back empty." : datasetNames.join(", ") });
  }

  if (requests.length) {
    // A fully cached run issued no HTTP at all; reporting it as "0 ms" would read as a very fast
    // call rather than as no call, and hide the reason the numbers came back instantly.
    const allCached = cached === requests.length;
    segments.push({ key: "requests", icon: "globe", label: "Requests",
      value: failed
        ? `${plural(failed, "failure")} of ${requests.length}`
        : allCached
          ? `${requests.length} · cached`
          : `${requests.length} · ${formatDuration(durationMs)}`,
      tone: failed ? "error" : "ok",
      title: cached && !allCached ? `${cached} of ${requests.length} served from cache` : "" });
  }

  segments.push({ key: "diagnostics", icon: errors ? "alert" : "check", label: "Checks",
    value: errors || warnings
      ? [errors ? plural(errors, "error") : "", warnings ? plural(warnings, "warning") : ""].filter(Boolean).join(" · ")
      : "Clean",
    tone: errors ? "error" : warnings ? "warn" : "ok",
    action: errors || warnings ? "show-diagnostics" : undefined,
    title: errors || warnings ? "Open the document to see them" : "The analyzer found nothing to report." });

  segments.push({ key: "document", icon: "file", label: "Document",
    value: !state.activeId ? "Unsaved draft" : edited ? "Unsaved changes" : "Saved",
    tone: state.activeId && !edited ? "ok" : "warn",
    action: "save-insight",
    title: state.activeId ? "" : "This insight has never been saved." });

  // Which app bare request names resolve against — a wrong one is the usual cause of RQL101.
  const app = state.connections.find((connection) => connection.id === state.connectionId);
  segments.push({ key: "app", icon: "puzzle", label: "App",
    value: app ? app.name : state.connectionId ? "Unknown app" : "All connected apps",
    tone: state.connectionId && !app ? "error" : app && app.status !== "CONNECTED" ? "warn" : "muted",
    title: app && app.status !== "CONNECTED" ? `This app is ${app.status.toLowerCase()}.` : "" });

  if (params.length) {
    segments.push({ key: "params", icon: "settings", label: "Parameters",
      value: String(params.length), tone: "muted",
      title: params.map((param) => param.name).join(", ") });
  }

  // Only in Design mode, where a selection is what the Format pane is acting on.
  if (state.mode === "design") {
    const selected = state.selected === null
      ? null
      : (state.outline || []).find((component) => componentKey(component) === state.selected);
    const bound = selected ? datasetName(selected) : null;
    segments.push({ key: "selection", icon: "wand", label: "Selected",
      value: selected ? (bound ? `${selected.type} · ${bound}` : selected.type) : "Nothing",
      tone: selected ? "active" : "muted",
      title: selected ? "" : "Click a visual on the canvas to format it." });
  }

  return segments;
}

const MODES = ["view", "design", "code"];
const MODE_LABELS = { view: "View", design: "Design", code: "Code" };
const MODE_ICONS = { view: "file", design: "wand", code: "hash" };
const MODE_HINTS = {
  view: "Read the insight",
  design: "Place visuals and bind fields on the canvas",
  code: "Edit the .rqd document directly",
};

/**
 * Which insight was open and which mode it was in. Only these two preferences are local — the run
 * result itself lives on the insight, so it follows the document across browsers.
 */
function loadStore() {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORE_KEY));
    return {
      // Validated on read: this id is interpolated into a URL and into a data-id attribute.
      lastInsightId: typeof parsed?.lastInsightId === "string" && parsed.lastInsightId.length <= 64
        ? parsed.lastInsightId
        : null,
      // `editing` is the pre-Design-mode shape of this preference; a workspace saved by the older
      // build still opens on the editor rather than silently dropping to the read-only view.
      mode: MODES.includes(parsed?.mode) ? parsed.mode : (parsed?.editing === true ? "code" : "view"),
    };
  } catch {
    // The workspace stays fully usable when browser storage is unavailable.
  }
  return { lastInsightId: null, mode: "view" };
}

function saveStore(store) {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify({
      lastInsightId: store.lastInsightId || null,
      mode: MODES.includes(store.mode) ? store.mode : "view",
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
    // When the in-flight run started, for the elapsed readout. Null whenever nothing is running.
    runStartedAt: null,
    error: "",
    saved: [],
    activeId: null,
    name: "Untitled insight",
    saving: false,
    // The document as last saved, so "edited since this run" is a plain comparison.
    savedSource: "",
    // Monotonic local edits let a run be marked stale for source, connection, and parameter changes,
    // including unsaved drafts where there is no savedSource baseline to compare against.
    changeRevision: 0,
    runRevision: null,
    mode: "view",
    // The parsed component tree the canvas draws and the design panes edit, kept current by analyze()
    // whether or not the document has been run.
    outline: [],
    // Selected component, identified by the source offset its tag starts at (see componentKey).
    selected: null,
    lastRunAt: null,
    // True only for a run in this session; a hydrated snapshot is labelled differently.
    dataFresh: false,
    runNote: "",
    opening: false,
  };
  const abort = new AbortController();
  let analysisTimer = 0;
  let elapsedTimer = 0;
  let analysisSequence = 0;
  let runSequence = 0;

  function invalidateRun() {
    runSequence += 1;
    state.running = false;
    state.runStartedAt = null;
  }

  function invalidateAnalysis() {
    analysisSequence += 1;
    clearTimeout(analysisTimer);
  }

  /**
   * Advances every elapsed-time readout by writing into the existing nodes. Deliberately not a
   * render(): repainting the whole page ten times a second would throw away focus and the caret on
   * every tick, and the only thing that actually changed is one string.
   */
  function tickElapsed() {
    clearInterval(elapsedTimer);
    if (!state.running || !state.runStartedAt) return;
    const paint = () => {
      const readouts = outlet.querySelectorAll("[data-run-elapsed]");
      if (!readouts.length) {
        clearInterval(elapsedTimer);
        return;
      }
      const text = formatElapsed(Date.now() - state.runStartedAt);
      readouts.forEach((node) => { node.textContent = text; });
    };
    paint();
    elapsedTimer = window.setInterval(paint, 100);
  }

  function diagnostics() {
    const items = state.analysis?.diagnostics || [];
    if (!items.length) return '<p class="insight-analysis-ok">Document checks clean.</p>';
    return `<div class="insight-diagnostics">${items.slice(0, 4).map((item) => `<p class="is-${item.severity.toLowerCase()}">${icon("alert", 14)}<b>${escapeHtml(item.code)}</b> ${escapeHtml(item.message)}</p>`).join("")}${items.length > 4 ? `<span>+${items.length - 4} more checks</span>` : ""}</div>`;
  }

  function parameterControls() {
    const params = state.analysis?.params || state.data?.params || [];
    if (!params.length) return "";
    // Labelled as a group: without it the inputs read as loose fields between the toolbar and the
    // workspace, with nothing saying they are the document's declared parameters.
    return `<div class="insight-params" role="group" aria-label="Insight parameters"><span class="insight-params-kicker">Parameters</span>${params.map((param) => `<label><span>${escapeHtml(param.name)}</span><input data-param="${escapeAttr(param.name)}" type="${param.type === "number" ? "number" : "text"}" value="${escapeAttr(state.parameters[param.name] ?? param.defaultValue ?? "")}"></label>`).join("")}</div>`;
  }

  /** The result panel is the largest region on the page; when empty it should offer the next step. */
  function emptyPreview() {
    if (state.opening) return '<div class="insight-preview-empty"><h2>Opening…</h2></div>';
    return `<div class="insight-preview-empty">${icon("file", 24)}<h2>Ready for a query</h2><p>Run the document to fetch data from the connected apps and render its datasets.</p>
      <button class="insight-run-button" type="button" data-action="run-insight" ${state.running ? "disabled" : ""}>${icon("play", 15)} Run insight</button>
      <p class="insight-empty-hint">or press ${escapeHtml(SHORTCUT_KEY)}Enter</p></div>`;
  }

  /** When the result was produced, and whether it can still be trusted to match the document. */
  function previewStatus() {
    if (!state.data) return "Run to fetch API data";
    const when = state.lastRunAt ? formatDate(state.lastRunAt) : "";
    const edited = state.data && state.runRevision !== null && state.changeRevision !== state.runRevision
      ? " · Document edited since this run"
      : "";
    if (state.dataFresh) return `${when ? `Ran ${escapeHtml(when)}` : "Last successful run"}${edited}`;
    return `<span class="insight-stale-chip">Saved result</span>${when ? ` · ran ${escapeHtml(when)}` : ""}${edited}`;
  }

  /**
   * The status strip. Segments come from statusSegments(); this only turns them into markup, so the
   * rules about what a state means live in one testable place rather than inside a template.
   */
  function statusBar() {
    const segments = statusSegments(state);
    return `<div class="insight-status-bar" role="status" aria-label="Insight status">${segments.map((segment) => {
      const body = `${icon(segment.icon, 12)}<span class="insight-status-label">${escapeHtml(segment.label)}</span><span class="insight-status-value">${escapeHtml(segment.value)}${segment.live ? ' <span data-run-elapsed class="insight-status-elapsed">0.0s</span>' : ""}</span>`;
      const attrs = `class="insight-status-segment is-${escapeAttr(segment.tone)}"${segment.title ? ` title="${escapeAttr(segment.title)}"` : ""}`;
      // A segment with somewhere to go is a button; the rest are plain readouts and must not be
      // presented as though they could be pressed.
      return segment.action
        ? `<button type="button" ${attrs} data-action="${escapeAttr(segment.action)}">${body}</button>`
        : `<span ${attrs}>${body}</span>`;
    }).join("")}</div>`;
  }

  /**
   * Run progress. Deliberately indeterminate: a run's request count is not knowable up front — one
   * `lookup` stage issues a request per row — so a filling bar would be inventing a denominator.
   * What can be reported honestly is elapsed time and what the last run of this document cost.
   */
  function runProgress() {
    if (!state.running) return "";
    const requests = state.data?.requests || [];
    const previous = requests.length
      ? `Last run took ${formatDuration(requests.reduce((total, request) => total + (request.cached ? 0 : request.durationMs || 0), 0))} over ${plural(requests.length, "request")}.`
      : "First run of this document.";
    return `<div class="insight-progress">
      <div class="insight-progress-track" role="progressbar" aria-label="Run in progress" aria-describedby="insight-progress-note"><span class="insight-progress-bar"></span></div>
      <p class="insight-progress-note" id="insight-progress-note">Fetching from the connected apps… <span data-run-elapsed>0.0s</span> · ${escapeHtml(previous)}</p>
    </div>`;
  }

  /** Errors live in the editor footer, which is hidden by default — surface a count regardless. */
  function hiddenDiagnosticsNote() {
    if (state.mode === "code") return "";
    const errors = (state.analysis?.diagnostics || []).filter((item) => item.severity === "ERROR");
    if (!errors.length) return "";
    return `<button class="insight-run-error insight-run-error-action" type="button" data-action="show-diagnostics">${icon("alert", 15)} <span>${errors.length} issue${errors.length === 1 ? "" : "s"} in the document — open Code to see ${errors.length === 1 ? "it" : "them"}.</span></button>`;
  }

  function editorPanel() {
    return `<section class="insight-editor-panel"><header><span>insight.rqd</span><small>Markdown · RQL · components</small></header><textarea id="insight-source" data-focus-key="source" class="insight-plain-editor" spellcheck="false">${escapeHtml(state.source)}</textarea><footer>${diagnostics()}</footer></section>`;
  }

  // ── design panes ──────────────────────────────────────────────────────────

  function selectedComponent() {
    if (state.selected === null) return null;
    return state.outline.find((component) => componentKey(component) === state.selected) || null;
  }

  function datasets() {
    return state.data?.datasets || {};
  }

  function visualPicker() {
    const bindable = Object.keys(datasets()).length > 0;
    return PICKER_GROUPS.map((group) => {
      const types = Object.keys(CATALOG).filter((type) => CATALOG[type].group === group);
      return `<div class="insight-rail-group"><span class="insight-rail-kicker">${escapeHtml(group)}</span>
        <div class="insight-visual-grid">${types.map((type) => {
          const spec = CATALOG[type];
          // A data component placed before any dataset exists could only be bound to a name that is
          // not in the document yet, so it would land as an RQI101 error. Offer it once there is
          // something to bind it to.
          const blocked = spec.needsData && !bindable;
          const hint = blocked ? `${spec.label} — run the insight first, so there is a dataset to bind` : `Add a ${spec.label.toLowerCase()}`;
          return `<button class="insight-visual-tile" type="button" data-action="add-component" data-type="${escapeAttr(type)}" ${blocked ? "disabled" : ""} title="${escapeAttr(hint)}">${icon(spec.icon, 17)}<span>${escapeHtml(spec.label)}</span></button>`;
        }).join("")}</div></div>`;
    }).join("");
  }

  /** One field binding. A select carries the whole interaction; drag-and-drop is the shortcut. */
  function wellControl(component, well, dataset) {
    const fields = datasetFields(dataset);
    const raw = component.props[well.prop];
    if (well.multi) {
      const chosen = raw ? parseColumnSpecs(raw).map((column) => column.field) : [];
      const remaining = fields.filter((field) => !chosen.includes(field));
      return `<div class="insight-well" data-well="${escapeAttr(well.prop)}">
        <span class="insight-well-label">${escapeHtml(well.label)}</span>
        <div class="insight-well-chips">${chosen.length
          ? chosen.map((field) => `<span class="insight-well-chip">${escapeHtml(field)}<button type="button" data-action="drop-field" data-well="${escapeAttr(well.prop)}" data-field="${escapeAttr(field)}" aria-label="Remove ${escapeAttr(field)}">${icon("close", 11)}</button></span>`).join("")
          : '<span class="insight-well-placeholder">Every field</span>'}</div>
        <select data-focus-key="well-${escapeAttr(well.prop)}" data-well-select="${escapeAttr(well.prop)}"><option value="">${chosen.length ? "Add a field…" : "Choose fields…"}</option>${remaining.map((field) => `<option value="${escapeAttr(field)}">${escapeHtml(field)}</option>`).join("")}</select>
      </div>`;
    }
    return `<div class="insight-well" data-well="${escapeAttr(well.prop)}">
      <span class="insight-well-label">${escapeHtml(well.label)}</span>
      <select data-focus-key="well-${escapeAttr(well.prop)}" data-well-select="${escapeAttr(well.prop)}"><option value="">${escapeHtml(well.hint || "Field")}…</option>${fields.map((field) => `<option value="${escapeAttr(field)}" ${raw === field ? "selected" : ""}>${escapeHtml(field)}</option>`).join("")}</select>
    </div>`;
  }

  function formatPane() {
    const component = selectedComponent();
    if (!component) return '<p class="insight-rail-empty">Select a visual on the canvas to bind fields and format it.</p>';
    const spec = CATALOG[component.type];
    if (!spec) return `<p class="insight-rail-empty">&lt;${escapeHtml(component.type)}/&gt; is not a component this build knows. Fix it in Code.</p>`;
    if (component.type === "Prose") return '<p class="insight-rail-empty">Prose is written in Markdown — switch to Code to edit this block.</p>';
    const names = Object.keys(datasets());
    const bound = datasetName(component);
    const dataset = datasets()[bound];
    const nothingToConfigure = !(spec.wells || []).length && !(spec.format || []).length && !spec.needsData;
    return `<div class="insight-format">
      <div class="insight-format-head"><span title="${escapeAttr(`<${component.type}/>`)}">${icon(spec.icon, 14)} ${escapeHtml(spec.label)}</span>
        <div class="insight-format-tools">
          <button class="insight-icon-button" type="button" data-action="move-component" data-dir="-1" title="Move up" aria-label="Move up">${icon("arrowUp", 13)}</button>
          <button class="insight-icon-button" type="button" data-action="move-component" data-dir="1" title="Move down" aria-label="Move down">${icon("arrowDown", 13)}</button>
          <button class="insight-icon-button is-danger" type="button" data-action="delete-component" title="Remove from the document" aria-label="Remove from the document">${icon("trash", 13)}</button>
        </div>
      </div>
      ${spec.needsData ? `<div class="insight-well"><span class="insight-well-label">Data</span><select data-focus-key="well-data" data-well-select="data"><option value="">Choose a dataset…</option>${names.map((name) => `<option value="${escapeAttr(name)}" ${name === bound ? "selected" : ""}>${escapeHtml(name)}</option>`).join("")}</select></div>` : ""}
      ${(spec.wells || []).map((well) => wellControl(component, well, dataset)).join("")}
      ${(spec.format || []).map((field) => `<label class="insight-format-field"><span>${escapeHtml(field.label)}</span><input data-focus-key="format-${escapeAttr(field.prop)}" data-format="${escapeAttr(field.prop)}" class="${field.kind === "expr" ? "mono" : ""}" value="${escapeAttr(component.props[field.prop] ?? "")}" placeholder="${escapeAttr(field.kind === "expr" ? "expression" : "text")}"></label>`).join("")}
      ${spec.needsData && !dataset ? '<p class="insight-rail-note">Nothing plotted yet — bind a dataset, then run.</p>' : ""}
      ${nothingToConfigure ? '<p class="insight-rail-note">This component reads no props; it renders from the run itself.</p>' : ""}
    </div>`;
  }

  function fieldsPane() {
    const all = datasets();
    const names = Object.keys(all);
    if (!names.length) return '<p class="insight-rail-empty">Run the insight to load its datasets, then drag fields onto a visual.</p>';
    return names.map((name) => {
      const dataset = all[name];
      return `<details class="insight-field-group" open><summary><span>${escapeHtml(name)}</span><small>${dataset.rows.length} rows</small></summary>
        <ul>${datasetFields(dataset).map((field) => {
          const numeric = isNumericColumn(dataset.rows, field);
          return `<li><button class="insight-field-chip" type="button" draggable="true" data-action="assign-field" data-field="${escapeAttr(field)}" data-dataset="${escapeAttr(name)}" title="${escapeAttr(`${name}.${field} — drag onto a well, or click to bind it to the selected visual`)}">${icon(numeric ? "hash" : "text", 12)}<span>${escapeHtml(field)}</span></button></li>`;
        }).join("")}</ul></details>`;
    }).join("");
  }

  /**
   * Three stacked panes. Format is its own section rather than a continuation of the Visualizations
   * pane: stacked in one scroller, the picker's grid pushed the wells of the selected visual below
   * the fold, so the half being actively used was the half you had to scroll to find.
   */
  function designRail() {
    return `<aside class="insight-rail" aria-label="Design panes">
      <section class="insight-rail-section is-picker"><header>Visualizations</header><div class="insight-rail-body">${visualPicker()}</div></section>
      <section class="insight-rail-section is-format"><header>Format</header><div class="insight-rail-body">${formatPane()}</div></section>
      <section class="insight-rail-section is-fields"><header>Fields</header><div class="insight-rail-body">${fieldsPane()}</div></section>
    </aside>`;
  }

  /**
   * What the result panel shows. Design mode always draws the outline — including components whose
   * dataset has not been fetched — because an empty canvas cannot be edited; the read-only modes keep
   * offering the Run button until there is a result, which is the more useful next step there.
   */
  function canvas() {
    const data = { datasets: datasets(), requests: state.data?.requests || [], outline: state.outline };
    if (state.mode === "design") return renderInsight(data, { selected: state.selected });
    return state.data ? renderInsight(data) : emptyPreview();
  }

  // ── focus across repaints ─────────────────────────────────────────────────
  // render() replaces the whole page, so anything being typed into loses focus and its caret. Keying
  // controls by a stable name and restoring after the repaint keeps the editor, the field wells and
  // the format inputs usable — the debounced analyze() re-renders mid-typing on every one of them.

  function captureFocus() {
    const active = document.activeElement;
    if (!active || !outlet.contains(active) || !active.dataset?.focusKey) return null;
    const capture = { key: active.dataset.focusKey };
    if (typeof active.selectionStart === "number") {
      capture.start = active.selectionStart;
      capture.end = active.selectionEnd;
      capture.scroll = active.scrollTop;
    }
    return capture;
  }

  function restoreFocus(capture) {
    if (!capture) return;
    const element = outlet.querySelector(`[data-focus-key="${CSS.escape(capture.key)}"]`);
    if (!element) return;
    element.focus({ preventScroll: true });
    if (typeof capture.start === "number" && typeof element.setSelectionRange === "function") {
      element.setSelectionRange(capture.start, capture.end);
      element.scrollTop = capture.scroll;
    }
  }

  function render() {
    const usable = state.connections.filter((connection) => connection.status === "CONNECTED");
    const focus = captureFocus();
    outlet.innerHTML = `<section class="insight-page" aria-labelledby="insight-page-title">
      <header class="insight-page-header"><div><p class="eyebrow">${icon("file", 14)} Insight workspace</p><h1 id="insight-page-title">Insights</h1><p>Build the view by hand or in the document — both edit the same insight. One insight can read from several connected apps.</p></div>
        <div class="insight-actions">
          <label class="insight-name-field"><span>Name</span><input id="insight-name" value="${escapeAttr(state.name)}"></label>
          <label class="insight-connection-picker"><span>Default app</span><select id="insight-connection"><option value="">All connected apps</option>${usable.map((connection) => `<option value="${escapeAttr(connection.id)}" ${state.connectionId === connection.id ? "selected" : ""}>${escapeHtml(connection.name)}</option>`).join("")}</select></label>
          <div class="insight-mode-switch" role="group" aria-label="Editor mode">
            ${MODES.map((mode) => `<button class="insight-mode-button ${state.mode === mode ? "is-active" : ""}" type="button" data-action="set-mode" data-mode="${mode}" aria-pressed="${state.mode === mode}" title="${escapeAttr(MODE_HINTS[mode])}">${icon(MODE_ICONS[mode], 14)} ${escapeHtml(MODE_LABELS[mode])}</button>`).join("")}
          </div>
          <div class="insight-button-group">
            <button class="btn" type="button" data-action="save-insight" ${state.saving || state.running ? "disabled" : ""} title="Save (${SHORTCUT_KEY}S)">${icon("download", 15)} ${state.saving ? "Saving…" : "Save"}</button>
            <button class="insight-run-button" type="button" data-action="run-insight" ${state.running ? "disabled" : ""} title="Run insight (${SHORTCUT_KEY}Enter)">${state.running ? '<span class="insight-run-spinner" aria-hidden="true"></span>' : icon("play", 15)} ${state.running ? "Running…" : "Run insight"}</button>
          </div>
        </div>
      </header>
      ${!usable.length ? '<div class="insight-connection-note">Import and connect an API collection on Connections to run an insight.</div>' : ""}
      ${hiddenDiagnosticsNote()}
      ${parameterControls()}
      <div class="insight-workspace ${state.mode === "code" ? "is-editing" : ""} ${state.mode === "design" ? "is-design" : ""}">
        <nav class="insight-library" aria-label="Saved insights"><header><span>Saved insights</span><button class="btn btn-sm" type="button" data-action="new-insight">New</button></header>${state.saved.length ? `<ul>${state.saved.map((insight) => `<li><button class="insight-library-item ${insight.id === state.activeId ? "is-active" : ""}" type="button" data-action="open-insight" data-id="${escapeAttr(insight.id)}"><strong>${escapeHtml(insight.name)}</strong>${insight.description ? `<small>${escapeHtml(insight.description)}</small>` : ""}</button><button class="insight-library-delete" type="button" data-action="delete-insight" data-id="${escapeAttr(insight.id)}" aria-label="Delete ${escapeAttr(insight.name)}">${icon("trash", 13)}</button></li>`).join("")}</ul>` : '<p class="insight-library-empty">Nothing saved yet. Build one, then press Save.</p>'}</nav>
        ${state.mode === "code" ? editorPanel() : ""}
        <section class="insight-preview-panel ${state.running ? "is-running" : ""}" aria-busy="${state.running}"><header><span>${state.mode === "design" ? "Canvas" : "Live insight"}</span><small aria-live="polite">${state.running ? "Running…" : previewStatus()}</small></header><div class="insight-preview-body">${runProgress()}${state.error ? `<div class="insight-run-error">${icon("alert", 15)} <span>${escapeHtml(state.error)}</span><button class="insight-error-dismiss" type="button" data-action="dismiss-banner" aria-label="Dismiss error">${icon("close", 13)}</button></div>` : ""}${state.runNote ? `<p class="insight-run-note">${escapeHtml(state.runNote)}</p>` : ""}${canvas()}</div></section>
        ${state.mode === "design" ? designRail() : ""}
      </div>
      ${statusBar()}
    </section>`;
    restoreFocus(focus);
    tickElapsed();
  }

  async function analyze() {
    const sequence = ++analysisSequence;
    const source = state.source;
    const connectionId = state.connectionId || undefined;
    try {
      const analysis = await api.analyzeInsight({
        source,
        connectionId,
      });
      if (sequence !== analysisSequence) return;
      state.analysis = analysis;
      (state.analysis.params || []).forEach((param) => {
        if (!(param.name in state.parameters)) state.parameters[param.name] = param.defaultValue;
      });
      // The parse is authoritative over anything the design panes patched in locally: it re-validates
      // every binding and re-computes the spans the next edit will splice against.
      state.outline = state.analysis.outline || [];
    } catch (error) {
      if (sequence !== analysisSequence) return;
      state.analysis = {
        diagnostics: [{ severity: "ERROR", code: "RQI500", message: message(error, "Analysis failed") }],
        params: [],
        outline: [],
      };
      // The outline is deliberately left as-is — a failed analysis should not blank the canvas.
    }
    render();
  }

  function scheduleAnalyze() {
    invalidateAnalysis();
    analysisTimer = window.setTimeout(analyze, 320);
  }

  // ── document mutations ────────────────────────────────────────────────────
  // Every design gesture ends up here, editing the .rqd text. Nothing else in the page holds a model
  // of the dashboard, so Design and Code can never disagree about what the insight is.

  /**
   * Replaces a range of the document and keeps the outline's spans pointing at the same components.
   * Without the shift, a second edit in the same tick — or a click on a block below the one just
   * changed — would splice at a stale offset and corrupt the tag it landed in.
   */
  function spliceSource(start, end, text) {
    const delta = text.length - (end - start);
    state.source = state.source.slice(0, start) + text + state.source.slice(end);
    if (!delta) return;
    state.outline.forEach((component) => {
      if (!component.span || component.span.startOffset < end) return;
      component.span = {
        ...component.span,
        startOffset: component.span.startOffset + delta,
        endOffset: component.span.endOffset + delta,
      };
    });
    if (state.selected !== null && state.selected >= end) state.selected += delta;
  }

  /** Applies prop changes to the selected component's tag and repaints without waiting on the server. */
  function editComponent(component, changes) {
    if (!component?.span) return;
    const { startOffset, endOffset } = component.span;
    let tag = state.source.slice(startOffset, endOffset);
    for (const [name, value] of Object.entries(changes)) tag = setTagProp(tag, name, value);
    spliceSource(startOffset, endOffset, tag);
    component.props = parseTagProps(tag);
    component.span = { ...component.span, endOffset: startOffset + tag.length };
    render();
    scheduleAnalyze();
  }

  /**
   * A structural change shifts or reorders spans the local outline can no longer describe, so the
   * parse is refreshed immediately rather than on the debounce — the canvas would otherwise sit on a
   * stale tree for a third of a second and accept clicks against it.
   */
  async function restructure() {
    clearTimeout(analysisTimer);
    await analyze();
  }

  async function addComponent(type) {
    const names = Object.keys(datasets());
    const selected = selectedComponent();
    // A new visual inherits the selected one's dataset when it has one, so building a second view of
    // the same data does not start by re-picking it.
    const bound = selected ? datasetName(selected) : null;
    const name = names.includes(bound) ? bound : names[0];
    const tag = newComponentSource(type, name, datasets()[name]);
    // Placed after the selection, which is where a reader would expect the next visual to appear;
    // with nothing selected it lands at the end of the document.
    const anchor = selected?.span ? selected.span.endOffset : state.source.length;
    const text = `\n\n${tag}\n`;
    spliceSource(anchor, anchor, text);
    state.selected = anchor + 2;
    await restructure();
  }

  async function deleteComponent(component) {
    if (!component?.span) return;
    const { startOffset, endOffset } = component.span;
    // The newline the tag sat on goes with it, or removing visuals leaves a widening run of blanks.
    const end = state.source[endOffset] === "\n" ? endOffset + 1 : endOffset;
    spliceSource(startOffset, end, "");
    state.selected = null;
    await restructure();
  }

  /**
   * Swaps a component with its neighbour in document order. Prose is stepped over rather than
   * swapped through: moving a chart past a paragraph would silently re-caption both.
   */
  async function moveComponent(component, direction) {
    // KpiRow is a container whose parser span covers only its opening tag. Treating that opening tag
    // as a movable leaf would strand its children or closing tag. Cross-content moves are also unsafe:
    // prose between two visuals is authored in that order, so leave the move disabled instead of
    // silently changing which visual the prose introduces.
    const movable = state.outline.filter((item) => item.type !== "Prose" && item.type !== "KpiRow" && item.span);
    const neighbour = movable[movable.indexOf(component) + direction];
    if (!neighbour) return;
    const [first, second] = component.span.startOffset < neighbour.span.startOffset
      ? [component, neighbour]
      : [neighbour, component];
    const firstText = state.source.slice(first.span.startOffset, first.span.endOffset);
    const secondText = state.source.slice(second.span.startOffset, second.span.endOffset);
    const between = state.source.slice(first.span.endOffset, second.span.startOffset);
    if (between.trim()) return;
    spliceSource(first.span.startOffset, second.span.endOffset, secondText + between + firstText);
    state.selected = component === first
      ? first.span.startOffset + secondText.length + between.length
      : first.span.startOffset;
    await restructure();
  }

  /** Binds a field to a well, rebinding the visual's dataset first when the field comes from another. */
  function assignField(well, field, fromDataset) {
    const component = selectedComponent();
    const spec = component && CATALOG[component.type];
    if (!component || !spec) return;
    const changes = {};
    if (fromDataset && CATALOG[component.type].needsData && datasetName(component) !== fromDataset) {
      changes.data = fromDataset;
    }
    const definition = (spec.wells || []).find((item) => item.prop === well);
    if (definition?.multi) {
      const current = component.props[well] ? parseColumnSpecs(component.props[well]).map((column) => column.field) : [];
      if (current.includes(field)) return;
      changes[well] = `[${[...current, field].map((name) => `"${name}"`).join(", ")}]`;
    } else {
      changes[well] = field;
    }
    editComponent(component, changes);
  }

  /** Click-to-bind: fills the first empty well, else replaces the last one. */
  function assignToFirstFreeWell(field, fromDataset) {
    const component = selectedComponent();
    const wells = component && CATALOG[component.type]?.wells;
    if (!wells?.length) return false;
    const target = wells.find((well) => !component.props[well.prop]) || wells[wells.length - 1];
    assignField(target.prop, field, fromDataset);
    return true;
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
    invalidateRun();
    invalidateAnalysis();
    const insight = await api.getInsight(id);
    state.activeId = insight.id;
    state.name = insight.name;
    state.source = insight.source;
    state.savedSource = insight.source;
    state.connectionId = insight.connectionId || "";
    state.data = insight.lastRun || null;
    // Seeded from the stored run so the canvas has something to draw before analyze() returns; a
    // selection from the previous document would point into offsets that no longer mean anything.
    state.outline = insight.lastRun?.outline || [];
    state.selected = null;
    state.lastRunAt = insight.lastRunAt || null;
    state.changeRevision = 0;
    state.runRevision = insight.lastRun ? 0 : null;
    state.dataFresh = false;
    state.runNote = "";
    state.error = "";
    // Cleared so the next analyze() re-seeds defaults from this document instead of leaking
    // parameter names from the previously open one.
    state.parameters = {};
    saveStore({ lastInsightId: insight.id, mode: state.mode });
  }

  /** Last insight worked on, else the most recently updated, else the empty new-document state. */
  async function restoreLastOpened() {
    const stored = loadStore();
    state.mode = stored.mode;
    const wanted = state.saved.find((item) => item.id === stored.lastInsightId) || state.saved[0];
    if (!wanted) {
      saveStore({ lastInsightId: null, mode: state.mode });
      return;
    }
    try {
      await openInsight(wanted.id);
    } catch {
      // Deleted from another browser: fall back to the empty state rather than an error.
      saveStore({ lastInsightId: null, mode: state.mode });
    }
  }

  async function runInsight() {
    if (state.running) return;
    const sequence = ++runSequence;
    const source = state.source;
    const revision = state.changeRevision;
    state.running = true;
    state.error = "";
    state.runStartedAt = Date.now();
    render();
    try {
      const payload = {
        source,
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
      if (sequence !== runSequence) return;
      state.runRevision = revision;
      state.dataFresh = true;
      if (state.changeRevision === revision) {
        state.analysis = { ...state.analysis, diagnostics: state.data.diagnostics, params: state.data.params, outline: state.data.outline };
        state.outline = state.data.outline || [];
      }
    } catch (error) {
      if (sequence !== runSequence) return;
      state.error = message(error, "The insight could not be loaded.");
    } finally {
      if (sequence !== runSequence) return;
      state.running = false;
      state.runStartedAt = null;
      render();
    }
  }

  async function saveInsight() {
    if (state.saving || state.running) return;
    state.saving = true;
    render();
    try {
      const payload = { name: state.name, source: state.source, connectionId: state.connectionId || null };
      const wasNew = !state.activeId;
      const stored = state.activeId ? await api.updateInsight(state.activeId, payload) : await api.createInsight(payload);
      state.activeId = stored.id;
      state.savedSource = state.source;
      state.saved = await api.listInsights();
      saveStore({ lastInsightId: stored.id, mode: state.mode });
      // A draft's result was computed before the insight had an id, so nothing was stored for it.
      if (wasNew && state.data) state.runNote = "Run again to save this result with the insight.";
    } catch (error) {
      state.error = message(error, "Save failed");
    } finally {
      state.saving = false;
      render();
    }
  }

  on(outlet, "click", "[data-action]", async (_event, target) => {
    const { action, id } = target.dataset;
    if (action === "run-insight") {
      await runInsight();
    } else if (action === "set-mode") {
      const { mode } = target.dataset;
      if (!MODES.includes(mode) || mode === state.mode) return;
      state.mode = mode;
      saveStore({ lastInsightId: state.activeId, mode: state.mode });
      render();
      // The editor is the reason Code was pressed — put the caret in it rather than leaving focus
      // on a button that just moved.
      if (mode === "code") outlet.querySelector("#insight-source")?.focus();
    } else if (action === "select-block") {
      const offset = Number(target.dataset.offset);
      state.selected = Number.isFinite(offset) && offset !== state.selected ? offset : null;
      render();
    } else if (action === "add-component") {
      await addComponent(target.dataset.type);
    } else if (action === "delete-component") {
      await deleteComponent(selectedComponent());
    } else if (action === "move-component") {
      const selected = selectedComponent();
      if (selected) await moveComponent(selected, Number(target.dataset.dir));
    } else if (action === "drop-field") {
      const component = selectedComponent();
      const { well, field } = target.dataset;
      if (!component) return;
      const remaining = parseColumnSpecs(component.props[well] || "").map((column) => column.field).filter((name) => name !== field);
      // An empty columns list is removed rather than written as `[]`, which <DataTable> would read as
      // "no columns" instead of falling back to every field.
      editComponent(component, { [well]: remaining.length ? `[${remaining.map((name) => `"${name}"`).join(", ")}]` : "" });
    } else if (action === "assign-field") {
      const { field, dataset } = target.dataset;
      if (!assignToFirstFreeWell(field, dataset)) {
        state.error = "Select a chart or table on the canvas first — that is what a field binds to.";
        render();
      }
    } else if (action === "save-insight") {
      await saveInsight();
    } else if (action === "new-insight") {
      invalidateRun();
      invalidateAnalysis();
      state.activeId = null;
      state.name = "Untitled insight";
      state.source = EXAMPLE;
      state.savedSource = "";
      state.data = null;
      state.changeRevision = 0;
      state.runRevision = null;
      state.outline = [];
      state.selected = null;
      state.lastRunAt = null;
      state.dataFresh = false;
      state.runNote = "";
      state.error = "";
      state.parameters = {};
      saveStore({ lastInsightId: null, mode: state.mode });
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
        if (state.activeId === id) {
          invalidateRun();
          invalidateAnalysis();
        }
        await api.deleteInsight(id);
        if (state.activeId === id) {
          state.activeId = null;
          state.name = "Untitled insight";
          state.source = EXAMPLE;
          state.savedSource = "";
          state.data = null;
          state.analysis = null;
          state.outline = [];
          state.selected = null;
          state.changeRevision = 0;
          state.runRevision = null;
          state.lastRunAt = null;
          state.dataFresh = false;
          state.runNote = "";
          state.parameters = {};
          saveStore({ lastInsightId: null, mode: state.mode });
        }
        state.saved = await api.listInsights();
      } catch (error) {
        state.error = message(error, "Delete failed");
      }
      render();
      if (!state.activeId) analyze();
    } else if (action === "show-diagnostics") {
      // The diagnostics list lives in the editor footer, so the only place to "show" them is Code.
      state.mode = "code";
      saveStore({ lastInsightId: state.activeId, mode: state.mode });
      render();
      outlet.querySelector("#insight-source")?.focus();
    } else if (action === "dismiss-banner") {
      state.error = "";
      render();
    }
  });

  outlet.addEventListener("input", (event) => {
    if (event.target.id === "insight-source") {
      state.source = event.target.value;
      state.changeRevision += 1;
      // A hand edit can move or delete the selected tag, and its offset would then point at whatever
      // text has shifted into that position.
      state.selected = null;
      scheduleAnalyze();
    } else if (event.target.id === "insight-name") {
      state.name = event.target.value;
    } else if (event.target.dataset.param) {
      state.parameters[event.target.dataset.param] = event.target.type === "number" ? Number(event.target.value) : event.target.value;
      state.changeRevision += 1;
    }
  }, { signal: abort.signal });
  // Format inputs commit on change, not on input: every commit rewrites the document and repaints the
  // page, so applying per-keystroke would splice the source a dozen times for one title.
  outlet.addEventListener("change", (event) => {
    const { target } = event;
    if (target.id === "insight-connection") {
      state.connectionId = target.value;
      state.changeRevision += 1;
      analyze();
    } else if (target.dataset.wellSelect) {
      const well = target.dataset.wellSelect;
      const value = target.value;
      if (!value) return;
      if (well === "data") editComponent(selectedComponent(), { data: value });
      else assignField(well, value);
    } else if (target.dataset.format) {
      editComponent(selectedComponent(), { [target.dataset.format]: target.value.trim() });
    }
  }, { signal: abort.signal });

  // ── drag a field onto a well ──────────────────────────────────────────────

  /** A drop from outside the page carries text this never parses, so a bad payload is simply ignored. */
  function readTransfer(transfer) {
    try {
      const payload = JSON.parse(transfer.getData("text/plain"));
      return payload && payload.field ? payload : null;
    } catch {
      return null;
    }
  }

  outlet.addEventListener("dragstart", (event) => {
    const chip = event.target.closest("[data-field][data-dataset]");
    if (!chip) return;
    event.dataTransfer.setData("text/plain", JSON.stringify({ dataset: chip.dataset.dataset, field: chip.dataset.field }));
    event.dataTransfer.effectAllowed = "copy";
  }, { signal: abort.signal });
  outlet.addEventListener("dragover", (event) => {
    const well = event.target.closest("[data-well]");
    if (!well) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = "copy";
    well.classList.add("is-drop-target");
  }, { signal: abort.signal });
  outlet.addEventListener("dragleave", (event) => {
    event.target.closest("[data-well]")?.classList.remove("is-drop-target");
  }, { signal: abort.signal });
  outlet.addEventListener("drop", (event) => {
    const well = event.target.closest("[data-well]");
    if (!well) return;
    event.preventDefault();
    well.classList.remove("is-drop-target");
    // Carried as JSON rather than a delimited pair: a field name comes from the source API's own
    // JSON and may contain any separator character a hand-rolled encoding would pick.
    const payload = readTransfer(event.dataTransfer);
    if (payload) assignField(well.dataset.well, payload.field, payload.dataset);
  }, { signal: abort.signal });
  // Canvas blocks are selectable regions rather than buttons, so Enter and Space have to be wired up
  // by hand — every visual has to be reachable without a mouse.
  outlet.addEventListener("keydown", (event) => {
    if (event.key !== "Enter" && event.key !== " ") return;
    const block = event.target.closest('[data-action="select-block"]');
    if (!block || block.tagName === "BUTTON") return;
    event.preventDefault();
    block.click();
  }, { signal: abort.signal });

  // On document, not outlet: with nothing focused the keydown targets <body>, which never bubbles
  // through the page outlet. The abort signal unbinds it when the route changes.
  document.addEventListener("keydown", (event) => {
    if (!(IS_APPLE ? event.metaKey : event.ctrlKey) || event.altKey) return;
    if (event.key === "Enter") {
      event.preventDefault();
      runInsight();
    } else if (event.key.toLowerCase() === "s") {
      // Otherwise the browser offers to save the page, which is never what is wanted here.
      event.preventDefault();
      saveInsight();
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
    clearInterval(elapsedTimer);
  };
}
