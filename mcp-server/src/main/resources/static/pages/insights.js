import { api } from "../api.js";
import { escapeAttr, escapeHtml, formatDate, icon, markdown, message, on } from "../ui.js";

const EMPTY_INSIGHT = `---
title: Untitled insight
---

# Untitled insight

Add connected read requests to build a dashboard from multiple API datasets.
`;

function oneLine(value, fallback) {
  const text = String(value ?? "").replace(/\s+/g, " ").trim();
  return (text || fallback).slice(0, 120);
}

function rqlString(value) {
  return String(value ?? "").replaceAll("\\", "\\\\").replaceAll('"', '\\"');
}

function regexEscape(value) {
  return String(value ?? "").replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** A safe, useful draft based on a request that really exists in the connected app catalogue. */
function requestStarter(tool, connection) {
  const requestName = requestLabel(tool, connection);
  const title = oneLine(tool.displayName, tool.name || "API request");
  const table = withGridLayoutProps("<DataTable data={rows} />", { gridX: 0, gridY: 0, gridW: 8, gridH: 4 });
  return `---
title: ${JSON.stringify(title)}
grid:
  columns: ${GRID_DEFAULTS.columns}
  rowHeight: ${GRID_DEFAULTS.rowHeight}
  gap: ${GRID_DEFAULTS.gap}
---

# ${title} overview

\`\`\`rql
let rows = request "${rqlString(requestName)}";
\`\`\`

<KpiRow>
  <Stat value={count(rows)} label="Rows" />
</KpiRow>

${table}
<Status />
`;
}

function requestLabel(tool, connection) {
  return `${oneLine(connection?.name, "App")}: ${oneLine(tool?.displayName, tool?.name || "Request")}`;
}

function initialToolValues(tool) {
  const values = {};
  for (const [name, property] of Object.entries(tool?.paramsSchema?.properties || {})) {
    if (property.default !== undefined) values[name] = String(property.default);
    else values[name] = property.type === "boolean" ? "false" : "";
  }
  return values;
}

function coerceToolValue(raw, property) {
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

function toolArguments(tool, values = {}) {
  const properties = tool?.paramsSchema?.properties || {};
  return Object.fromEntries(Object.entries(properties).flatMap(([name, property]) => {
    const raw = values[name];
    return raw === "" || raw === undefined ? [] : [[name, coerceToolValue(raw, property)]];
  }));
}

function sourceLabelParts(label) {
  const match = String(label || "").match(/^([^:]+):\s*(.+)$/);
  return {
    collection: oneLine(match?.[1], "Collection"),
    request: oneLine(match?.[2] || label, "Request"),
  };
}

const GRID_DEFAULTS = { columns: 12, rowHeight: 96, gap: 12 };
const GRID_LIMITS = {
  columns: [4, 16],
  rowHeight: [56, 180],
  gap: [0, 32],
};
function clampNumber(value, fallback, min, max) {
  const number = Number(value);
  if (!Number.isFinite(number)) return fallback;
  return Math.max(min, Math.min(max, Math.round(number)));
}

function frontmatterBlock(source) {
  const text = String(source || "");
  if (!text.startsWith("---")) return null;
  const firstBreak = text.indexOf("\n");
  const end = firstBreak < 0 ? -1 : text.indexOf("\n---", firstBreak);
  if (end < 0) return null;
  const close = text.indexOf("\n", end + 1);
  return {
    start: 0,
    end: close < 0 ? text.length : close + 1,
    bodyStart: firstBreak + 1,
    bodyEnd: end,
    body: text.slice(firstBreak + 1, end),
  };
}

function dashboardGridSettings(source) {
  const block = frontmatterBlock(source);
  if (!block) return { ...GRID_DEFAULTS };
  const grid = /^grid:\s*\n((?:[ \t]+[A-Za-z][A-Za-z0-9]*:\s*.*(?:\n|$))*)/m.exec(block.body);
  if (!grid) return { ...GRID_DEFAULTS };
  const valueOf = (key) => {
    const match = new RegExp(`^\\s+${key}:\\s*([0-9]+)\\s*$`, "m").exec(grid[1]);
    return match ? Number(match[1]) : undefined;
  };
  return {
    columns: clampNumber(valueOf("columns"), GRID_DEFAULTS.columns, ...GRID_LIMITS.columns),
    rowHeight: clampNumber(valueOf("rowHeight"), GRID_DEFAULTS.rowHeight, ...GRID_LIMITS.rowHeight),
    gap: clampNumber(valueOf("gap"), GRID_DEFAULTS.gap, ...GRID_LIMITS.gap),
  };
}

function writeDashboardGridSettings(source, changes) {
  const current = dashboardGridSettings(source);
  const next = {
    columns: clampNumber(changes.columns ?? current.columns, current.columns, ...GRID_LIMITS.columns),
    rowHeight: clampNumber(changes.rowHeight ?? current.rowHeight, current.rowHeight, ...GRID_LIMITS.rowHeight),
    gap: clampNumber(changes.gap ?? current.gap, current.gap, ...GRID_LIMITS.gap),
  };
  const gridLines = [
    "grid:",
    `  columns: ${next.columns}`,
    `  rowHeight: ${next.rowHeight}`,
    `  gap: ${next.gap}`,
  ];
  const block = frontmatterBlock(source);
  if (!block) return `---\n${gridLines.join("\n")}\n---\n\n${String(source || "").replace(/^\s+/, "")}`;
  const bodyLines = block.body.split("\n");
  const kept = [];
  for (let index = 0; index < bodyLines.length; index += 1) {
    const line = bodyLines[index];
    if (/^grid:\s*$/.test(line)) {
      while (index + 1 < bodyLines.length && /^\s+/.test(bodyLines[index + 1])) index += 1;
      continue;
    }
    kept.push(line);
  }
  while (kept.length && !kept[kept.length - 1].trim()) kept.pop();
  const body = [...kept, ...gridLines].join("\n");
  return `${source.slice(0, block.bodyStart)}${body}\n${source.slice(block.bodyEnd)}`;
}

function identifier(value, fallback = "dataset") {
  const clean = String(value || fallback)
    .replace(/[^A-Za-z0-9_]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .replace(/^[0-9]/, "_$&");
  return clean || fallback;
}

function requestBindings(source) {
  const bindings = [];
  const pattern = /let\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*request\s+"((?:[^"\\]|\\.)*)"/g;
  let match;
  while ((match = pattern.exec(String(source || "")))) {
    bindings.push({
      name: match[1],
      label: unescapeRqlString(match[2]),
      startOffset: match.index,
      endOffset: pattern.lastIndex,
    });
  }
  return bindings;
}

function datasetStatement(source, name) {
  const pattern = new RegExp(`let\\s+${regexEscape(name)}\\s*=\\s*([\\s\\S]*?);`, "i");
  const match = String(source || "").match(pattern);
  if (!match) return null;
  return {
    name,
    expression: match[1].trim(),
    startOffset: match.index,
    endOffset: match.index + match[0].length,
  };
}

function allDatasetNames(source) {
  return [...new Set([
    ...requestBindings(source).map((binding) => binding.name),
    ...relationshipBindings(source).map((binding) => binding.name),
  ])];
}

function nextDatasetName(source, tool) {
  const existing = new Set(requestBindings(source).map((binding) => binding.name));
  const base = identifier(tool?.name || tool?.displayName || "dataset").replace(/_request$/i, "") || "dataset";
  if (!existing.has(base)) return base;
  for (let index = 2; index < 100; index += 1) {
    const candidate = `${base}_${index}`;
    if (!existing.has(candidate)) return candidate;
  }
  return `${base}_${Date.now()}`;
}

function renameDatasetReferences(source, oldName, nextName) {
  return String(source || "").replace(new RegExp(`\\b${regexEscape(oldName)}\\b`, "g"), nextName);
}

function removeRequestBindingSource(source, name) {
  const binding = requestBindings(source).find((item) => item.name === name);
  if (!binding || name === "rows") return source;
  const text = String(source || "");
  const fenceStart = text.lastIndexOf("```rql", binding.startOffset);
  const fenceEnd = text.indexOf("```", binding.endOffset);
  if (fenceStart >= 0 && fenceEnd >= binding.endOffset) {
    let start = fenceStart;
    while (start > 0 && text[start - 1] === "\n") start -= 1;
    let end = fenceEnd + 3;
    while (end < text.length && text[end] === "\n") end += 1;
    return text.slice(0, start) + text.slice(end);
  }
  return text.slice(0, binding.startOffset) + text.slice(binding.endOffset);
}

function removeFencedStatement(source, startOffset, endOffset) {
  const text = String(source || "");
  const fenceStart = text.lastIndexOf("```rql", startOffset);
  const fenceEnd = text.indexOf("```", endOffset);
  if (fenceStart >= 0 && fenceEnd >= endOffset) {
    let start = fenceStart;
    while (start > 0 && text[start - 1] === "\n") start -= 1;
    let end = fenceEnd + 3;
    while (end < text.length && text[end] === "\n") end += 1;
    return text.slice(0, start) + text.slice(end);
  }
  return text.slice(0, startOffset) + text.slice(endOffset);
}

function relationshipBindings(source) {
  const relationships = [];
  const pattern = /let\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*([\s\S]*?);/g;
  let match;
  while ((match = pattern.exec(String(source || "")))) {
    const parts = splitPipeline(match[2]);
    const joined = parts[1]?.match(/^join\s+([A-Za-z_][A-Za-z0-9_]*)\s+on\s+([^\s=]+)\s*=\s*([^\s]+)(?:\s+prefix\s+"([^"]+)")?$/i);
    if (!parts[0] || !joined) continue;
    relationships.push({
      name: match[1],
      left: parts[0],
      right: joined[1],
      leftField: joined[2],
      rightField: joined[3],
      prefix: joined[4] || joined[1],
      visualStages: parts.slice(2),
      startOffset: match.index,
      endOffset: pattern.lastIndex,
    });
  }
  return relationships;
}

function nextRelationshipName(source, left, right) {
  const existing = new Set([
    ...requestBindings(source).map((binding) => binding.name),
    ...relationshipBindings(source).map((binding) => binding.name),
  ]);
  const base = identifier(`${left}_${right}`, "joined");
  if (!existing.has(base)) return base;
  for (let index = 2; index < 100; index += 1) {
    const candidate = `${base}_${index}`;
    if (!existing.has(candidate)) return candidate;
  }
  return `${base}_${Date.now()}`;
}

function relationshipSource({ name, left, right, leftField, rightField, prefix, visualStages = [] }) {
  const suffix = visualStages.length ? `\n  |> ${visualStages.join("\n  |> ")}` : "";
  return `let ${identifier(name, "joined")} = ${left} |> join ${right} on ${leftField} = ${rightField} prefix "${rqlString(prefix || right)}"${suffix};`;
}

function replaceRelationshipBindingSource(source, oldName, relationship) {
  const binding = relationshipBindings(source).find((item) => item.name === oldName);
  if (!binding) return source;
  const next = { ...relationship, visualStages: relationship.visualStages || binding.visualStages || [] };
  return String(source || "").slice(0, binding.startOffset)
    + relationshipSource(next)
    + String(source || "").slice(binding.endOffset);
}

function removeRelationshipBindingSource(source, name) {
  const binding = relationshipBindings(source).find((item) => item.name === name);
  if (!binding) return source;
  return removeFencedStatement(source, binding.startOffset, binding.endOffset);
}

function removeDatasetTables(source, dataset) {
  const safe = dataset.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return String(source || "")
    .replace(new RegExp(`\\n*## [^\\n]+\\n\\n<DataTable\\s+data=\\{${safe}\\}\\s*/>\\n*`, "g"), "\n\n")
    .replace(new RegExp(`\\n*<DataTable\\s+data=\\{${safe}\\}\\s*/>\\n*`, "g"), "\n\n");
}

function compactDocument(source) {
  return String(source || "").replace(/\n{4,}/g, "\n\n\n");
}

// ── visual query model ──────────────────────────────────────────────────────
// The canvas edits the same RQL statement that Code mode exposes. This keeps one executable source
// of truth while letting the common SELECT / WHERE / ORDER / LIMIT workflow stay entirely visual.

const QUERY_OPERATORS = [
  { value: "=", label: "equals", needsValue: true },
  { value: "!=", label: "does not equal", needsValue: true },
  { value: ">", label: "greater than", needsValue: true },
  { value: ">=", label: "at least", needsValue: true },
  { value: "<", label: "less than", needsValue: true },
  { value: "<=", label: "at most", needsValue: true },
  { value: "contains", label: "contains", needsValue: true },
  { value: "not_contains", label: "does not contain", needsValue: true },
  { value: "starts with", label: "starts with", needsValue: true },
  { value: "ends with", label: "ends with", needsValue: true },
  { value: "in", label: "is one of", needsValue: true },
  { value: "is null", label: "is empty", needsValue: false },
  { value: "is not null", label: "is not empty", needsValue: false },
  { value: "is true", label: "is true", needsValue: false },
  { value: "is false", label: "is false", needsValue: false },
];

let queryRuleSequence = 0;

function nextRuleId() {
  queryRuleSequence += 1;
  return `condition-${queryRuleSequence}`;
}

function blankVisualQuery() {
  return {
    managed: false,
    requestExpression: "",
    requestLabel: "",
    columns: [],
    filters: [],
    distinct: false,
    sortField: "",
    sortDirection: "asc",
    limit: 100,
    groupField: "",
    aggregateFunction: "count",
    aggregateField: "*",
  };
}

/** Splits a pipeline without treating a quoted `|>` as a delimiter. */
function splitPipeline(source) {
  const parts = [];
  let start = 0;
  let quote = false;
  let escape = false;
  let depth = 0;
  for (let index = 0; index < source.length - 1; index += 1) {
    const char = source[index];
    if (quote) {
      if (escape) escape = false;
      else if (char === "\\") escape = true;
      else if (char === '"') quote = false;
      continue;
    }
    if (char === '"') quote = true;
    else if ("([{ ".includes(char) && char !== " ") depth += 1;
    else if (")]}".includes(char)) depth = Math.max(0, depth - 1);
    else if (!depth && source.startsWith("|>", index)) {
      parts.push(source.slice(start, index).trim());
      start = index + 2;
      index += 1;
    }
  }
  parts.push(source.slice(start).trim());
  return parts.filter(Boolean);
}

function unescapeRqlString(value) {
  return String(value || "").replace(/\\([\\"])/g, "$1");
}

function parseFilterRules(expression) {
  if (!expression) return [];
  const pieces = expression.split(/\s+(and|or)\s+/i);
  const rules = [];
  for (let index = 0; index < pieces.length; index += 2) {
    const text = pieces[index].trim().replace(/^\((.*)\)$/s, "$1").trim();
    const logic = index ? pieces[index - 1].toUpperCase() : "AND";
    const match = text.match(/^([^\s]+)\s+(is\s+not\s+null|is\s+null|is\s+true|is\s+false|not_contains|starts\s+with|ends\s+with|contains|in|>=|<=|!=|<>|==|=|>|<)(?:\s+(.+))?$/i);
    if (!match) return null;
    let value = (match[3] || "").trim();
    if (/^\(.*\)$/.test(value)) value = value.slice(1, -1);
    value = value.replace(/^"(.*)"$/s, "$1").replace(/\\"/g, '"');
    if (match[2].toLowerCase() === "in") {
      value = value.split(",").map((item) => item.trim().replace(/^"(.*)"$/s, "$1")).join(", ");
    }
    rules.push({ id: nextRuleId(), logic, field: match[1], operator: match[2].toLowerCase().replace(/\s+/g, " "), value });
  }
  return rules;
}

/** Reads the subset the GUI can safely round-trip. Custom RQL remains available in Code mode. */
function parseVisualQuery(source, datasetName = "rows") {
  const query = blankVisualQuery();
  const statement = datasetStatement(source, datasetName);
  if (!statement) return query;
  const parts = splitPipeline(statement.expression);
  const request = parts[0]?.match(/^request\s+"((?:[^"\\]|\\.)*)"$/i);
  const joined = parts[1]?.match(/^join\s+([A-Za-z_][A-Za-z0-9_]*)\s+on\s+([^\s=]+)\s*=\s*([^\s]+)(?:\s+prefix\s+"([^"]+)")?$/i);
  let stages = [];
  query.managed = true;
  if (request) {
    query.requestExpression = parts[0];
    query.requestLabel = unescapeRqlString(request[1]);
    stages = parts.slice(1);
  } else if (/^[A-Za-z_][A-Za-z0-9_]*$/.test(parts[0] || "") && joined) {
    query.requestExpression = `${parts[0]}\n  |> ${parts[1]}`;
    query.requestLabel = `${parts[0]} joined to ${joined[1]}`;
    stages = parts.slice(2);
  } else {
    query.managed = false;
    return query;
  }
  for (const raw of stages) {
    const stage = raw.trim();
    const lower = stage.toLowerCase();
    if (lower.startsWith("where ")) {
      const parsed = parseFilterRules(stage.slice(6));
      if (parsed === null) query.managed = false;
      else query.filters = parsed;
    } else if (lower.startsWith("select ")) {
      query.columns = stage.slice(7).split(",").map((field) => field.trim()).filter(Boolean);
    } else if (lower === "distinct") {
      query.distinct = true;
    } else if (lower.startsWith("order by ")) {
      const sort = stage.slice(9).trim().match(/^([^\s,]+)(?:\s+(asc|desc))?/i);
      query.sortField = sort?.[1] || "";
      query.sortDirection = sort?.[2]?.toLowerCase() === "desc" ? "desc" : "asc";
    } else if (lower.startsWith("limit ")) {
      query.limit = Math.max(1, Math.min(10000, Number(stage.slice(6).trim()) || 100));
    } else if (lower.startsWith("group by ")) {
      const grouped = stage.slice(9).match(/^([^\s,]+)\s+agg\s+(count|sum|avg|min|max)\(([^)]+)\)(?:\s+as\s+([^\s]+))?/i);
      if (!grouped) query.managed = false;
      else {
        query.groupField = grouped[1];
        query.aggregateFunction = grouped[2].toLowerCase();
        query.aggregateField = grouped[3];
      }
    } else {
      query.managed = false;
    }
  }
  return query;
}

function queryValue(value) {
  const text = String(value ?? "").trim();
  if (/^-?(?:0|[1-9]\d*)(?:\.\d+)?$/.test(text) || /^(true|false|null)$/i.test(text)) return text.toLowerCase();
  return `"${rqlString(text)}"`;
}

function queryCondition(rule) {
  const operator = QUERY_OPERATORS.find((item) => item.value === rule.operator) || QUERY_OPERATORS[0];
  if (!operator.needsValue) return `${rule.field} ${rule.operator}`;
  if (rule.operator === "in") {
    const values = String(rule.value || "").split(",").map((value) => value.trim()).filter(Boolean);
    return `${rule.field} in (${values.map(queryValue).join(", ")})`;
  }
  return `${rule.field} ${rule.operator} ${queryValue(rule.value)}`;
}

function aggregateAlias(query) {
  const field = query.aggregateField === "*" ? "" : `_${query.aggregateField.replace(/[^A-Za-z0-9_]/g, "_")}`;
  return `${query.aggregateFunction}${field}`;
}

function visualQueryPipeline(query) {
  const stages = [query.requestExpression];
  const filters = query.filters.filter((rule) => rule.field && (QUERY_OPERATORS.find((item) => item.value === rule.operator)?.needsValue === false || String(rule.value || "").trim()));
  if (filters.length) {
    stages.push(`where ${filters.map((rule, index) => `${index ? `${rule.logic.toLowerCase()} ` : ""}${queryCondition(rule)}`).join(" ")}`);
  }
  if (query.groupField) {
    const aggregateField = query.aggregateFunction === "count" ? (query.aggregateField || "*") : query.aggregateField;
    if (aggregateField) stages.push(`group by ${query.groupField} agg ${query.aggregateFunction}(${aggregateField}) as ${aggregateAlias(query)}`);
  }
  if (query.sortField) stages.push(`order by ${query.sortField} ${query.sortDirection}`);
  if (!query.groupField && query.columns.length) stages.push(`select ${query.columns.join(", ")}`);
  if (query.distinct) stages.push("distinct");
  if (query.limit) stages.push(`limit ${Math.max(1, Math.min(10000, Number(query.limit) || 100))}`);
  return stages.join("\n  |> ");
}

function applyVisualQuery(source, query, datasetName = "rows") {
  const statement = datasetStatement(source, datasetName);
  if (!statement) return source;
  return source.slice(0, statement.startOffset) + `let ${datasetName} = ${visualQueryPipeline(query)};` + source.slice(statement.endOffset);
}

function sqlQueryPreview(query) {
  const columns = query.groupField
    ? `${query.groupField}, ${query.aggregateFunction.toUpperCase()}(${query.aggregateField || "*"}) AS ${aggregateAlias(query)}`
    : query.columns.length ? query.columns.join(", ") : "*";
  const clauses = [`SELECT${query.distinct ? " DISTINCT" : ""} ${columns}`, `FROM \"${query.requestLabel || "request"}\"`];
  const filters = query.filters.filter((rule) => rule.field && (QUERY_OPERATORS.find((item) => item.value === rule.operator)?.needsValue === false || String(rule.value || "").trim()));
  if (filters.length) clauses.push(`WHERE ${filters.map((rule, index) => `${index ? `${rule.logic} ` : ""}${queryCondition(rule)}`).join(" ")}`);
  if (query.groupField) clauses.push(`GROUP BY ${query.groupField}`);
  if (query.sortField) clauses.push(`ORDER BY ${query.sortField} ${query.sortDirection.toUpperCase()}`);
  if (query.limit) clauses.push(`LIMIT ${query.limit}`);
  return clauses.join("\n");
}

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

function isGridComponent(component) {
  return Boolean(component?.span) && component.type !== "Filter" && component.type !== "KpiRow";
}

function defaultGridSize(component) {
  switch (component?.type) {
    case "DataTable":
      return { w: 8, h: 4 };
    case "BarChart":
    case "LineChart":
    case "PieChart":
      return { w: 6, h: 4 };
    case "Metrics":
    case "Status":
      return { w: 6, h: 3 };
    case "QuickTable":
    case "LabelTable":
      return { w: 5, h: 3 };
    case "Stat":
    case "KeyValue":
    case "LabelValue":
    case "Text":
      return { w: 3, h: 2 };
    default:
      return { w: 4, h: 3 };
  }
}

function componentGridLayout(component, grid, fallback) {
  const size = defaultGridSize(component);
  const hasPosition = component?.props?.gridX !== undefined && component?.props?.gridY !== undefined;
  const hasSize = component?.props?.gridW !== undefined || component?.props?.gridH !== undefined;
  const w = clampNumber(component?.props?.gridW, size.w, 1, grid.columns);
  const h = clampNumber(component?.props?.gridH, size.h, 1, 12);
  const x = hasPosition
    ? clampNumber(component?.props?.gridX, 0, 0, Math.max(0, grid.columns - w))
    : fallback.x;
  const y = hasPosition
    ? clampNumber(component?.props?.gridY, fallback.y, 0, 200)
    : fallback.y;
  return { x, y, w: Math.min(w, grid.columns - x), h, explicit: hasPosition || hasSize };
}

function gridItemStyle(layout) {
  return `--grid-x:${layout.x + 1};--grid-y:${layout.y + 1};--grid-w:${layout.w};--grid-h:${layout.h};`;
}

function nextAutoGridSlot(cursor, grid, component) {
  const size = defaultGridSize(component);
  const w = Math.min(size.w, grid.columns);
  const h = size.h;
  if (cursor.x + w > grid.columns) {
    cursor.x = 0;
    cursor.y += Math.max(1, cursor.rowH);
    cursor.rowH = 0;
  }
  const slot = { x: cursor.x, y: cursor.y, w, h };
  cursor.x += w;
  cursor.rowH = Math.max(cursor.rowH, h);
  return slot;
}

function gridLayoutsOverlap(left, right) {
  return left.x < right.x + right.w
    && left.x + left.w > right.x
    && left.y < right.y + right.h
    && left.y + left.h > right.y;
}

function gridLayoutCollides(layout, occupied) {
  return occupied.some((item) => gridLayoutsOverlap(layout, item));
}

function resolveGridCollision(layout, occupied, grid) {
  const normalized = {
    ...layout,
    x: clampNumber(layout.x, 0, 0, Math.max(0, grid.columns - layout.w)),
    y: clampNumber(layout.y, 0, 0, 200),
    w: clampNumber(layout.w, layout.w, 1, grid.columns),
    h: clampNumber(layout.h, layout.h, 1, 12),
    explicit: layout.explicit,
  };
  normalized.w = Math.min(normalized.w, grid.columns - normalized.x);
  if (!gridLayoutCollides(normalized, occupied)) return normalized;

  // Preserve the chosen column first. If another block already occupies the cells, the least
  // surprising correction is to move this one down until it reaches open grid space.
  for (let y = normalized.y + 1; y <= 200; y += 1) {
    const candidate = { ...normalized, y };
    if (!gridLayoutCollides(candidate, occupied)) return candidate;
  }

  // Fallback for dense layouts: scan the grid top-to-bottom and pick the first clean slot.
  for (let y = 0; y <= 200; y += 1) {
    for (let x = 0; x <= grid.columns - normalized.w; x += 1) {
      const candidate = { ...normalized, x, y };
      if (!gridLayoutCollides(candidate, occupied)) return candidate;
    }
  }
  return normalized;
}

function normalizeGridLayout(layout, grid) {
  const w = clampNumber(layout.w, 1, 1, grid.columns);
  const x = clampNumber(layout.x, 0, 0, Math.max(0, grid.columns - w));
  return {
    ...layout,
    x,
    y: clampNumber(layout.y, 0, 0, 200),
    w: Math.min(w, grid.columns - x),
    h: clampNumber(layout.h, 1, 1, 12),
  };
}

function compactGridRows(items, grid) {
  const placed = [];
  const compacted = [];
  const ordered = items
    .map((item, index) => ({ ...item, index, layout: normalizeGridLayout(item.layout, grid) }))
    .sort((left, right) => left.layout.y - right.layout.y
      || left.layout.x - right.layout.x
      || left.index - right.index);

  for (const item of ordered) {
    let next = { ...item.layout };
    while (next.y > 0) {
      const candidate = { ...next, y: next.y - 1 };
      if (gridLayoutCollides(candidate, placed)) break;
      next = candidate;
    }
    placed.push(next);
    compacted.push({ ...item, layout: next });
  }

  return compacted
    .sort((left, right) => left.index - right.index)
    .map(({ index: _index, ...item }) => item);
}

function resolvedComponentGridLayouts(components, grid, overrides = new Map()) {
  const cursor = { x: 0, y: 0, rowH: 0 };
  const occupied = [];
  const items = [];
  for (const component of components || []) {
    if (!isGridComponent(component)) continue;
    const fallback = nextAutoGridSlot(cursor, grid, component);
    const current = componentGridLayout(component, grid, fallback);
    const override = overrides.get(componentKey(component));
    const rawLayout = override ? { ...current, ...override, explicit: true } : current;
    const layout = resolveGridCollision(rawLayout, occupied, grid);
    occupied.push(layout);
    items.push({ component, layout });
  }
  return compactGridRows(items, grid);
}

function nonOverlappingResize(layout, nextW, nextH, occupied, grid) {
  const candidate = {
    ...layout,
    w: clampNumber(nextW, layout.w, 1, grid.columns - layout.x),
    h: clampNumber(nextH, layout.h, 1, 12),
  };
  while (gridLayoutCollides(candidate, occupied) && candidate.w > layout.w) candidate.w -= 1;
  while (gridLayoutCollides(candidate, occupied) && candidate.h > layout.h) candidate.h -= 1;
  return candidate;
}

function nonOverlappingMove(layout, nextX, nextY, occupied, grid) {
  const candidate = {
    ...layout,
    x: clampNumber(nextX, layout.x, 0, Math.max(0, grid.columns - layout.w)),
    y: clampNumber(nextY, layout.y, 0, 200),
    explicit: true,
  };
  return gridLayoutCollides(candidate, occupied) ? null : candidate;
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
  const grid = design?.grid || GRID_DEFAULTS;
  const editable = Boolean(design?.editable);
  const autoCursor = { x: 0, y: 0, rowH: 0 };
  const renderItems = [];
  const stats = [];
  const occupied = [];
  const isSelected = (component) => editable && componentKey(component) === design.selected;
  const selectAttrs = (component) => (editable
    ? ` data-action="select-block" data-offset="${componentKey(component)}" tabindex="0" role="button" aria-pressed="${isSelected(component)}"`
    : "");
  const selectable = (component, html) => (editable
    ? `<div class="insight-block ${isSelected(component) ? "is-selected" : ""}"${selectAttrs(component)} aria-label="${escapeAttr(component.type)}"><span class="insight-block-tag">${escapeHtml(component.type)}</span>${html}</div>`
    : html);
  const canvasControls = (component, layout) => (editable && isSelected(component)
    ? `<div class="insight-canvas-controls" aria-label="${escapeAttr(component.type)} canvas controls">
        <button class="insight-canvas-tool is-danger" type="button" data-action="canvas-delete-component" data-offset="${componentKey(component)}" title="Delete from dashboard" aria-label="Delete ${escapeAttr(component.type)}">${icon("trash", 12)}</button>
      </div><button class="insight-canvas-resize-handle" type="button" data-resize-handle data-offset="${componentKey(component)}" title="Drag to resize" aria-label="Drag to resize ${escapeAttr(component.type)}"></button>`
    : "");
  const pushGridItem = (component, html, options = {}) => {
    const fallback = nextAutoGridSlot(autoCursor, grid, component);
    const rawLayout = options.fullWidth
      ? { x: 0, y: fallback.y, w: grid.columns, h: options.h || fallback.h }
      : componentGridLayout(component, grid, fallback);
    const layout = resolveGridCollision(rawLayout, occupied, grid);
    occupied.push(layout);
    renderItems.push({ component, html, layout });
  };
  const flushStats = () => {
    if (!stats.length) return;
    stats.splice(0).forEach((component) => {
      pushGridItem(component, `<section class="insight-stat ${isSelected(component) ? "is-selected" : ""}"${selectAttrs(component)}><span>${escapeHtml(component.props.label || "Metric")}</span><strong>${escapeHtml(evaluate(component.props.value, data.datasets))}</strong></section>`);
    });
  };
  (data.outline || []).forEach((component) => {
    if (component.type === "Stat") {
      stats.push(component);
      return;
    }
    if (["KpiRow", "Filter"].includes(component.type)) return;
    flushStats();
    const dataset = data.datasets?.[datasetName(component)];
    const push = (html) => pushGridItem(component, selectable(component, html));
    if (component.type === "Prose") push(`<div class="insight-prose">${markdown(interpolate(component.props.value, data.datasets), 2)}</div>`);
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
  const empty = editable
    ? '<div class="insight-preview-empty"><h2>Empty canvas</h2><p>Add a visual from the toolbar, then use Grid to place it on the dashboard.</p></div>'
    : '<div class="insight-preview-empty"><h2>No renderable components</h2><p>Add a Stat, BarChart, or DataTable component to the document.</p></div>';
  const blocks = compactGridRows(renderItems, grid).map(({ component, html, layout }) => (
    `<div class="insight-grid-item ${layout.explicit ? "is-positioned" : ""} ${isSelected(component) ? "is-selected" : ""}" style="${gridItemStyle(layout)}">${html}${canvasControls(component, layout)}</div>`
  ));
  return `<div class="insight-rendered ${editable ? "is-design" : ""}"><div class="insight-dashboard-grid" style="--grid-columns:${grid.columns};--grid-row-height:${grid.rowHeight}px;--grid-gap:${grid.gap}px;">${blocks.join("") || empty}</div></div>`;
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
    label: "Data table", icon: "table", group: "Tables", needsData: true,
    description: "Uses the loaded API dataset. Pick the read request above, then choose visible columns here.",
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
    label: "Manual table", icon: "table", group: "Manual",
    description: "Manual table from expressions. It does not choose an API request; use Data table for request-backed rows and column selection.",
    format: [{ prop: "title", label: "Title", kind: "text" }, { prop: "headers", label: "Headers", kind: "expr" }, { prop: "rows", label: "Rows", kind: "expr" }],
  },
  LabelTable: {
    label: "Manual label table", icon: "table", group: "Manual",
    description: "Manual label/value rows from expressions. Use Data table when you want to bind API rows and choose columns.",
    format: [{ prop: "title", label: "Title", kind: "text" }, { prop: "headers", label: "Headers", kind: "expr" }, { prop: "rows", label: "Rows", kind: "expr" }],
  },
  Metrics: { label: "Run metrics", icon: "kpi", group: "Execution", format: [] },
  Status: { label: "Request status", icon: "kpi", group: "Execution", format: [] },
  // Rendered and selectable, but never offered in the picker — prose is written in Code.
  Prose: { label: "Prose", icon: "text", group: null, format: [] },
};

const PICKER_GROUPS = ["Charts", "Tables", "Cards", "Manual", "Execution"];

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

function withGridLayoutProps(tag, layout) {
  return Object.entries(layout).reduce((next, [name, value]) => setTagProp(next, name, value), tag);
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

/** Save state is independent from run freshness: editing a never-run insight is still an unsaved edit. */
function hasUnsavedChanges(state) {
  if (!state.activeId) {
    return state.source !== EMPTY_INSIGHT || state.name !== "Untitled insight" || Boolean(state.data);
  }
  return state.source !== state.savedSource
    || state.name !== state.savedName
    || state.connectionId !== state.savedConnectionId;
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
  const unsaved = hasUnsavedChanges(state);

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
    value: !state.activeId ? "Unsaved draft" : unsaved ? "Unsaved changes" : "Saved",
    tone: state.activeId && !unsaved ? "ok" : "warn",
    action: !state.activeId || unsaved ? "save-insight" : undefined,
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

const MODES = ["design", "view", "code"];
const MODE_LABELS = { design: "Build", view: "Output", code: "Source" };
const MODE_ICONS = { view: "file", design: "wand", code: "hash" };
const MODE_HINTS = {
  design: "Build the query and configure visuals",
  view: "Focus on the rendered result",
  code: "Inspect or edit the generated source document",
};
const AUTHOR_TABS = ["compose", "api", "source"];
const AUTHOR_TAB_LABELS = { compose: "Build", api: "Test request", source: "Source" };
const AUTHOR_TAB_ICONS = { compose: "wand", api: "globe", source: "hash" };

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
      // The Insights landing page is consumption-first. Restore the document, not the authoring
      // surface; Create/Edit are the explicit entry points into the IDE.
      mode: "view",
      autoSave: Boolean(parsed?.autoSave),
    };
  } catch {
    // The workspace stays fully usable when browser storage is unavailable.
  }
  return { lastInsightId: null, mode: "view", autoSave: false };
}

function saveStore(store) {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify({
      lastInsightId: store.lastInsightId || null,
      mode: MODES.includes(store.mode) ? store.mode : "design",
      autoSave: Boolean(store.autoSave),
    }));
  } catch {
    // Remembering the last insight is an optional convenience.
  }
}

export async function mount(outlet) {
  const state = {
    source: EMPTY_INSIGHT,
    connections: [],
    tools: [],
    starterToolId: "",
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
    // Kept as an editor preference. Saving is debounced so typing never sends one request per key.
    autoSave: false,
    // The document as last saved, so "edited since this run" is a plain comparison.
    savedSource: "",
    savedName: "",
    savedConnectionId: "",
    // Monotonic local edits let a run be marked stale for source, connection, and parameter changes,
    // including unsaved drafts where there is no savedSource baseline to compare against.
    changeRevision: 0,
    runRevision: null,
    mode: "design",
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
    queryDataset: "rows",
    query: blankVisualQuery(),
    relationship: { left: "", right: "", leftField: "", rightField: "", name: "", prefix: "", editing: "" },
    authorTab: "compose",
    apiTestToolId: "",
    apiTestDrafts: new Map(),
    apiTestResult: null,
    apiTestError: "",
    apiTestSubmitting: false,
    // Keep the discovered response schema even after SELECT or GROUP BY narrows the next result.
    queryFields: [],
    queryFieldsByDataset: {},
    // Completion spans use source offsets, so the editor keeps the caret position alongside the
    // document rather than trying to infer it after a debounced analysis returns.
    cursorOffset: 0,
    completionIndex: 0,
  };
  const abort = new AbortController();
  let analysisTimer = 0;
  let autoSaveTimer = 0;
  let lastAutoSaveAttempt = "";
  let elapsedTimer = 0;
  let analysisSequence = 0;
  let runSequence = 0;
  let resizeDrag = null;
  let moveDrag = null;
  let suppressCanvasClick = false;

  function invalidateRun() {
    runSequence += 1;
    state.running = false;
    state.runStartedAt = null;
  }

  function invalidateAnalysis() {
    analysisSequence += 1;
    clearTimeout(analysisTimer);
  }

  function autoSaveKey() {
    return `${state.name}\u0000${state.connectionId}\u0000${state.source}`;
  }

  /** Queue one quiet-period save for the current document revision. */
  function scheduleAutoSave() {
    clearTimeout(autoSaveTimer);
    if (!state.autoSave || state.mode === "view" || state.saving || state.running
      || !hasUnsavedChanges(state) || !state.name.trim()) return;
    const key = autoSaveKey();
    if (key === lastAutoSaveAttempt) return;
    autoSaveTimer = window.setTimeout(async () => {
      if (!state.autoSave || state.mode === "view" || state.saving || state.running
        || !hasUnsavedChanges(state) || autoSaveKey() !== key) return;
      lastAutoSaveAttempt = key;
      await saveInsight();
    }, 900);
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

  /** A compact document state keeps the workspace legible without asking the user to infer it from
   * whether the Save button happens to be enabled. */
  function documentStatus() {
    if (state.saving) return { label: "Saving", tone: "is-pending" };
    if (state.activeId && !hasUnsavedChanges(state)) return { label: "Saved", tone: "is-saved" };
    if (state.activeId) return { label: "Unsaved changes", tone: "is-changed" };
    return { label: state.source === EMPTY_INSIGHT ? "New draft" : "Unsaved draft", tone: "is-draft" };
  }

  /** The result panel is the largest region on the page; when empty it should offer the next step. */
  function emptyPreview() {
    if (state.opening) return '<div class="insight-preview-empty"><h2>Opening…</h2></div>';
    return `<div class="insight-preview-empty">${icon("file", 24)}<h2>Ready to run</h2><p>Run the insight to fetch API data and render its datasets.</p>
      <p class="insight-empty-hint">Use Run insight above or press ${escapeHtml(SHORTCUT_KEY)}Enter</p></div>`;
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
      const attrs = `class="insight-status-segment is-${escapeAttr(segment.tone)}" data-status-key="${escapeAttr(segment.key)}"${segment.title ? ` title="${escapeAttr(segment.title)}"` : ""}`;
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
    if (state.mode === "code" || activeAuthorTab() === "source") return "";
    const errors = (state.analysis?.diagnostics || []).filter((item) => item.severity === "ERROR");
    if (!errors.length) return "";
    return `<button class="insight-run-error insight-run-error-action" type="button" data-action="show-diagnostics">${icon("alert", 15)} <span>${errors.length} issue${errors.length === 1 ? "" : "s"} in the document — open Source to see ${errors.length === 1 ? "it" : "them"}.</span></button>`;
  }

  function starterRequests() {
    const connected = new Set(state.connections
      .filter((connection) => connection.status === "CONNECTED")
      .map((connection) => connection.id));
    return state.tools.filter((tool) => connected.has(tool.connectionId)
      && tool.enabled && !tool.pending && tool.method === "GET");
  }

  function toolConnection(tool) {
    return tool ? state.connections.find((connection) => connection.id === tool.connectionId) : null;
  }

  function toolByRequestLabel(label) {
    return starterRequests().find((tool) => requestLabel(tool, toolConnection(tool)) === label) || null;
  }

  function apiTestDraft(tool) {
    if (!tool) return { values: {} };
    if (!state.apiTestDrafts.has(tool.id)) {
      state.apiTestDrafts.set(tool.id, { values: initialToolValues(tool) });
    }
    return state.apiTestDrafts.get(tool.id);
  }

  function syncApiTestTool() {
    const requests = starterRequests();
    if (!requests.length) {
      state.apiTestToolId = "";
      return null;
    }
    const existing = requests.find((tool) => tool.id === state.apiTestToolId);
    if (existing) return existing;
    const selectedBinding = requestBindings(state.source).find((binding) => binding.name === state.queryDataset);
    const selectedTool = selectedBinding && toolByRequestLabel(selectedBinding.label);
    const starter = requests.find((tool) => tool.id === state.starterToolId);
    const fallback = selectedTool || starter || requests[0];
    state.apiTestToolId = fallback?.id || "";
    return fallback || null;
  }

  function requestIsInInsight(tool) {
    if (!tool) return false;
    const label = requestLabel(tool, toolConnection(tool));
    return requestBindings(state.source).some((binding) => binding.label === label);
  }

  function apiTestRequestOptions(requests, selectedId) {
    const bindings = requestBindings(state.source);
    const usedLabels = new Set(bindings.map((binding) => binding.label));
    const inputOptions = bindings.map((binding) => {
      const tool = toolByRequestLabel(binding.label);
      if (!tool) return "";
      const parts = sourceLabelParts(binding.label);
      return `<option value="${escapeAttr(tool.id)}" ${tool.id === selectedId ? "selected" : ""}>${escapeHtml(binding.name)} — ${escapeHtml(parts.collection)} / ${escapeHtml(parts.request)}</option>`;
    }).join("");
    const availableOptions = state.connections.filter((connection) => connection.status === "CONNECTED").map((connection) => {
      const items = requests.filter((tool) => tool.connectionId === connection.id
        && !usedLabels.has(requestLabel(tool, connection)));
      if (!items.length) return "";
      return `<optgroup label="${escapeAttr(connection.name || "Collection")}">${items.map((tool) => `<option value="${escapeAttr(tool.id)}" ${tool.id === selectedId ? "selected" : ""}>${escapeHtml(tool.displayName || tool.name)}</option>`).join("")}</optgroup>`;
    }).join("");
    return `${inputOptions ? `<optgroup label="Insight inputs">${inputOptions}</optgroup>` : ""}${availableOptions}`;
  }

  function apiTestFields(tool, draft) {
    const properties = tool?.paramsSchema?.properties || {};
    const required = new Set(tool?.paramsSchema?.required || []);
    const entries = Object.entries(properties);
    if (!entries.length) return '<p class="insight-api-empty">This request has no parameters. Send it to inspect the response before adding it to the dashboard.</p>';
    return `<div class="insight-api-fields">${entries.map(([name, property]) => {
      const type = property.type || "string";
      const location = tool.paramLocations?.[name] || "query";
      const requiredMark = required.has(name) ? ' <b aria-label="required">*</b>' : "";
      const value = draft.values[name] ?? "";
      const control = type === "boolean"
        ? `<select data-focus-key="api-test-${escapeAttr(name)}" data-api-test-value="${escapeAttr(name)}"><option value="false" ${value === "false" ? "selected" : ""}>false</option><option value="true" ${value === "true" ? "selected" : ""}>true</option></select>`
        : `<input data-focus-key="api-test-${escapeAttr(name)}" data-api-test-value="${escapeAttr(name)}" class="${["array", "object"].includes(type) ? "mono" : ""}" value="${escapeAttr(value)}" ${required.has(name) ? "required" : ""} placeholder="${escapeAttr(property.description || `Value for ${name}`)}">`;
      return `<label><span>${escapeHtml(name)}${requiredMark}<small>${escapeHtml(location)} · ${escapeHtml(type)}</small></span>${control}</label>`;
    }).join("")}</div>`;
  }

  function apiTestResultPanel(tool) {
    if (state.apiTestError) return `<div class="insight-api-error">${icon("alert", 14)} <span>${escapeHtml(state.apiTestError)}</span></div>`;
    const result = state.apiTestResult?.toolId === tool?.id ? state.apiTestResult.response : null;
    if (!result) return '<p class="insight-api-empty">No response yet. Send the selected request to verify the shape before using it in the dashboard.</p>';
    const status = Number(result.status);
    const successful = status >= 200 && status < 300;
    const headers = Object.entries(result.headers || {});
    const body = result.body === undefined || result.body === null || result.body === ""
      ? successful ? "Response body is empty." : `Remote API returned HTTP ${status} with an empty response body.`
      : result.body;
    return `<section class="insight-api-result">
      <header><div><span class="status-pill ${successful ? "status-active" : "status-error"}">HTTP ${escapeHtml(status || "—")}</span>${result.latencyMs !== undefined ? `<span class="mono">${escapeHtml(result.latencyMs)} ms</span>` : ""}${result.contentType ? `<span class="mono">${escapeHtml(result.contentType)}</span>` : ""}</div><button class="btn btn-ghost btn-sm" type="button" data-action="clear-api-test-result">Clear</button></header>
      ${result.request ? `<div class="insight-api-url"><span class="method-badge mono">${escapeHtml(tool?.method || "GET")}</span><code>${escapeHtml(result.request)}</code></div>` : ""}
      ${headers.length ? `<details><summary>${headers.length} response headers</summary><div class="insight-api-headers">${headers.map(([name, values]) => `<div><span class="mono">${escapeHtml(name)}</span><code>${escapeHtml(Array.isArray(values) ? values.join(", ") : values)}</code></div>`).join("")}</div></details>` : ""}
      <pre class="tool-result-body"><code>${escapeHtml(body)}</code></pre>${result.truncated ? '<p class="insight-api-empty">Response display was truncated by the server.</p>' : ""}
    </section>`;
  }

  function apiTesterPanel() {
    const requests = starterRequests();
    if (!requests.length) {
      return `<section class="insight-api-tester is-empty">${projectExplorer()}<div class="insight-api-empty-state">${icon("globe", 18)}<strong>No enabled GET requests</strong><span>Enable read requests in APIs, then test them here before adding them to an insight dashboard.</span><a class="btn btn-ghost" href="/apps">Open APIs</a></div></section>`;
    }
    const tool = syncApiTestTool();
    const connection = toolConnection(tool);
    const draft = apiTestDraft(tool);
    const inInsight = requestIsInInsight(tool);
    const sourceName = requestBindings(state.source).find((binding) => binding.label === requestLabel(tool, connection))?.name;
    return `<section class="insight-api-tester" aria-labelledby="insight-api-test-title">
      ${projectExplorer()}
      <form id="insight-api-test-form" class="insight-api-form">
        <header>
          <div><p>Request test</p><h2 id="insight-api-test-title">${escapeHtml(tool?.displayName || "Select request")}</h2><span>${escapeHtml(connection?.name || "Collection")} · ${escapeHtml(tool?.urlTemplate || "")}</span></div>
          <div class="insight-api-actions">
            <button class="btn btn-ghost" type="button" data-action="add-api-test-source" ${tool && !inInsight ? "" : "disabled"}>${icon("plus", 14)} Add as input</button>
            <button class="btn btn-primary" type="submit" ${tool && !state.apiTestSubmitting ? "" : "disabled"}>${state.apiTestSubmitting ? "Sending…" : `${icon("play", 14)} Send`}</button>
          </div>
        </header>
        <label class="insight-api-request-picker"><span>Request to test${sourceName ? `<small>Dashboard input: ${escapeHtml(sourceName)}</small>` : "<small>Available request</small>"}</span><select id="insight-api-test-request">${apiTestRequestOptions(requests, tool?.id)}</select></label>
        ${apiTestFields(tool, draft)}
      </form>
      ${apiTestResultPanel(tool)}
    </section>`;
  }

  /** Dashboard inputs are plug-in collection requests: RQL is generated underneath, not typed first. */
  function sourceDeck(usable) {
    const requests = starterRequests();
    const connectedApps = state.connections.filter((connection) => connection.status === "CONNECTED");
    if (!connectedApps.length) {
      return `<section class="insight-studio-source is-empty" aria-label="Dashboard inputs"><div>${icon("puzzle", 16)}<span><strong>Connect collections</strong><small>Connect API collections first. Insights can then combine requests from those collections into one dashboard.</small></span></div><a class="btn btn-ghost" href="/connections">Open Connections</a></section>`;
    }
    if (!requests.length) {
      return `<section class="insight-studio-source is-empty" aria-label="Dashboard inputs"><div>${icon("hash", 16)}<span><strong>No read inputs available</strong><small>Enable GET requests in APIs. Each enabled request can become a dataset in this dashboard.</small></span></div><a class="btn btn-ghost" href="/apps">Open APIs</a></section>`;
    }
    const bindings = requestBindings(state.source);
    const sourceTool = requests.find((tool) => {
      const connection = state.connections.find((item) => item.id === tool.connectionId);
      return requestLabel(tool, connection) === state.query.requestLabel;
    });
    const selected = sourceTool?.id || (requests.some((tool) => tool.id === state.starterToolId) ? state.starterToolId : requests[0].id);
    const requestOptions = connectedApps.map((connection) => {
      const items = requests.filter((tool) => tool.connectionId === connection.id);
      if (!items.length) return "";
      return `<optgroup label="${escapeAttr(connection.name || "Collection")}">${items.map((tool) => `<option value="${escapeAttr(tool.id)}" ${tool.id === selected ? "selected" : ""}>${escapeHtml(tool.displayName || tool.name)}</option>`).join("")}</optgroup>`;
    }).join("");
    const resultDatasets = datasets();
    const requestRuns = state.data?.requests || [];
    const bindingList = bindings.length
      ? `<div class="insight-source-blocks" aria-label="Dashboard input datasets">${bindings.map((binding) => {
          const parts = sourceLabelParts(binding.label);
          const dataset = resultDatasets[binding.name];
          const run = requestRuns.find((request) => request.request === binding.label || request.request.endsWith(binding.label));
          const status = dataset ? `${plural(dataset.rows.length, "row")}` : run ? (run.success ? "Fetched" : "Failed") : "Not run";
          const tone = run && !run.success ? "error" : dataset ? "ready" : "idle";
          const locked = binding.name === "rows";
          const shaping = binding.name === state.queryDataset;
          return `<div class="insight-source-block is-${tone} ${shaping ? "is-shaping" : ""}" title="${escapeAttr(binding.label)}">
            <header><span>${icon("globe", 12)}<b>${escapeHtml(binding.name)}</b><i>${escapeHtml(parts.collection)}</i></span><em>${escapeHtml(status)}</em></header>
            <label><span>Dataset</span><input data-request-name="${escapeAttr(binding.name)}" value="${escapeAttr(binding.name)}" ${locked ? "disabled" : ""} title="${locked ? "The first dataset name is kept as rows for compatibility." : "Rename this generated dataset"}"></label>
            <small><span>Request</span>${escapeHtml(parts.request)}</small>
            <div class="insight-source-block-actions"><button class="btn btn-ghost btn-sm" type="button" data-action="shape-request-source" data-dataset="${escapeAttr(binding.name)}">${icon("settings", 13)} ${shaping ? "Shaping" : "Shape"}</button><button class="btn btn-ghost btn-sm" type="button" data-action="add-dataset-table" data-dataset="${escapeAttr(binding.name)}">${icon("table", 13)} Add table</button><button class="insight-icon-button is-danger" type="button" data-action="remove-request-source" data-dataset="${escapeAttr(binding.name)}" ${locked ? "disabled" : ""} title="${locked ? "The first rows input cannot be removed here." : "Remove this input from the dashboard"}" aria-label="Remove ${escapeAttr(binding.name)}">${icon("trash", 13)}</button></div>
          </div>`;
        }).join("")}</div>`
      : '<p class="insight-source-blocks-empty">No inputs yet. Choose a GET request to create the first dataset.</p>';
    return `<section class="insight-studio-source" aria-label="Dashboard inputs">
      <label><span>Preferred collection</span><select id="insight-connection"><option value="">All connected collections</option>${usable.map((connection) => `<option value="${escapeAttr(connection.id)}" ${state.connectionId === connection.id ? "selected" : ""}>${escapeHtml(connection.name)}</option>`).join("")}</select></label>
      <label><span>Add collection request</span><select id="insight-starter-request">${requestOptions}</select></label>
      <div class="insight-studio-source-action"><span>${bindings.length ? "Add this request as another input" : "Use this request as the first dataset"}</span><button class="btn btn-primary" type="button" data-action="add-request-source" ${state.running ? "disabled" : ""}>${bindings.length ? `${icon("plus", 14)} Add input` : "Add first input"}</button></div>
      ${bindingList}
    </section>`;
  }

  function queryDatasetNames() {
    return allDatasetNames(state.source);
  }

  function syncQueryDataset() {
    const names = queryDatasetNames();
    const next = names.includes(state.queryDataset)
      ? state.queryDataset
      : (names.includes("rows") ? "rows" : names[0] || "rows");
    if (next !== state.queryDataset) {
      state.queryDataset = next;
      state.query = parseVisualQuery(state.source, next);
    }
    return next;
  }

  function setQueryDataset(name) {
    const names = queryDatasetNames();
    state.queryDataset = names.includes(name) ? name : (names.includes("rows") ? "rows" : names[0] || "rows");
    state.query = parseVisualQuery(state.source, state.queryDataset);
  }

  function availableQueryFields(datasetName = state.queryDataset) {
    const current = datasetFields(datasets()[datasetName]);
    const remembered = datasetName === "rows" && state.queryFields.length
      ? state.queryFields
      : (state.queryFieldsByDataset[datasetName] || []);
    return [...new Set([...remembered, ...current])];
  }

  function rememberQueryFields() {
    const next = { ...state.queryFieldsByDataset };
    for (const [name, dataset] of Object.entries(datasets())) {
      const fields = datasetFields(dataset);
      if (fields.length) next[name] = [...new Set([...(next[name] || []), ...fields])];
    }
    state.queryFieldsByDataset = next;
    state.queryFields = next.rows || [];
  }

  function conditionControl(rule, index, fields) {
    const operator = QUERY_OPERATORS.find((item) => item.value === rule.operator) || QUERY_OPERATORS[0];
    return `<div class="insight-query-condition" data-condition-id="${escapeAttr(rule.id)}">
      <label class="insight-query-logic"><span>${index ? "Join" : "Where"}</span><select data-query-rule="logic" ${index ? "" : "disabled"}><option value="AND" ${rule.logic === "AND" ? "selected" : ""}>AND</option><option value="OR" ${rule.logic === "OR" ? "selected" : ""}>OR</option></select></label>
      <label><span>Field</span><select data-query-rule="field">${fields.map((field) => `<option value="${escapeAttr(field)}" ${rule.field === field ? "selected" : ""}>${escapeHtml(field)}</option>`).join("")}</select></label>
      <label><span>Condition</span><select data-query-rule="operator">${QUERY_OPERATORS.map((item) => `<option value="${escapeAttr(item.value)}" ${rule.operator === item.value ? "selected" : ""}>${escapeHtml(item.label)}</option>`).join("")}</select></label>
      <label class="insight-query-value ${operator.needsValue ? "" : "is-disabled"}"><span>Value${rule.operator === "in" ? " (comma separated)" : ""}</span><input data-query-rule="value" value="${escapeAttr(rule.value || "")}" ${operator.needsValue ? "" : "disabled"} placeholder="${rule.operator === "in" ? "open, pending" : "Value"}"></label>
      <button class="insight-icon-button is-danger" type="button" data-action="remove-query-condition" data-condition-id="${escapeAttr(rule.id)}" aria-label="Remove condition">${icon("trash", 13)}</button>
    </div>`;
  }

  function datasetFieldOptions(name, selected = "") {
    const fields = datasetFields(datasets()[name]);
    if (!fields.length) return `<option value="${escapeAttr(selected)}">${escapeHtml(selected || "Run to load fields")}</option>`;
    const value = selected && fields.includes(selected) ? selected : fields[0];
    return fields.map((field) => `<option value="${escapeAttr(field)}" ${field === value ? "selected" : ""}>${escapeHtml(field)}</option>`).join("");
  }

  function normalizedKey(value) {
    return String(value || "").toLowerCase().replace(/[^a-z0-9]/g, "");
  }

  function singular(value) {
    const text = String(value || "");
    if (text.endsWith("ies")) return `${text.slice(0, -3)}y`;
    if (text.endsWith("s") && text.length > 1) return text.slice(0, -1);
    return text;
  }

  function syncRelationshipDraft() {
    const names = requestBindings(state.source).map((binding) => binding.name);
    if (names.length < 2) return null;
    const left = names.includes(state.relationship.left) ? state.relationship.left : (names.includes("rows") ? "rows" : names[0]);
    const right = names.includes(state.relationship.right) && state.relationship.right !== left
      ? state.relationship.right
      : names.find((name) => name !== left);
    const leftFields = datasetFields(datasets()[left]);
    const rightFields = datasetFields(datasets()[right]);
    const common = leftFields.find((field) => rightFields.includes(field));
    const rightNames = [right, singular(right)].map(normalizedKey);
    const likelyLeft = leftFields.find((field) => rightNames.some((name) => normalizedKey(field) === `${name}id`))
      || common || leftFields.find((field) => /(^|_)id$/i.test(field)) || leftFields[0] || state.relationship.leftField || "id";
    const likelyRight = rightFields.find((field) => normalizedKey(field) === normalizedKey(likelyLeft))
      || rightFields.find((field) => /(^|_)id$/i.test(field)) || rightFields[0] || state.relationship.rightField || "id";
    const leftField = leftFields.includes(state.relationship.leftField) ? state.relationship.leftField : likelyLeft;
    const rightField = rightFields.includes(state.relationship.rightField) ? state.relationship.rightField : likelyRight;
    const name = state.relationship.name || nextRelationshipName(state.source, left, right);
    const prefix = state.relationship.prefix || right;
    state.relationship = { ...state.relationship, left, right, leftField, rightField, name, prefix };
    return state.relationship;
  }

  function relationshipPreview(draft, relationships) {
    const resultDatasets = datasets();
    const leftDataset = resultDatasets[draft.left];
    const rightDataset = resultDatasets[draft.right];
    const leftFields = datasetFields(leftDataset);
    const rightFields = datasetFields(rightDataset);
    const leftReady = leftFields.includes(draft.leftField);
    const rightReady = rightFields.includes(draft.rightField);
    const output = identifier(draft.name, nextRelationshipName(state.source, draft.left, draft.right));
    const conflicts = allDatasetNames(state.source).includes(output) && output !== draft.editing;
    const tone = conflicts || (!leftReady && leftFields.length) || (!rightReady && rightFields.length)
      ? "warning"
      : leftReady && rightReady ? "ready" : "idle";
    const leftRows = leftDataset ? plural(leftDataset.rows.length, "left row") : "run left request";
    const rightRows = rightDataset ? plural(rightDataset.rows.length, "match row") : "run match request";
    const relationshipCount = relationships.length ? plural(relationships.length, "relationship") : "no relationships yet";
    const note = conflicts
      ? `Output "${output}" already exists. Choose another dataset name.`
      : leftReady && rightReady
        ? `Ready to left join ${draft.left}.${draft.leftField} to ${draft.right}.${draft.rightField}.`
        : "Run the dashboard once to validate the selected key fields.";
    return `<div class="insight-relationship-preview is-${tone}" aria-live="polite">
      <span>${icon(tone === "warning" ? "alert" : "route", 14)}</span>
      <div><strong>${escapeHtml(note)}</strong><small>${escapeHtml(leftRows)} · ${escapeHtml(rightRows)} · ${escapeHtml(relationshipCount)}</small></div>
    </div>`;
  }

  function relationshipBuilder() {
    const names = requestBindings(state.source).map((binding) => binding.name);
    const relationships = relationshipBindings(state.source);
    if (names.length < 2) {
      return `<section class="insight-studio-stage is-relate"><header><div><span>02</span><h3>Model relationships</h3><p>Add at least two dashboard inputs to create a joined dataset.</p></div></header></section>`;
    }
    const draft = syncRelationshipDraft();
    const datasetOptions = (selected, exclude = "") => names
      .filter((name) => name !== exclude)
      .map((name) => `<option value="${escapeAttr(name)}" ${name === selected ? "selected" : ""}>${escapeHtml(name)}</option>`).join("");
    const editing = draft.editing && relationships.some((item) => item.name === draft.editing);
    return `<section class="insight-studio-stage is-relate"><header><div><span>02</span><h3>Model relationships</h3><p>Create joined datasets by matching keys across dashboard inputs.</p></div><button class="btn btn-ghost btn-sm" type="button" data-action="add-relationship" ${draft ? "" : "disabled"}>${icon("route", 13)} ${editing ? "Update relationship" : "Create relationship"}</button></header>
      <div class="insight-relationship-form">
        <label><span>From dataset</span><select data-relationship="left">${datasetOptions(draft.left, draft.right)}</select></label>
        <label><span>From key</span><select data-relationship="leftField">${datasetFieldOptions(draft.left, draft.leftField)}</select></label>
        <label><span>Match dataset</span><select data-relationship="right">${datasetOptions(draft.right, draft.left)}</select></label>
        <label><span>Match key</span><select data-relationship="rightField">${datasetFieldOptions(draft.right, draft.rightField)}</select></label>
        <label><span>Output dataset</span><input data-relationship="name" value="${escapeAttr(draft.name)}"></label>
        <label><span>Matched-field prefix</span><input data-relationship="prefix" value="${escapeAttr(draft.prefix)}"></label>
      </div>
      ${relationshipPreview(draft, relationships)}
      ${relationships.length ? `<div class="insight-relationship-list" aria-label="Dashboard relationship datasets">${relationships.map((item) => {
        const result = datasets()[item.name];
        const status = result ? plural(result.rows.length, "row") : (item.visualStages.length ? "Shaped · not run" : "Not run");
        const shaping = item.name === state.queryDataset;
        return `<div class="insight-relationship-card ${item.name === draft.editing ? "is-editing" : ""} ${shaping ? "is-shaping" : ""}"><span>${icon("route", 13)}<b>${escapeHtml(item.name)}</b><em>${escapeHtml(status)}</em></span><small>${escapeHtml(item.left)}.${escapeHtml(item.leftField)} → ${escapeHtml(item.right)}.${escapeHtml(item.rightField)}</small><div class="insight-relationship-actions"><button class="btn btn-ghost btn-sm" type="button" data-action="shape-request-source" data-dataset="${escapeAttr(item.name)}">${icon("settings", 13)} ${shaping ? "Shaping" : "Shape"}</button><button class="btn btn-ghost btn-sm" type="button" data-action="edit-relationship" data-relationship-name="${escapeAttr(item.name)}">${icon("settings", 13)} Edit</button><button class="btn btn-ghost btn-sm" type="button" data-action="add-dataset-table" data-dataset="${escapeAttr(item.name)}">${icon("table", 13)} Add table</button><button class="insight-icon-button is-danger" type="button" data-action="remove-relationship" data-relationship-name="${escapeAttr(item.name)}" aria-label="Remove ${escapeAttr(item.name)}">${icon("trash", 13)}</button></div></div>`;
      }).join("")}</div>` : '<p class="insight-studio-empty-rule">No relationships yet. Create one to produce a joined dataset for this dashboard.</p>'}
    </section>`;
  }

  function projectExplorer() {
    const inputs = requestBindings(state.source);
    const relationships = relationshipBindings(state.source);
    const datasetNames = allDatasetNames(state.source);
    const resultDatasets = datasets();
    const visualCounts = new Map();
    for (const component of state.outline || []) {
      if (!CATALOG[component.type]?.needsData) continue;
      const data = datasetName(component);
      if (!data) continue;
      visualCounts.set(data, (visualCounts.get(data) || 0) + 1);
    }
    const inputItems = inputs.length
      ? inputs.map((input) => {
          const parts = sourceLabelParts(input.label);
          const dataset = resultDatasets[input.name];
          const rows = dataset ? plural(dataset.rows.length, "row") : "not run";
          const shaping = input.name === state.queryDataset;
          return `<li><button class="${shaping ? "is-active" : ""}" type="button" data-action="shape-request-source" data-dataset="${escapeAttr(input.name)}"><span>${icon("globe", 12)}<b>${escapeHtml(input.name)}</b><i>${escapeHtml(rows)}</i></span><small>${escapeHtml(parts.collection)} · ${escapeHtml(parts.request)}</small></button></li>`;
        }).join("")
      : `<li class="is-empty"><span>${icon("globe", 12)}<b>No request datasets</b></span><small>Add a GET request above.</small></li>`;
    const relationshipItems = relationships.length
      ? relationships.map((relationship) => {
          const rows = resultDatasets[relationship.name] ? plural(resultDatasets[relationship.name].rows.length, "row") : "not run";
          const shaping = relationship.name === state.queryDataset;
          return `<li><button class="${shaping ? "is-active" : ""}" type="button" data-action="shape-request-source" data-dataset="${escapeAttr(relationship.name)}"><span>${icon("route", 12)}<b>${escapeHtml(relationship.name)}</b><i>${escapeHtml(rows)}</i></span><small>${escapeHtml(relationship.left)}.${escapeHtml(relationship.leftField)} → ${escapeHtml(relationship.right)}.${escapeHtml(relationship.rightField)}</small></button></li>`;
        }).join("")
      : `<li class="is-empty"><span>${icon("route", 12)}<b>No relationships</b></span><small>Add another dataset to join fields.</small></li>`;
    const visualItems = datasetNames.length
      ? datasetNames.map((name) => {
          const count = visualCounts.get(name) || 0;
          return `<li><span>${icon(count ? "table" : "file", 12)}<b>${escapeHtml(name)}</b><i>${escapeHtml(count ? plural(count, "visual") : "unused")}</i></span><small>${escapeHtml(resultDatasets[name] ? plural(resultDatasets[name].rows.length, "row") : "Run to load rows")}</small></li>`;
        }).join("")
      : `<li class="is-empty"><span>${icon("file", 12)}<b>No visuals yet</b></span><small>Add a table or chart to bind data.</small></li>`;
    return `<section class="insight-project-explorer" aria-label="Project explorer">
      <header><small>Project Explorer</small><strong>${escapeHtml(state.name || "Untitled insight")}</strong></header>
      <div class="insight-project-tree">
        <details open><summary>${icon("globe", 12)} <span>Request datasets</span><em>${inputs.length}</em></summary><ul>${inputItems}</ul></details>
        <details open><summary>${icon("route", 12)} <span>Relationships</span><em>${relationships.length}</em></summary><ul>${relationshipItems}</ul></details>
        <details open><summary>${icon("table", 12)} <span>Visual bindings</span><em>${[...visualCounts.values()].reduce((total, count) => total + count, 0)}</em></summary><ul>${visualItems}</ul></details>
      </div>
    </section>`;
  }

  /** The dataset map is useful once work exists, but it should not interrupt the first-input path. */
  function projectExplorerDrawer() {
    const inputs = requestBindings(state.source);
    if (!inputs.length) return "";
    const relationships = relationshipBindings(state.source);
    const visuals = (state.outline || []).filter((component) => CATALOG[component.type]?.needsData).length;
    return `<details class="insight-project-drawer">
      <summary>${icon("route", 13)} <span>Dataset map</span><small>${plural(inputs.length, "input")} · ${plural(visuals, "visual")}${relationships.length ? ` · ${plural(relationships.length, "relationship")}` : ""}</small></summary>
      ${projectExplorer()}
    </details>`;
  }

  function visualQueryBuilder() {
    if (state.mode !== "design") return "";
    const datasetName = syncQueryDataset();
    const query = state.query;
    const fields = availableQueryFields(datasetName);
    const requestNames = queryDatasetNames();
    const datasetSelect = requestNames.length > 1
      ? `<label class="insight-query-dataset"><span>Shape dataset</span><select id="insight-query-dataset">${requestNames.map((name) => `<option value="${escapeAttr(name)}" ${name === datasetName ? "selected" : ""}>${escapeHtml(name)}</option>`).join("")}</select></label>`
      : "";
    if (state.source === EMPTY_INSIGHT) {
      return "";
    }
    if (!query.managed) {
      return `<section class="insight-studio-query is-custom" aria-labelledby="insight-query-title">
        ${projectExplorerDrawer()}
        <div class="insight-studio-query-intro"><p>Dataset designer</p><h2 id="insight-query-title">${escapeHtml(datasetName)} has custom logic</h2><span>Switch back to controls when you want the plug-and-play editor.</span></div>
        <div class="insight-studio-query-actions">${datasetSelect}<button class="btn btn-ghost" type="button" data-action="open-source">Open generated RQL</button><button class="btn" type="button" data-action="reset-visual-query">Use IDE controls</button></div>
      </section>`;
    }
    if (!fields.length) {
      return `<section class="insight-studio-query is-waiting" aria-labelledby="insight-query-title">
        ${projectExplorerDrawer()}
        <div class="insight-studio-query-intro"><p>Dataset designer</p><h2 id="insight-query-title">Load ${escapeHtml(datasetName)} fields</h2><span>Run once to expose fields, filters, relationship keys, and visual bindings.</span></div>
        <div class="insight-studio-query-actions">${datasetSelect}<button class="btn btn-primary" type="button" data-action="run-query" ${state.running ? "disabled" : ""}>${state.running ? "Loading…" : "Load fields"}</button></div>
      </section>`;
    }
    const outputFields = query.groupField ? [query.groupField, aggregateAlias(query)] : fields;
    const selected = new Set(query.columns);
    const datasetRows = datasets()[datasetName]?.rows || [];
    const numeric = fields.filter((field) => isNumericColumn(datasetRows, field));
    return `<section class="insight-studio-query" aria-labelledby="insight-query-title">
      ${projectExplorerDrawer()}
      <header class="insight-studio-query-header"><div class="insight-studio-query-intro"><p>Dataset designer</p><h2 id="insight-query-title">${escapeHtml(datasetName)} · ${escapeHtml(query.requestLabel)}</h2><span>Shape this dataset for the dashboard.</span></div><div class="insight-studio-query-actions">${datasetSelect}<button class="btn btn-ghost" type="button" data-action="reset-query">Reset</button><button class="btn btn-primary" type="button" data-action="run-query" ${state.running ? "disabled" : ""}>${state.running ? "Running…" : `${icon("play", 14)} Run`}</button></div></header>
      <div class="insight-studio-stages">
        ${relationshipBuilder()}
        <section class="insight-studio-stage is-project"><header><div><span>03</span><h3>Shape selected dataset</h3><p>Choose fields exposed by the currently selected dashboard dataset.</p></div><button type="button" data-action="query-select-all">All fields</button></header>
          <div class="insight-studio-field-list" role="group" aria-label="Result columns">${fields.map((field) => `<button type="button" class="insight-studio-field ${!selected.size || selected.has(field) ? "is-selected" : ""}" data-action="toggle-query-column" data-field="${escapeAttr(field)}" aria-pressed="${!selected.size || selected.has(field)}">${icon(!selected.size || selected.has(field) ? "check" : "plus", 12)}<span>${escapeHtml(field)}</span></button>`).join("")}</div>
        </section>
        <section class="insight-studio-stage is-filter"><header><div><span>04</span><h3>Filter</h3><p>Keep rows that matter for the dashboard story.</p></div><button class="btn btn-ghost btn-sm" type="button" data-action="add-query-condition">${icon("plus", 13)} Add rule</button></header>
          <div class="insight-studio-rules">${query.filters.length ? query.filters.map((rule, index) => conditionControl(rule, index, fields)).join("") : '<p class="insight-studio-empty-rule">No rules. The query will include all rows.</p>'}</div>
        </section>
        <section class="insight-studio-stage is-transform"><header><div><span>05</span><h3>Summarize</h3><p>Aggregate, sort, and cap this dataset before visuals bind to it.</p></div></header>
          <div class="insight-studio-transform-controls">
            <label><span>Group rows by</span><select data-query="groupField"><option value="">No grouping</option>${fields.map((field) => `<option value="${escapeAttr(field)}" ${query.groupField === field ? "selected" : ""}>${escapeHtml(field)}</option>`).join("")}</select></label>
            <label><span>Calculation</span><select data-query="aggregateFunction" ${query.groupField ? "" : "disabled"}>${["count", "sum", "avg", "min", "max"].map((fn) => `<option value="${fn}" ${query.aggregateFunction === fn ? "selected" : ""}>${fn.toUpperCase()}</option>`).join("")}</select></label>
            <label><span>Measure</span><select data-query="aggregateField" ${query.groupField && query.aggregateFunction !== "count" ? "" : "disabled"}><option value="*">Rows</option>${(numeric.length ? numeric : fields).map((field) => `<option value="${escapeAttr(field)}" ${query.aggregateField === field ? "selected" : ""}>${escapeHtml(field)}</option>`).join("")}</select></label>
            <label><span>Sort by</span><select data-query="sortField"><option value="">Source order</option>${outputFields.map((field) => `<option value="${escapeAttr(field)}" ${query.sortField === field ? "selected" : ""}>${escapeHtml(field)}</option>`).join("")}</select></label>
            <label><span>Direction</span><select data-query="sortDirection" ${query.sortField ? "" : "disabled"}><option value="asc" ${query.sortDirection === "asc" ? "selected" : ""}>Ascending</option><option value="desc" ${query.sortDirection === "desc" ? "selected" : ""}>Descending</option></select></label>
            <label><span>Maximum rows</span><input data-query="limit" type="number" min="1" max="10000" value="${escapeAttr(query.limit)}"></label>
            <label class="insight-studio-toggle"><input data-query="distinct" type="checkbox" ${query.distinct ? "checked" : ""}><span>Deduplicate rows</span></label>
          </div>
        </section>
      </div>
      <details class="insight-studio-plan"><summary>${icon("hash", 13)} Generated plan <span>Preview</span></summary><pre>${escapeHtml(sqlQueryPreview(query))}</pre></details>
    </section>`;
  }

  function completionItems() {
    if (state.mode !== "code") return [];
    return (state.analysis?.completions || []).filter((completion) => completion?.replaceSpan
      && Number.isFinite(completion.replaceSpan.startOffset)
      && Number.isFinite(completion.replaceSpan.endOffset));
  }

  function completionMenu(items) {
    if (!items.length) return "";
    const active = Math.max(0, Math.min(state.completionIndex, items.length - 1));
    return `<div id="insight-completions" class="insight-completions" role="listbox" aria-label="Code suggestions">
      <div class="insight-completions-header"><span>Suggestions</span><small>↑↓ to choose · Tab or Enter to insert · Esc to close</small></div>
      ${items.map((completion, index) => `<button type="button" id="insight-completion-${index}" class="insight-completion ${index === active ? "is-active" : ""}" role="option" aria-selected="${index === active}" data-action="accept-completion" data-completion-index="${index}">
        <code>${escapeHtml(completion.label)}</code><span class="insight-completion-kind">${escapeHtml(completion.kind)}</span><small>${escapeHtml(completion.detail)}</small>
      </button>`).join("")}
    </div>`;
  }

  function editorPanel() {
    const completions = completionItems();
    const active = Math.max(0, Math.min(state.completionIndex, Math.max(0, completions.length - 1)));
    return `<section class="insight-editor-panel"><header><span>insight.rqd</span><small>Markdown · RQL · components</small></header><div class="insight-editor-body"><textarea id="insight-source" data-focus-key="source" class="insight-plain-editor" spellcheck="false" aria-autocomplete="${completions.length ? "list" : "none"}" ${completions.length ? `aria-controls="insight-completions" aria-activedescendant="insight-completion-${active}"` : ""}>${escapeHtml(state.source)}</textarea>${completionMenu(completions)}</div><footer>${diagnostics()}</footer></section>`;
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
          const hint = blocked
            ? `${spec.label} — run the insight first, so there is a dataset to bind`
            : spec.description || `Add a ${spec.label.toLowerCase()}`;
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
    if (!component) return '<p class="insight-rail-empty"><strong>How this works</strong><br>Add dashboard inputs, model any relationships, run the dashboard, then select a table or chart on the canvas. Result columns appear here for Data table.</p>';
    const spec = CATALOG[component.type];
    if (!spec) return `<p class="insight-rail-empty">&lt;${escapeHtml(component.type)}/&gt; is not a component this build knows. Fix it in Code.</p>`;
    if (component.type === "Prose") return '<p class="insight-rail-empty">Prose is written in Markdown — switch to Code to edit this block.</p>';
    const names = Object.keys(datasets());
    const bound = datasetName(component);
    const dataset = datasets()[bound];
    const nothingToConfigure = !(spec.wells || []).length && !(spec.format || []).length && !spec.needsData;
    const grid = dashboardGridSettings(state.source);
    const fallback = nextAutoGridSlot({ x: 0, y: 0, rowH: 0 }, grid, component);
    const layout = componentGridLayout(component, grid, fallback);
    const layoutControls = isGridComponent(component)
      ? `<section class="insight-grid-layout-controls" aria-label="Selected visual grid placement">
          <header><span>Grid placement</span><small>${escapeHtml(layout.w)}×${escapeHtml(layout.h)} at ${escapeHtml(layout.x)},${escapeHtml(layout.y)}</small></header>
          <div class="insight-grid-nudges" role="group" aria-label="Move selected visual">
            <button class="insight-icon-button" type="button" data-action="grid-nudge" data-dx="0" data-dy="-1" title="Move up" aria-label="Move up">${icon("arrowUp", 13)}</button>
            <button class="insight-icon-button" type="button" data-action="grid-nudge" data-dx="-1" data-dy="0" title="Move left" aria-label="Move left">←</button>
            <button class="insight-icon-button" type="button" data-action="grid-nudge" data-dx="1" data-dy="0" title="Move right" aria-label="Move right">→</button>
            <button class="insight-icon-button" type="button" data-action="grid-nudge" data-dx="0" data-dy="1" title="Move down" aria-label="Move down">${icon("arrowDown", 13)}</button>
          </div>
          <div class="insight-grid-size-controls">
            <label><span>X</span><input data-grid-layout="gridX" type="number" min="0" max="${grid.columns - 1}" value="${escapeAttr(layout.x)}"></label>
            <label><span>Y</span><input data-grid-layout="gridY" type="number" min="0" max="200" value="${escapeAttr(layout.y)}"></label>
            <label><span>W</span><input data-grid-layout="gridW" type="number" min="1" max="${grid.columns}" value="${escapeAttr(layout.w)}"></label>
            <label><span>H</span><input data-grid-layout="gridH" type="number" min="1" max="12" value="${escapeAttr(layout.h)}"></label>
          </div>
        </section>`
      : "";
    return `<div class="insight-format">
      <div class="insight-format-head"><span title="${escapeAttr(`<${component.type}/>`)}">${icon(spec.icon, 14)} ${escapeHtml(spec.label)}</span>
        <div class="insight-format-tools">
          <button class="insight-icon-button" type="button" data-action="move-component" data-dir="-1" title="Move up" aria-label="Move up">${icon("arrowUp", 13)}</button>
          <button class="insight-icon-button" type="button" data-action="move-component" data-dir="1" title="Move down" aria-label="Move down">${icon("arrowDown", 13)}</button>
          <button class="insight-icon-button is-danger" type="button" data-action="delete-component" title="Remove from the document" aria-label="Remove from the document">${icon("trash", 13)}</button>
        </div>
      </div>
      ${spec.description ? `<p class="insight-format-note">${escapeHtml(spec.description)}</p>` : ""}
      ${layoutControls}
      ${spec.needsData ? `<div class="insight-well"><span class="insight-well-label">Data</span><select data-focus-key="well-data" data-well-select="data"><option value="">Choose a dataset…</option>${names.map((name) => `<option value="${escapeAttr(name)}" ${name === bound ? "selected" : ""}>${escapeHtml(name)}</option>`).join("")}</select></div>` : ""}
      ${(spec.wells || []).map((well) => wellControl(component, well, dataset)).join("")}
      ${(spec.format || []).map((field) => `<label class="insight-format-field"><span>${escapeHtml(field.label)}</span><input data-focus-key="format-${escapeAttr(field.prop)}" data-format="${escapeAttr(field.prop)}" class="${field.kind === "expr" ? "mono" : ""}" value="${escapeAttr(component.props[field.prop] ?? "")}" placeholder="${escapeAttr(field.kind === "expr" ? "expression" : "text")}"></label>`).join("")}
      ${component.type === "DataTable" ? '<details class="insight-filter-guide"><summary>Filter rows</summary><p>Open Source and add a pipeline after the request, for example <code>|&gt; where status = "open"</code>. The Columns control above only chooses what the table shows.</p><button class="btn btn-ghost btn-sm" type="button" data-action="open-source">Open Source</button></details>' : ""}
      ${spec.needsData && !dataset ? '<p class="insight-rail-note">Nothing plotted yet — bind a dataset, then run.</p>' : ""}
      ${nothingToConfigure ? '<p class="insight-rail-note">This component reads no props; it renders from the run itself.</p>' : ""}
    </div>`;
  }

  function gridToolbar() {
    const grid = dashboardGridSettings(state.source);
    const visuals = (state.outline || []).filter(isGridComponent).length;
    if (!visuals) return "";
    return `<div class="insight-grid-toolbar" aria-label="Dashboard grid controls">
      <div><small>Grid</small><strong>${escapeHtml(grid.columns)} columns · ${escapeHtml(grid.rowHeight)}px rows · ${escapeHtml(grid.gap)}px gap</strong></div>
      <label><span>Columns</span><select data-grid-setting="columns">${[6, 8, 12, 16].map((value) => `<option value="${value}" ${grid.columns === value ? "selected" : ""}>${value}</option>`).join("")}</select></label>
      <label><span>Row</span><input data-grid-setting="rowHeight" type="number" min="${GRID_LIMITS.rowHeight[0]}" max="${GRID_LIMITS.rowHeight[1]}" step="4" value="${escapeAttr(grid.rowHeight)}"></label>
      <label><span>Gap</span><input data-grid-setting="gap" type="number" min="${GRID_LIMITS.gap[0]}" max="${GRID_LIMITS.gap[1]}" step="2" value="${escapeAttr(grid.gap)}"></label>
      <button class="btn btn-ghost btn-sm" type="button" data-action="grid-auto-arrange" ${visuals ? "" : "disabled"}>${icon("settings", 13)} Auto-arrange</button>
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

  /** Visual configuration is contextual: it appears after data exists, and opens when a visual is selected. */
  function visualToolbar() {
    const component = selectedComponent();
    const spec = component ? CATALOG[component.type] : null;
    const selectionLabel = spec ? `Editing ${spec.label}` : "Select a visual to edit";
    return `<details class="insight-studio-visual-workspace" ${component ? "open" : ""}>
      <summary><span>${icon("chartBar", 14)} <strong>Build a visual</strong></span><small>${escapeHtml(selectionLabel)}</small></summary>
      <div class="insight-studio-visual-workspace-body" aria-label="Visualization builder">
        <div class="insight-studio-visual-picker" role="toolbar" aria-label="Add visual">${visualPicker()}</div>
        ${gridToolbar()}
        <details class="insight-studio-visual-detail" ${component ? "open" : ""}>
          <summary>${icon("settings", 13)} <span>Properties</span></summary>
          <div class="insight-studio-visual-detail-panel">
            <section><header>Selected visual</header>${formatPane()}</section>
            <details><summary>Fields available to visuals</summary>${fieldsPane()}</details>
          </div>
        </details>
      </div>
    </details>`;
  }

  function libraryDrawer() {
    return `<details class="insight-studio-library" ${state.activeId ? "open" : ""}>
      <summary><span>${icon("file", 14)} <span>Saved insights<small>Reusable query documents</small></span></span><em>${state.saved.length}</em></summary>
      ${state.saved.length ? `<ul>${state.saved.map((insight) => `<li><button class="insight-library-item ${insight.id === state.activeId ? "is-active" : ""}" type="button" data-action="open-insight" data-id="${escapeAttr(insight.id)}"><strong>${escapeHtml(insight.name)}</strong>${insight.description ? `<small>${escapeHtml(insight.description)}</small>` : ""}</button><button class="insight-library-delete" type="button" data-action="delete-insight" data-id="${escapeAttr(insight.id)}" aria-label="Delete ${escapeAttr(insight.name)}">${icon("trash", 13)}</button></li>`).join("")}</ul>` : '<p>Save a query to keep it here.</p>'}
    </details>`;
  }

  function activeAuthorTab() {
    if (state.mode === "code") return "source";
    return AUTHOR_TABS.includes(state.authorTab) ? state.authorTab : "compose";
  }

  function authorTabId(tab) {
    return `insight-author-tab-${tab}`;
  }

  function authorPanelId(tab) {
    return `insight-author-panel-${tab}`;
  }

  function authorTabs() {
    const current = activeAuthorTab();
    return `<div class="insight-author-tabs" role="tablist" aria-label="Dashboard editor sections">${AUTHOR_TABS.map((tab) => `<button id="${authorTabId(tab)}" class="${tab === current ? "is-active" : ""}" type="button" role="tab" aria-selected="${tab === current}" aria-controls="${authorPanelId(tab)}" tabindex="${tab === current ? "0" : "-1"}" data-action="set-author-tab" data-tab="${escapeAttr(tab)}">${icon(AUTHOR_TAB_ICONS[tab], 13)} ${escapeHtml(AUTHOR_TAB_LABELS[tab])}</button>`).join("")}</div>`;
  }

  function authorHeaderMeta() {
    const tab = activeAuthorTab();
    if (tab === "api") return { kicker: "Request test", title: "Test a selected request", detail: "" };
    if (tab === "source") return { kicker: "Generated source", title: "RQL document", detail: "" };
    const fieldCount = availableQueryFields(syncQueryDataset()).length;
    return {
      kicker: "Composition plan",
      title: "Assemble one dashboard",
      detail: fieldCount ? `${fieldCount} fields in ${state.queryDataset}` : "Run once to load fields",
    };
  }

  function authorContent(usable) {
    const tab = activeAuthorTab();
    const hasInputs = requestBindings(state.source).length > 0;
    const hasData = Object.keys(datasets()).length > 0;
    const firstStep = `<section class="insight-first-step" aria-labelledby="insight-first-step-title"><p>Step 1 of 3</p><h2 id="insight-first-step-title">Add your first data source</h2><span>Choose a connected GET request. Run it once, then build the visual you need.</span></section>`;
    const content = tab === "api"
      ? apiTesterPanel()
      : tab === "source"
        ? editorPanel()
        : hasInputs
          ? `${sourceDeck(usable)}${parameterControls()}${visualQueryBuilder()}${hasData ? visualToolbar() : ""}`
          : `${firstStep}${sourceDeck(usable)}`;
    return `<div id="${authorPanelId(tab)}" class="insight-author-panel" role="tabpanel" aria-labelledby="${authorTabId(tab)}">${content}</div>`;
  }

  function insightListPanel() {
    return `<aside class="insight-studio-list" aria-label="Saved insights">
      <header class="insight-panel-header">
        <div><small>Saved</small><strong>Insights</strong></div>
        <button class="btn btn-sm" type="button" data-action="new-insight">${icon("plus", 14)} Create</button>
      </header>
      ${state.saved.length ? `<ul>${state.saved.map((insight) => `<li><button class="insight-library-item ${insight.id === state.activeId ? "is-active" : ""}" type="button" data-action="open-insight" data-id="${escapeAttr(insight.id)}"><strong>${escapeHtml(insight.name)}</strong>${insight.description ? `<small>${escapeHtml(insight.description)}</small>` : ""}</button></li>`).join("")}</ul>` : `<div class="insight-list-empty">${icon("file", 20)}<strong>No insights yet</strong><span>Create one dashboard from multiple requests and collections.</span></div>`}
    </aside>`;
  }

  /**
   * What the result panel shows. Design mode always draws the outline — including components whose
   * dataset has not been fetched — because an empty canvas cannot be edited; the read-only modes keep
   * offering the Run button until there is a result, which is the more useful next step there.
   */
  function canvas() {
    const data = { datasets: datasets(), requests: state.data?.requests || [], outline: state.outline };
    if (state.mode === "design") return renderInsight(data, { editable: true, selected: state.selected, grid: dashboardGridSettings(state.source) });
    return state.data ? renderInsight(data, { grid: dashboardGridSettings(state.source) }) : emptyPreview();
  }

  function outputPanelClass({ readonly = false } = {}) {
    const isDesign = state.mode === "design";
    return ["insight-studio-output", isDesign ? "is-design" : "", readonly ? "is-readonly" : "", state.running ? "is-running" : ""]
      .filter(Boolean)
      .join(" ");
  }

  function outputPanelContent({ readonly = false } = {}) {
    const isDesign = state.mode === "design";
    const canEdit = Boolean(state.activeId || state.source !== EMPTY_INSIGHT);
    const title = readonly ? (state.activeId ? state.name : "Select or create an insight") : (isDesign ? "Preview & visuals" : "Insight result");
    const canvasContent = isDesign
      ? `<div class="insight-dashboard-device" aria-label="Auto-sized dashboard preview"><div class="insight-dashboard-device-screen">${canvas()}</div></div>`
      : canvas();
    return `<header class="insight-panel-header">
      <div><small>${readonly ? "Dashboard" : "Result canvas"}</small><strong>${escapeHtml(title)}</strong></div>
      <div>
        <span aria-live="polite">${state.running ? "Running…" : previewStatus()}</span>
        ${readonly ? `<button class="btn btn-ghost" type="button" data-action="edit-insight" ${canEdit ? "" : "disabled"}>${icon("wand", 15)} Edit</button>` : ""}
        <button class="insight-run-button" type="button" data-action="run-insight" ${state.running || !canEdit ? "disabled" : ""} title="Run insight (${SHORTCUT_KEY}Enter)">${state.running ? '<span class="insight-run-spinner" aria-hidden="true"></span>' : icon("play", 15)} ${state.running ? "Running…" : "Run"}</button>
      </div>
    </header>
    <div class="insight-studio-output-body">${runProgress()}${state.error ? `<div class="insight-run-error">${icon("alert", 15)} <span>${escapeHtml(state.error)}</span><button class="insight-error-dismiss" type="button" data-action="dismiss-banner" aria-label="Dismiss error">${icon("close", 13)}</button></div>` : ""}${state.runNote ? `<p class="insight-run-note">${escapeHtml(state.runNote)}</p>` : ""}${canvasContent}</div>`;
  }

  function outputPanel({ readonly = false } = {}) {
    return `<section class="${outputPanelClass({ readonly })}" aria-busy="${state.running}">${outputPanelContent({ readonly })}</section>`;
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

  function mastheadContent(documentState) {
    return `<div class="insight-studio-heading"><span class="insight-studio-heading-mark" aria-hidden="true">${icon("wand", 16)}</span><div class="insight-studio-heading-copy"><p>Dashboard IDE</p><div class="insight-studio-title-row"><h1 id="insight-page-title">Insights</h1><span class="insight-studio-document-state ${documentState.tone}" aria-live="polite">${escapeHtml(documentState.label)}</span></div></div><label class="insight-title-field"><span>Dashboard name</span><input id="insight-name" value="${escapeAttr(state.name)}" aria-label="Dashboard name"></label></div>
      <div class="insight-studio-masthead-actions"><button class="btn btn-ghost" type="button" data-action="back-to-preview" title="Return to the insight preview">Back to preview</button><button class="btn btn-ghost" type="button" data-action="toggle-auto-save" aria-pressed="${state.autoSave}" title="Automatically save changes after you pause editing">Auto-save: ${state.autoSave ? "On" : "Off"}</button><button class="btn btn-ghost" type="button" data-action="new-insight" title="Start a new insight">${icon("plus", 15)} Create</button><button class="btn" type="button" data-action="save-insight" ${state.saving || state.running || (state.activeId && !hasUnsavedChanges(state)) ? "disabled" : ""} title="Save insight (${SHORTCUT_KEY}S)">${icon("save", 15)} ${state.saving ? "Saving…" : "Save"}</button></div>`;
  }

  function buildPanelContent(usable) {
    const authorMeta = authorHeaderMeta();
    return `<header><div><small>${escapeHtml(authorMeta.kicker)}</small><strong>${escapeHtml(authorMeta.title)}</strong></div>${authorTabs()}${authorMeta.detail ? `<span>${escapeHtml(authorMeta.detail)}</span>` : ""}</header>${authorContent(usable)}`;
  }

  function authorShell() {
    return `<section class="insight-studio-page is-${state.mode}" aria-labelledby="insight-page-title">
      <header class="insight-studio-masthead" data-region="masthead"></header>
      <div data-region="hidden-diagnostics"></div>
      <main class="insight-studio-workbench is-${state.mode}">
        <section class="insight-studio-build" aria-label="Dashboard authoring" data-region="build-panel"></section>
        <section class="insight-studio-output" aria-busy="false" data-region="output-panel"></section>
      </main>
      <div data-region="status-bar"></div>
    </section>`;
  }

  function syncRegion(name, html, { className = null, attributes = {} } = {}) {
    const region = outlet.querySelector(`[data-region="${name}"]`);
    if (!region) return;
    const signature = `${className || region.className}::${JSON.stringify(attributes)}::${html}`;
    if (region.dataset.renderSignature === signature) return;
    if (className && region.className !== className) region.className = className;
    for (const [attribute, value] of Object.entries(attributes)) {
      if (value === false || value === null || value === undefined) region.removeAttribute(attribute);
      else region.setAttribute(attribute, String(value));
    }
    region.innerHTML = html;
    region.dataset.renderSignature = signature;
  }

  function ensureAuthorShell() {
    const shell = `author:${state.mode}`;
    if (outlet.dataset.insightShell === shell) return;
    outlet.dataset.insightShell = shell;
    outlet.innerHTML = authorShell();
  }

  function setAuthorTab(tab, { focusSource = false, focusTab = false } = {}) {
    if (!AUTHOR_TABS.includes(tab)) return;
    if (tab !== activeAuthorTab()) {
      state.authorTab = tab;
      if (state.mode === "code") state.mode = "design";
      if (tab === "compose") setQueryDataset(state.queryDataset);
      render();
    }
    if (focusTab) outlet.querySelector(`#${CSS.escape(authorTabId(tab))}`)?.focus({ preventScroll: true });
    else if (focusSource && tab === "source") outlet.querySelector("#insight-source")?.focus();
  }

  function render() {
    const usable = state.connections.filter((connection) => connection.status === "CONNECTED");
    const focus = captureFocus();
    const documentState = documentStatus();
    if (state.mode === "view") {
      outlet.dataset.insightShell = "view";
      outlet.innerHTML = `<section class="insight-studio-page is-view" aria-labelledby="insight-page-title">
        <h1 id="insight-page-title" class="sr-only">Insights</h1>
        <main class="insight-studio-workbench is-view">
          ${insightListPanel()}
          ${outputPanel({ readonly: true })}
        </main>
      </section>`;
      restoreFocus(focus);
      tickElapsed();
      return;
    }
    ensureAuthorShell();
    syncRegion("masthead", mastheadContent(documentState));
    syncRegion("hidden-diagnostics", hiddenDiagnosticsNote());
    syncRegion("build-panel", buildPanelContent(usable), {
      className: "insight-studio-build",
      attributes: { "aria-label": "Dashboard authoring" },
    });
    syncRegion("output-panel", outputPanelContent(), {
      className: outputPanelClass(),
      attributes: { "aria-busy": state.running ? "true" : "false" },
    });
    syncRegion("status-bar", statusBar());
    restoreFocus(focus);
    tickElapsed();
    scheduleAutoSave();
  }

  async function analyze() {
    const sequence = ++analysisSequence;
    const source = state.source;
    const connectionId = state.connectionId || undefined;
    try {
      const analysis = await api.analyzeInsight({
        source,
        connectionId,
        cursorOffset: state.cursorOffset,
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

  /** Applies a server-authored insertion range and returns focus to the source editor. */
  function acceptCompletion(index = state.completionIndex) {
    const completion = completionItems()[index];
    const span = completion?.replaceSpan;
    if (!completion || !span) return false;
    const start = Math.max(0, Math.min(Number(span.startOffset), state.source.length));
    const end = Math.max(start, Math.min(Number(span.endOffset), state.source.length));
    const insert = String(completion.insertText || completion.label || "");
    state.source = state.source.slice(0, start) + insert + state.source.slice(end);
    state.cursorOffset = start + insert.length;
    state.completionIndex = 0;
    state.analysis = state.analysis ? { ...state.analysis, completions: [] } : null;
    setQueryDataset(state.queryDataset);
    state.changeRevision += 1;
    state.selected = null;
    render();
    const editor = outlet.querySelector("#insight-source");
    editor?.focus({ preventScroll: true });
    editor?.setSelectionRange(state.cursorOffset, state.cursorOffset);
    scheduleAnalyze();
    return true;
  }

  /** A caret move changes the completion context even though the document did not change. */
  function updateCompletionCursor(editor) {
    if (!editor || typeof editor.selectionStart !== "number") return;
    const cursor = editor.selectionEnd;
    if (cursor === state.cursorOffset) return;
    state.cursorOffset = cursor;
    state.completionIndex = 0;
    scheduleAnalyze();
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
    state.changeRevision += 1;
    state.error = "";
    render();
    scheduleAnalyze();
  }

  function applyGridLayouts(entries) {
    const replacements = entries
      .filter(({ component }) => component?.span)
      .map(({ component, layout }) => ({
        component,
        layout,
        tag: withGridLayoutProps(state.source.slice(component.span.startOffset, component.span.endOffset), {
          gridX: layout.x,
          gridY: layout.y,
          gridW: layout.w,
          gridH: layout.h,
        }),
      }))
      .filter(({ component, tag }) => tag !== state.source.slice(component.span.startOffset, component.span.endOffset))
      .sort((left, right) => right.component.span.startOffset - left.component.span.startOffset);

    if (!replacements.length) return false;
    for (const { component, tag } of replacements) {
      const { startOffset, endOffset } = component.span;
      spliceSource(startOffset, endOffset, tag);
      component.props = parseTagProps(tag);
      component.span = { ...component.span, endOffset: startOffset + tag.length };
    }
    state.changeRevision += 1;
    state.error = "";
    render();
    scheduleAnalyze();
    return true;
  }

  function commitVisualQuery({ repaint = true } = {}) {
    if (!state.query.managed) return;
    const next = applyVisualQuery(state.source, state.query, syncQueryDataset());
    if (next === state.source) {
      if (repaint) render();
      return;
    }
    state.source = next;
    state.changeRevision += 1;
    state.error = "";
    if (repaint) render();
    scheduleAnalyze();
  }

  function startManagedQuery() {
    const parsed = parseVisualQuery(state.source, syncQueryDataset());
    if (!parsed.requestExpression) return;
    state.query = { ...blankVisualQuery(), managed: true, requestExpression: parsed.requestExpression, requestLabel: parsed.requestLabel };
    commitVisualQuery();
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

  function nextGridSlotFor(type) {
    const grid = dashboardGridSettings(state.source);
    const occupied = resolvedComponentGridLayouts(state.outline || [], grid).map(({ layout }) => layout);
    const cursor = occupied.reduce((next, layout) => {
      const bottom = layout.y + layout.h;
      if (bottom > next.y || (bottom === next.y && layout.x + layout.w > next.x)) {
        return { x: layout.x + layout.w, y: layout.y, rowH: layout.h };
      }
      return next;
    }, { x: 0, y: 0, rowH: 0 });
    return resolveGridCollision(nextAutoGridSlot(cursor, grid, { type, props: {} }), occupied, grid);
  }

  async function addComponent(type) {
    const names = Object.keys(datasets());
    const selected = selectedComponent();
    // A new visual inherits the selected one's dataset when it has one, so building a second view of
    // the same data does not start by re-picking it.
    const bound = selected ? datasetName(selected) : null;
    const name = names.includes(bound) ? bound : names[0];
    const layout = nextGridSlotFor(type);
    const tag = withGridLayoutProps(newComponentSource(type, name, datasets()[name]), {
      gridX: layout.x,
      gridY: layout.y,
      gridW: layout.w,
      gridH: layout.h,
    });
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

  function confirmDeleteComponent(component) {
    if (!component?.span) return false;
    const spec = CATALOG[component.type];
    const label = spec?.label || component.type || "visual";
    return confirm(`Delete this ${label.toLowerCase()} from the dashboard?`);
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

  function setGridSettings(changes) {
    const next = writeDashboardGridSettings(state.source, changes);
    if (next === state.source) return;
    state.source = next;
    state.changeRevision += 1;
    state.error = "";
    render();
    scheduleAnalyze();
  }

  function selectedGridLayout() {
    const component = selectedComponent();
    if (!isGridComponent(component)) return null;
    const grid = dashboardGridSettings(state.source);
    const entry = resolvedComponentGridLayouts(state.outline || [], grid)
      .find((item) => componentKey(item.component) === componentKey(component));
    const fallback = nextGridSlotFor(component.type);
    return { component, grid, layout: entry?.layout || componentGridLayout(component, grid, fallback) };
  }

  function occupiedGridLayouts(exceptKey = null) {
    const grid = dashboardGridSettings(state.source);
    const components = (state.outline || []).filter((component) => componentKey(component) !== exceptKey);
    return resolvedComponentGridLayouts(components, grid).map(({ layout }) => layout);
  }

  function setSelectedGridLayout(changes) {
    const current = selectedGridLayout();
    if (!current) return;
    const { component, grid, layout } = current;
    const nextW = clampNumber(changes.gridW ?? layout.w, layout.w, 1, grid.columns);
    const nextH = clampNumber(changes.gridH ?? layout.h, layout.h, 1, 12);
    const nextX = clampNumber(changes.gridX ?? layout.x, layout.x, 0, Math.max(0, grid.columns - nextW));
    const nextY = clampNumber(changes.gridY ?? layout.y, layout.y, 0, 200);
    const resolved = resolveGridCollision(
      { x: nextX, y: nextY, w: Math.min(nextW, grid.columns - nextX), h: nextH, explicit: true },
      occupiedGridLayouts(componentKey(component)),
      grid,
    );
    const entries = resolvedComponentGridLayouts(
      state.outline || [],
      grid,
      new Map([[componentKey(component), resolved]]),
    );
    applyGridLayouts(entries);
  }

  function resizeSelectedGridLayout(dw, dh) {
    const current = selectedGridLayout();
    if (!current) return;
    setSelectedGridLayout({
      gridX: current.layout.x,
      gridY: current.layout.y,
      gridW: current.layout.w + dw,
      gridH: current.layout.h + dh,
    });
  }

  function nudgeSelectedGridLayout(dx, dy) {
    const current = selectedGridLayout();
    if (!current) return;
    setSelectedGridLayout({
      gridX: current.layout.x + dx,
      gridY: current.layout.y + dy,
      gridW: current.layout.w,
      gridH: current.layout.h,
    });
  }

  async function autoArrangeGrid() {
    const components = (state.outline || []).filter(isGridComponent);
    if (!components.length) return;
    const grid = dashboardGridSettings(state.source);
    const cursor = { x: 0, y: 0, rowH: 0 };
    const replacements = components.map((component) => {
      const slot = nextAutoGridSlot(cursor, grid, component);
      return {
        component,
        tag: withGridLayoutProps(state.source.slice(component.span.startOffset, component.span.endOffset), {
          gridX: slot.x,
          gridY: slot.y,
          gridW: slot.w,
          gridH: slot.h,
        }),
      };
    }).sort((left, right) => right.component.span.startOffset - left.component.span.startOffset);
    for (const { component, tag } of replacements) {
      spliceSource(component.span.startOffset, component.span.endOffset, tag);
    }
    state.selected = null;
    state.changeRevision += 1;
    state.error = "";
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
    const [connections, tools, saved] = await Promise.all([
      api.listConnections().catch(() => []),
      api.listTools().catch(() => []),
      api.listInsights().catch(() => []),
    ]);
    state.connections = connections;
    state.tools = tools;
    state.saved = saved;
    if (!state.connectionId) state.connectionId = connections.find((item) => item.status === "CONNECTED")?.id || "";
    const requests = starterRequests();
    if (!requests.some((tool) => tool.id === state.starterToolId)) state.starterToolId = requests[0]?.id || "";
    if (!requests.some((tool) => tool.id === state.apiTestToolId)) state.apiTestToolId = state.starterToolId || requests[0]?.id || "";
    render();
  }

  function resetDraft(source = EMPTY_INSIGHT, name = "Untitled insight", connectionId = state.connectionId) {
    invalidateRun();
    invalidateAnalysis();
    state.activeId = null;
    state.name = name;
    state.source = source;
    state.connectionId = connectionId || "";
    state.savedSource = "";
    state.savedName = "";
    state.savedConnectionId = "";
    state.data = null;
    state.analysis = null;
    state.parameters = {};
    state.changeRevision = 0;
    state.runRevision = null;
    state.outline = [];
    state.selected = null;
    state.lastRunAt = null;
    state.dataFresh = false;
    state.runNote = "";
    state.error = "";
    state.queryDataset = "rows";
    state.query = parseVisualQuery(source, state.queryDataset);
    state.relationship = { left: "", right: "", leftField: "", rightField: "", name: "", prefix: "", editing: "" };
    state.authorTab = "compose";
    state.apiTestResult = null;
    state.apiTestError = "";
    state.apiTestSubmitting = false;
    state.queryFields = [];
    state.queryFieldsByDataset = {};
    saveStore({ lastInsightId: null, mode: state.mode, autoSave: state.autoSave });
  }

  async function useStarter() {
    const tool = starterRequests().find((item) => item.id === state.starterToolId);
    const connection = tool && state.connections.find((item) => item.id === tool.connectionId);
    if (!tool || !connection) return;
    const title = oneLine(tool.displayName, tool.name || "API request");
    const replacingSaved = Boolean(state.activeId);
    if ((replacingSaved || hasUnsavedChanges(state))
      && !confirm(replacingSaved
        ? `Start a new insight from "${title}"? Your saved insight will remain unchanged.`
        : `Replace this draft with "${title}"? Unsaved changes will be lost.`)) return;
    resetDraft(requestStarter(tool, connection), `${title} insight`, connection.id);
    state.queryDataset = "rows";
    state.query = parseVisualQuery(state.source, state.queryDataset);
    state.source = applyVisualQuery(state.source, state.query, state.queryDataset);
    state.mode = "design";
    render();
    await analyze();
    await runInsight();
  }

  async function addRequestSource() {
    const tool = starterRequests().find((item) => item.id === state.starterToolId);
    const connection = tool && state.connections.find((item) => item.id === tool.connectionId);
    if (!tool || !connection) return;
    const title = oneLine(tool.displayName, tool.name || "API request");
    const bindings = requestBindings(state.source);
    if (state.source === EMPTY_INSIGHT || !bindings.length) {
      resetDraft(requestStarter(tool, connection), `${title} insight`, connection.id);
      state.queryDataset = "rows";
      state.query = parseVisualQuery(state.source, state.queryDataset);
      state.source = applyVisualQuery(state.source, state.query, state.queryDataset);
      state.mode = "design";
      render();
      await analyze();
      await runInsight();
      return;
    }

    const name = nextDatasetName(state.source, tool);
    const label = requestLabel(tool, connection);
    const layout = nextGridSlotFor("DataTable");
    const table = withGridLayoutProps(`<DataTable data={${name}} />`, {
      gridX: layout.x,
      gridY: layout.y,
      gridW: layout.w,
      gridH: layout.h,
    });
    const block = `\n\n\`\`\`rql\nlet ${name} = request "${rqlString(label)}";\n\`\`\`\n\n## ${title}\n\n${table}\n`;
    spliceSource(state.source.length, state.source.length, block);
    state.changeRevision += 1;
    state.error = "";
    state.mode = "design";
    setQueryDataset(name);
    await restructure();
  }

  async function addDatasetTable(dataset) {
    const known = new Set([
      ...requestBindings(state.source).map((binding) => binding.name),
      ...relationshipBindings(state.source).map((binding) => binding.name),
    ]);
    if (!dataset || !known.has(dataset)) return;
    const layout = nextGridSlotFor("DataTable");
    const tag = withGridLayoutProps(`<DataTable data={${dataset}} />`, {
      gridX: layout.x,
      gridY: layout.y,
      gridW: layout.w,
      gridH: layout.h,
    });
    const text = `\n\n${tag}\n`;
    spliceSource(state.source.length, state.source.length, text);
    state.changeRevision += 1;
    state.error = "";
    await restructure();
  }

  async function addRelationship() {
    const draft = syncRelationshipDraft();
    if (!draft) return;
    if (!draft.left || !draft.right || draft.left === draft.right || !draft.leftField || !draft.rightField) {
      state.error = "Choose two datasets and matching key fields before creating a relationship.";
      render();
      return;
    }
    const output = identifier(draft.name, nextRelationshipName(state.source, draft.left, draft.right));
    const editing = draft.editing || "";
    const existing = new Set([
      ...requestBindings(state.source).map((binding) => binding.name),
      ...relationshipBindings(state.source).map((binding) => binding.name),
    ]);
    if (existing.has(output) && output !== editing) {
      state.error = `A dataset named "${output}" already exists.`;
      render();
      return;
    }
    if (editing) {
      let next = replaceRelationshipBindingSource(state.source, editing, { ...draft, name: output });
      if (output !== editing) next = renameDatasetReferences(next, editing, output);
      state.source = compactDocument(next);
      state.relationship = { ...draft, name: output, editing: "" };
      if (state.queryDataset === editing) state.queryDataset = output;
      setQueryDataset(state.queryDataset);
      state.changeRevision += 1;
      state.error = "";
      state.selected = null;
      await restructure();
      return;
    }
    const source = `\n\n\`\`\`rql\n${relationshipSource({ ...draft, name: output })}\n\`\`\`\n\n## ${output}\n\n<DataTable data={${output}} />\n`;
    spliceSource(state.source.length, state.source.length, source);
    state.relationship = { ...draft, name: nextRelationshipName(`${state.source}\nlet ${output} = ${draft.left};`, draft.left, draft.right), editing: "" };
    setQueryDataset(output);
    state.changeRevision += 1;
    state.error = "";
    await restructure();
  }

  async function editRelationship(name) {
    const relationship = relationshipBindings(state.source).find((item) => item.name === name);
    if (!relationship) return;
    state.relationship = { ...relationship, editing: relationship.name };
    render();
  }

  async function removeRelationship(name, { confirmRemoval = true } = {}) {
    if (!name) return;
    if (confirmRemoval && !confirm(`Remove the "${name}" relationship dataset from this dashboard?`)) return;
    let next = removeRelationshipBindingSource(state.source, name);
    next = removeDatasetTables(next, name);
    state.source = compactDocument(next);
    state.changeRevision += 1;
    state.error = "";
    state.selected = null;
    if (state.relationship.editing === name || state.relationship.name === name) {
      state.relationship = { left: "", right: "", leftField: "", rightField: "", name: "", prefix: "", editing: "" };
    }
    setQueryDataset(state.queryDataset);
    await restructure();
  }

  async function removeRequestSource(dataset) {
    if (!dataset || dataset === "rows") return;
    if (!confirm(`Remove the "${dataset}" input from this dashboard?`)) return;
    let next = removeRequestBindingSource(state.source, dataset);
    next = removeDatasetTables(next, dataset);
    for (const relationship of relationshipBindings(next)) {
      if (relationship.left === dataset || relationship.right === dataset) {
        next = removeRelationshipBindingSource(next, relationship.name);
        next = removeDatasetTables(next, relationship.name);
      }
    }
    state.source = compactDocument(next);
    state.changeRevision += 1;
    state.error = "";
    state.selected = null;
    setQueryDataset(state.queryDataset);
    state.relationship = { left: "", right: "", leftField: "", rightField: "", name: "", prefix: "", editing: "" };
    await restructure();
  }

  async function renameRequestSource(oldName, rawName) {
    const nextName = identifier(rawName, oldName);
    if (!oldName || oldName === "rows" || nextName === oldName) {
      render();
      return;
    }
    const bindings = requestBindings(state.source);
    if (bindings.some((binding) => binding.name === nextName)) {
      state.error = `A dataset named "${nextName}" already exists.`;
      render();
      return;
    }
    state.source = renameDatasetReferences(state.source, oldName, nextName);
    if (state.queryDataset === oldName) state.queryDataset = nextName;
    state.changeRevision += 1;
    state.error = "";
    state.selected = null;
    setQueryDataset(state.queryDataset);
    await restructure();
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
    state.savedName = insight.name;
    state.savedConnectionId = insight.connectionId || "";
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
    state.queryDataset = "rows";
    state.query = parseVisualQuery(insight.source, state.queryDataset);
    state.relationship = { left: "", right: "", leftField: "", rightField: "", name: "", prefix: "", editing: "" };
    state.queryFields = datasetFields(insight.lastRun?.datasets?.rows);
    state.queryFieldsByDataset = {};
    for (const [dataset, result] of Object.entries(insight.lastRun?.datasets || {})) {
      const fields = datasetFields(result);
      if (fields.length) state.queryFieldsByDataset[dataset] = fields;
    }
    // Cleared so the next analyze() re-seeds defaults from this document instead of leaking
    // parameter names from the previously open one.
    state.parameters = {};
    saveStore({ lastInsightId: insight.id, mode: state.mode, autoSave: state.autoSave });
  }

  /** Last insight worked on, else the most recently updated, else the empty new-document state. */
  async function restoreLastOpened() {
    const stored = loadStore();
    state.mode = stored.mode;
    state.autoSave = stored.autoSave;
    const wanted = state.saved.find((item) => item.id === stored.lastInsightId) || state.saved[0];
    if (!wanted) {
      saveStore({ lastInsightId: null, mode: state.mode, autoSave: state.autoSave });
      return;
    }
    try {
      await openInsight(wanted.id);
    } catch {
      // Deleted from another browser: fall back to the empty state rather than an error.
      saveStore({ lastInsightId: null, mode: state.mode, autoSave: state.autoSave });
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
      rememberQueryFields();
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
      // Preserve an edit made while the request was in flight as unsaved; it will be picked up by
      // the next autosave instead of being incorrectly treated as part of this response.
      state.savedSource = payload.source;
      state.savedName = payload.name;
      state.savedConnectionId = payload.connectionId || "";
      state.saved = await api.listInsights();
      saveStore({ lastInsightId: stored.id, mode: state.mode, autoSave: state.autoSave });
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
    } else if (action === "back-to-preview") {
      if (state.autoSave && hasUnsavedChanges(state)) await saveInsight();
      state.mode = "view";
      saveStore({ lastInsightId: state.activeId, mode: state.mode, autoSave: state.autoSave });
      render();
    } else if (action === "toggle-auto-save") {
      state.autoSave = !state.autoSave;
      lastAutoSaveAttempt = "";
      saveStore({ lastInsightId: state.activeId, mode: state.mode, autoSave: state.autoSave });
      render();
    } else if (action === "accept-completion") {
      acceptCompletion(Number(target.dataset.completionIndex));
    } else if (action === "run-query") {
      commitVisualQuery({ repaint: false });
      await runInsight();
    } else if (action === "set-author-tab") {
      const tab = target.dataset.tab;
      setAuthorTab(tab, { focusSource: true });
    } else if (action === "set-mode") {
      const { mode } = target.dataset;
      if (!MODES.includes(mode) || mode === state.mode) return;
      state.mode = mode;
      if (mode === "design") setQueryDataset(state.queryDataset);
      saveStore({ lastInsightId: state.activeId, mode: state.mode, autoSave: state.autoSave });
      render();
      // The editor is the reason Code was pressed — put the caret in it rather than leaving focus
      // on a button that just moved.
      if (mode === "code") outlet.querySelector("#insight-source")?.focus();
    } else if (action === "open-source") {
      state.mode = "design";
      state.authorTab = "source";
      saveStore({ lastInsightId: state.activeId, mode: state.mode, autoSave: state.autoSave });
      render();
      outlet.querySelector("#insight-source")?.focus();
    } else if (action === "select-block") {
      if (suppressCanvasClick) {
        suppressCanvasClick = false;
        return;
      }
      const offset = Number(target.dataset.offset);
      state.selected = Number.isFinite(offset) && offset !== state.selected ? offset : null;
      render();
    } else if (action === "add-component") {
      await addComponent(target.dataset.type);
    } else if (action === "add-query-condition") {
      const field = availableQueryFields()[0] || "";
      state.query.filters.push({ id: nextRuleId(), logic: "AND", field, operator: "=", value: "" });
      render();
    } else if (action === "remove-query-condition") {
      state.query.filters = state.query.filters.filter((rule) => rule.id !== target.dataset.conditionId);
      commitVisualQuery();
    } else if (action === "toggle-query-column") {
      const fields = availableQueryFields();
      const field = target.dataset.field;
      const selected = state.query.columns.length ? [...state.query.columns] : [...fields];
      if (selected.length === 1 && selected[0] === field) return;
      state.query.columns = selected.includes(field)
        ? selected.filter((item) => item !== field)
        : [...selected, field];
      // The query engine requires at least one projection. Returning to no explicit projection means all.
      if (!state.query.columns.length) state.query.columns = [];
      commitVisualQuery();
    } else if (action === "query-select-all") {
      state.query.columns = [];
      commitVisualQuery();
    } else if (action === "reset-query") {
      state.query = { ...blankVisualQuery(), managed: true, requestExpression: state.query.requestExpression, requestLabel: state.query.requestLabel };
      commitVisualQuery();
    } else if (action === "reset-visual-query") {
      if (!confirm("Replace the selected request pipeline with a visual query? Other document content will stay unchanged.")) return;
      startManagedQuery();
    } else if (action === "edit-insight") {
      if (!state.activeId && state.source === EMPTY_INSIGHT) return;
      state.mode = "design";
      setQueryDataset(state.queryDataset);
      saveStore({ lastInsightId: state.activeId, mode: state.mode, autoSave: state.autoSave });
      render();
      analyze();
    } else if (action === "delete-component") {
      const component = selectedComponent();
      if (confirmDeleteComponent(component)) await deleteComponent(component);
    } else if (action === "canvas-delete-component") {
      const offset = Number(target.dataset.offset);
      const component = state.outline.find((item) => componentKey(item) === offset);
      if (!component) return;
      state.selected = offset;
      if (confirmDeleteComponent(component)) await deleteComponent(component);
    } else if (action === "move-component") {
      const selected = selectedComponent();
      if (selected) await moveComponent(selected, Number(target.dataset.dir));
    } else if (action === "grid-nudge") {
      nudgeSelectedGridLayout(Number(target.dataset.dx) || 0, Number(target.dataset.dy) || 0);
    } else if (action === "grid-auto-arrange") {
      await autoArrangeGrid();
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
    } else if (action === "use-starter") {
      await useStarter();
    } else if (action === "add-request-source") {
      await addRequestSource();
    } else if (action === "shape-request-source") {
      setQueryDataset(target.dataset.dataset);
      const binding = requestBindings(state.source).find((item) => item.name === state.queryDataset);
      const tool = binding && toolByRequestLabel(binding.label);
      if (tool) state.apiTestToolId = tool.id;
      render();
    } else if (action === "add-dataset-table") {
      await addDatasetTable(target.dataset.dataset);
    } else if (action === "remove-request-source") {
      await removeRequestSource(target.dataset.dataset);
    } else if (action === "add-relationship") {
      await addRelationship();
    } else if (action === "edit-relationship") {
      await editRelationship(target.dataset.relationshipName);
    } else if (action === "remove-relationship") {
      await removeRelationship(target.dataset.relationshipName);
    } else if (action === "new-insight") {
      if (hasUnsavedChanges(state)
        && !confirm(`Start a new insight? Unsaved changes to "${state.name}" will be lost.`)) return;
      resetDraft();
      state.mode = "design";
      saveStore({ lastInsightId: null, mode: state.mode, autoSave: state.autoSave });
      render();
      analyze();
    } else if (action === "open-insight") {
      if (!id || id === state.activeId) return;
      const next = state.saved.find((item) => item.id === id);
      if (hasUnsavedChanges(state)
        && !confirm(`Open "${next?.name || "this insight"}"? Unsaved changes to "${state.name}" will be lost.`)) return;
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
          resetDraft();
        }
        state.saved = await api.listInsights();
      } catch (error) {
        state.error = message(error, "Delete failed");
      }
      render();
      if (!state.activeId) analyze();
    } else if (action === "show-diagnostics") {
      // The diagnostics list lives in the editor footer, so the only place to "show" them is Code.
      state.mode = "design";
      state.authorTab = "source";
      saveStore({ lastInsightId: state.activeId, mode: state.mode, autoSave: state.autoSave });
      render();
      outlet.querySelector("#insight-source")?.focus();
    } else if (action === "dismiss-banner") {
      state.error = "";
      render();
    } else if (action === "clear-api-test-result") {
      state.apiTestResult = null;
      state.apiTestError = "";
      render();
    } else if (action === "add-api-test-source") {
      if (!state.apiTestToolId || requestIsInInsight(starterRequests().find((tool) => tool.id === state.apiTestToolId))) return;
      state.starterToolId = state.apiTestToolId;
      state.authorTab = "compose";
      await addRequestSource();
    }
  });

  outlet.addEventListener("input", (event) => {
    if (event.target.id === "insight-source") {
      state.source = event.target.value;
      state.cursorOffset = event.target.selectionEnd;
      state.completionIndex = 0;
      setQueryDataset(state.queryDataset);
      state.changeRevision += 1;
      // A hand edit can move or delete the selected tag, and its offset would then point at whatever
      // text has shifted into that position.
      state.selected = null;
      scheduleAnalyze();
      scheduleAutoSave();
    } else if (event.target.id === "insight-name") {
      state.name = event.target.value;
      scheduleAutoSave();
    } else if (event.target.dataset.apiTestValue) {
      const tool = starterRequests().find((item) => item.id === state.apiTestToolId);
      const draft = apiTestDraft(tool);
      draft.values[event.target.dataset.apiTestValue] = event.target.value;
    } else if (event.target.dataset.param) {
      state.parameters[event.target.dataset.param] = event.target.type === "number" ? Number(event.target.value) : event.target.value;
      state.changeRevision += 1;
    }
  }, { signal: abort.signal });

  outlet.addEventListener("select", (event) => {
    if (event.target.id === "insight-source") updateCompletionCursor(event.target);
  }, { signal: abort.signal });

  outlet.addEventListener("focusin", (event) => {
    if (event.target.id === "insight-source") updateCompletionCursor(event.target);
  }, { signal: abort.signal });

  // Textarea `select` does not fire for every collapsed-caret move in every browser. Capture both
  // keyboard and pointer movement so the request always follows the place the user is editing.
  outlet.addEventListener("keyup", (event) => {
    if (event.target.id === "insight-source") updateCompletionCursor(event.target);
  }, { signal: abort.signal });

  outlet.addEventListener("pointerup", (event) => {
    if (event.target.id === "insight-source") updateCompletionCursor(event.target);
  }, { signal: abort.signal });

  outlet.addEventListener("pointerdown", (event) => {
    const handle = event.target.closest("[data-resize-handle]");
    const movingItem = handle ? null : event.target.closest(".insight-grid-item.is-selected");
    if (!handle && !movingItem) return;
    if (!handle && event.target.closest("button, a, input, select, textarea, [contenteditable='true']")) return;
    const offset = handle ? Number(handle.dataset.offset) : state.selected;
    const component = state.outline.find((item) => componentKey(item) === offset);
    if (!component || !isGridComponent(component)) return;
    const item = handle ? handle.closest(".insight-grid-item") : movingItem;
    const gridEl = item?.closest(".insight-dashboard-grid");
    if (!item || !gridEl) return;
    event.preventDefault();
    event.stopPropagation();
    state.selected = offset;
    const grid = dashboardGridSettings(state.source);
    const fallback = nextGridSlotFor(component.type);
    const resolvedLayouts = resolvedComponentGridLayouts(state.outline || [], grid);
    const resolvedEntry = resolvedLayouts.find((entry) => componentKey(entry.component) === offset);
    const layout = resolvedEntry?.layout || componentGridLayout(component, grid, fallback);
    const styles = getComputedStyle(gridEl);
    const columnGap = Number.parseFloat(styles.columnGap) || 0;
    const rowGap = Number.parseFloat(styles.rowGap) || columnGap;
    const rowHeight = Number.parseFloat(styles.gridAutoRows) || grid.rowHeight;
    const columnTrack = Math.max(1, (gridEl.clientWidth - columnGap * Math.max(0, grid.columns - 1)) / grid.columns + columnGap);
    const drag = {
      pointerId: event.pointerId,
      offset,
      item,
      startX: event.clientX,
      startY: event.clientY,
      layout,
      grid,
      columnTrack,
      rowTrack: Math.max(1, rowHeight + rowGap),
      occupied: resolvedLayouts
        .filter((entry) => componentKey(entry.component) !== offset)
        .map((entry) => entry.layout),
      lastW: layout.w,
      lastH: layout.h,
      lastX: layout.x,
      lastY: layout.y,
    };
    if (handle) {
      resizeDrag = drag;
      handle.setPointerCapture?.(event.pointerId);
      document.body.classList.add("is-resizing-insight");
    } else {
      moveDrag = drag;
      item.setPointerCapture?.(event.pointerId);
      item.classList.add("is-moving");
      document.body.classList.add("is-moving-insight");
    }
  }, { signal: abort.signal });

  window.addEventListener("pointermove", (event) => {
    if (resizeDrag && event.pointerId === resizeDrag.pointerId) {
      event.preventDefault();
      const dw = Math.round((event.clientX - resizeDrag.startX) / resizeDrag.columnTrack);
      const dh = Math.round((event.clientY - resizeDrag.startY) / resizeDrag.rowTrack);
      const next = nonOverlappingResize(
        resizeDrag.layout,
        resizeDrag.layout.w + dw,
        resizeDrag.layout.h + dh,
        resizeDrag.occupied,
        resizeDrag.grid,
      );
      if (next.w === resizeDrag.lastW && next.h === resizeDrag.lastH) return;
      resizeDrag.lastW = next.w;
      resizeDrag.lastH = next.h;
      resizeDrag.item.style.setProperty("--grid-w", String(next.w));
      resizeDrag.item.style.setProperty("--grid-h", String(next.h));
      return;
    }
    if (!moveDrag || event.pointerId !== moveDrag.pointerId) return;
    event.preventDefault();
    const dx = Math.round((event.clientX - moveDrag.startX) / moveDrag.columnTrack);
    const dy = Math.round((event.clientY - moveDrag.startY) / moveDrag.rowTrack);
    const next = nonOverlappingMove(
      moveDrag.layout,
      moveDrag.layout.x + dx,
      moveDrag.layout.y + dy,
      moveDrag.occupied,
      moveDrag.grid,
    );
    if (!next || (next.x === moveDrag.lastX && next.y === moveDrag.lastY)) return;
    moveDrag.moved = true;
    moveDrag.lastX = next.x;
    moveDrag.lastY = next.y;
    moveDrag.item.style.setProperty("--grid-x", String(next.x + 1));
    moveDrag.item.style.setProperty("--grid-y", String(next.y + 1));
  }, { signal: abort.signal });

  window.addEventListener("pointerup", (event) => {
    if (resizeDrag && event.pointerId === resizeDrag.pointerId) {
      const next = resizeDrag;
      resizeDrag = null;
      document.body.classList.remove("is-resizing-insight");
      state.selected = next.offset;
      setSelectedGridLayout({
        gridX: next.layout.x,
        gridY: next.layout.y,
        gridW: next.lastW,
        gridH: next.lastH,
      });
      return;
    }
    if (!moveDrag || event.pointerId !== moveDrag.pointerId) return;
    const next = moveDrag;
    moveDrag = null;
    next.item.classList.remove("is-moving");
    document.body.classList.remove("is-moving-insight");
    if (next.moved) suppressCanvasClick = true;
    state.selected = next.offset;
    setSelectedGridLayout({
      gridX: next.lastX,
      gridY: next.lastY,
      gridW: next.layout.w,
      gridH: next.layout.h,
    });
  }, { signal: abort.signal });
  // Format inputs commit on change, not on input: every commit rewrites the document and repaints the
  // page, so applying per-keystroke would splice the source a dozen times for one title.
  outlet.addEventListener("change", async (event) => {
    const { target } = event;
    if (target.id === "insight-connection") {
      state.connectionId = target.value;
      state.changeRevision += 1;
      analyze();
    } else if (target.id === "insight-starter-request") {
      state.starterToolId = target.value;
    } else if (target.id === "insight-api-test-request") {
      state.apiTestToolId = target.value;
      state.apiTestResult = null;
      state.apiTestError = "";
      const tool = starterRequests().find((item) => item.id === state.apiTestToolId);
      const label = tool && requestLabel(tool, toolConnection(tool));
      const binding = label && requestBindings(state.source).find((item) => item.label === label);
      if (binding) setQueryDataset(binding.name);
      render();
    } else if (target.dataset.apiTestValue) {
      const tool = starterRequests().find((item) => item.id === state.apiTestToolId);
      const draft = apiTestDraft(tool);
      draft.values[target.dataset.apiTestValue] = target.value;
    } else if (target.id === "insight-query-dataset") {
      setQueryDataset(target.value);
      render();
    } else if (target.dataset.requestName) {
      await renameRequestSource(target.dataset.requestName, target.value);
    } else if (target.dataset.relationship) {
      const key = target.dataset.relationship;
      state.relationship[key] = key === "name" || key === "prefix" ? identifier(target.value, state.relationship[key] || key) : target.value;
      if (key === "left" || key === "right") {
        state.relationship.leftField = "";
        state.relationship.rightField = "";
        if (state.relationship.left === state.relationship.right) {
          const names = requestBindings(state.source).map((binding) => binding.name);
          state.relationship.right = names.find((name) => name !== state.relationship.left) || state.relationship.right;
        }
      }
      render();
    } else if (target.dataset.queryRule) {
      const container = target.closest("[data-condition-id]");
      const rule = state.query.filters.find((item) => item.id === container?.dataset.conditionId);
      if (!rule) return;
      rule[target.dataset.queryRule] = target.value;
      commitVisualQuery();
    } else if (target.dataset.query) {
      const key = target.dataset.query;
      state.query[key] = target.type === "checkbox"
        ? target.checked
        : target.type === "number"
          ? Math.max(1, Math.min(10000, Number(target.value) || 100))
          : target.value;
      if (key === "aggregateFunction") {
        if (state.query.aggregateFunction === "count") state.query.aggregateField = "*";
        else if (state.query.aggregateField === "*") {
          const fields = availableQueryFields();
          const rows = datasets()[state.queryDataset]?.rows || [];
          state.query.aggregateField = fields.find((field) => isNumericColumn(rows, field)) || fields[0] || "";
        }
      }
      if (key === "groupField") {
        state.query.sortField = "";
        if (!state.query.groupField) state.query.aggregateField = "*";
      }
      commitVisualQuery();
    } else if (target.dataset.gridSetting) {
      setGridSettings({ [target.dataset.gridSetting]: target.value });
    } else if (target.dataset.gridLayout) {
      setSelectedGridLayout({ [target.dataset.gridLayout]: target.value });
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

  outlet.addEventListener("submit", async (event) => {
    const form = event.target;
    if (form.id !== "insight-api-test-form") return;
    event.preventDefault();
    if (state.apiTestSubmitting) return;
    const tool = starterRequests().find((item) => item.id === state.apiTestToolId);
    if (!tool) return;
    const draft = apiTestDraft(tool);
    state.apiTestSubmitting = true;
    state.apiTestError = "";
    render();
    try {
      const response = await api.invokeTool(tool.id, toolArguments(tool, draft.values));
      state.apiTestResult = { toolId: tool.id, response };
    } catch (error) {
      state.apiTestError = message(error, "Request failed");
    } finally {
      state.apiTestSubmitting = false;
      render();
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
    const authorTab = event.target.closest?.(".insight-author-tabs [role='tab'][data-tab]");
    if (authorTab) {
      const currentIndex = AUTHOR_TABS.indexOf(authorTab.dataset.tab);
      let nextIndex = currentIndex;
      if (event.key === "ArrowRight" || event.key === "ArrowDown") nextIndex = (currentIndex + 1) % AUTHOR_TABS.length;
      else if (event.key === "ArrowLeft" || event.key === "ArrowUp") nextIndex = (currentIndex - 1 + AUTHOR_TABS.length) % AUTHOR_TABS.length;
      else if (event.key === "Home") nextIndex = 0;
      else if (event.key === "End") nextIndex = AUTHOR_TABS.length - 1;
      else nextIndex = -1;
      if (nextIndex >= 0) {
        event.preventDefault();
        setAuthorTab(AUTHOR_TABS[nextIndex], { focusTab: true });
        return;
      }
    }
    if (event.target.id === "insight-source" && event.key === " " && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      updateCompletionCursor(event.target);
      analyze();
      return;
    }
    if (event.target.id === "insight-source" && !event.ctrlKey && !event.metaKey && !event.altKey) {
      const items = completionItems();
      if (event.key === "Escape" && items.length) {
        event.preventDefault();
        state.analysis = state.analysis ? { ...state.analysis, completions: [] } : null;
        state.completionIndex = 0;
        render();
        return;
      }
      if ((event.key === "ArrowDown" || event.key === "ArrowUp") && items.length) {
        event.preventDefault();
        const direction = event.key === "ArrowDown" ? 1 : -1;
        state.completionIndex = (state.completionIndex + direction + items.length) % items.length;
        render();
        return;
      }
      if ((event.key === "Tab" || event.key === "Enter") && items.length) {
        event.preventDefault();
        acceptCompletion();
        return;
      }
    }
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
    clearTimeout(autoSaveTimer);
    clearInterval(elapsedTimer);
    document.body.classList.remove("is-resizing-insight");
    document.body.classList.remove("is-moving-insight");
  };
}
