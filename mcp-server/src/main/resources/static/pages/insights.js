import { api } from "../api.js";
import { banner, escapeAttr, escapeHtml, icon, message, on } from "../ui.js";

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

function evaluate(expression, datasets) {
  const source = String(expression || "").replace(/^\{|\}$/g, "").trim();
  const count = /^count\(([\w-]+)\)$/.exec(source);
  if (count) return datasets[count[1]]?.rows?.length ?? 0;
  const sum = /^sum\(([\w-]+)\.([\w-]+)\)$/.exec(source);
  if (sum) return (datasets[sum[1]]?.rows || []).reduce((total, row) => total + Number(row[sum[2]] || 0), 0);
  return source.replace(/^["']|["']$/g, "") || "—";
}

function datasetName(component) {
  return component.props?.data?.replace(/^\{|\}$/g, "").trim();
}

function parseColumns(value, fallback) {
  if (!value) return fallback;
  const content = value.replace(/^\{|\}$/g, "").replace(/^\[|\]$/g, "");
  const columns = content.split(",").map((item) => item.trim().replace(/^["']|["']$/g, "")).filter(Boolean);
  return columns.length ? columns : fallback;
}

function dataTable(dataset, component) {
  const columns = parseColumns(component.props.columns, dataset.columns || Object.keys(dataset.rows?.[0] || {}));
  return `<section class="insight-card">
    <header class="insight-card-heading"><div><p class="insight-card-kicker">Dataset</p><h2>${escapeHtml(component.props.title || datasetName(component))}</h2></div><span>${dataset.rows.length} rows</span></header>
    <div class="insight-table-scroll"><table class="insight-data-table"><thead><tr>${columns.map((column) => `<th>${escapeHtml(column)}</th>`).join("")}</tr></thead><tbody>${dataset.rows.slice(0, 100).map((row) => `<tr>${columns.map((column) => `<td>${escapeHtml(row[column] === "" || row[column] === null || row[column] === undefined ? "—" : typeof row[column] === "object" ? JSON.stringify(row[column]) : row[column])}</td>`).join("")}</tr>`).join("")}</tbody></table>${dataset.rows.length > 100 ? `<p class="insight-table-note">Showing the first 100 of ${dataset.rows.length} rows.</p>` : ""}</div>
  </section>`;
}

function barChart(dataset, component) {
  const x = component.props.x;
  const y = component.props.y;
  const points = dataset.rows.map((row) => ({ label: String(row[x] ?? "—"), value: Number(row[y]) })).filter((point) => Number.isFinite(point.value)).slice(0, 24);
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
  return `<section class="insight-card insight-chart-card"><header class="insight-card-heading"><div><p class="insight-card-kicker">Comparison</p><h2>${escapeHtml(component.props.title || `${y} by ${x}`)}</h2></div><span>${points.length} categories</span></header>
    ${points.length ? `<svg class="insight-bar-chart" viewBox="0 0 ${width} ${height}" role="img">${[0, .5, 1].map((tick) => { const position = top + plotHeight - tick * plotHeight; return `<g><line x1="${left}" x2="${width - right}" y1="${position}" y2="${position}" class="insight-grid-line"></line><text x="${left - 9}" y="${position + 4}" text-anchor="end" class="insight-axis-label">${Math.round(maximum * tick)}</text></g>`; }).join("")}${bars}</svg>` : '<p class="insight-no-data">No numeric rows to chart.</p>'}
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
    if (component.type === "DataTable" && dataset) blocks.push(dataTable(dataset, component));
    else if (component.type === "BarChart" && dataset) blocks.push(barChart(dataset, component));
    else if (component.type === "Text") blocks.push(`<p class="insight-text">${escapeHtml(evaluate(component.props.value, data.datasets))}</p>`);
    else if (["KeyValue", "LabelValue"].includes(component.type)) blocks.push(`<div class="insight-kv ${component.type === "LabelValue" ? "is-plain" : ""}"><span class="insight-kv-label">${escapeHtml(component.props.label || "Value")}</span><span class="insight-kv-value">${escapeHtml(evaluate(component.props.value, data.datasets))}</span></div>`);
    else if (component.type === "Status" && data.requests?.length) blocks.push(`<section class="insight-card"><header class="insight-card-heading"><div><p class="insight-card-kicker">Execution</p><h2>Request status</h2></div></header><div class="insight-table-scroll"><table class="insight-data-table"><thead><tr><th>Request</th><th>Method</th><th>Status</th><th>Duration</th></tr></thead><tbody>${data.requests.map((request) => `<tr><td>${escapeHtml(request.request)}</td><td class="mono">${escapeHtml(request.method)}</td><td class="mono insight-status-code is-${request.success ? "ok" : "failed"}">${request.status}</td><td class="mono">${request.cached ? "cached" : `${request.durationMs} ms`}</td></tr>`).join("")}</tbody></table></div></section>`);
  });
  flushStats();
  return `<div class="insight-rendered">${blocks.join("") || '<div class="insight-preview-empty"><h2>No renderable components</h2><p>Add a Stat, BarChart, or DataTable component to the document.</p></div>'}</div>`;
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

  function render() {
    const usable = state.connections.filter((connection) => connection.status === "CONNECTED");
    outlet.innerHTML = `<section class="insight-page" aria-labelledby="insight-page-title">
      <header class="insight-page-header"><div><p class="eyebrow">${icon("file", 14)} Insight workspace</p><h1 id="insight-page-title">Insights</h1><p>Write RQL beside the view it drives. One insight can read from several connected apps.</p></div>
        <div class="insight-actions"><label class="insight-name-field"><span>Name</span><input id="insight-name" value="${escapeAttr(state.name)}"></label><label class="insight-connection-picker"><span>Default app</span><select id="insight-connection"><option value="">All connected apps</option>${usable.map((connection) => `<option value="${escapeAttr(connection.id)}" ${state.connectionId === connection.id ? "selected" : ""}>${escapeHtml(connection.name)}</option>`).join("")}</select></label><button class="btn" type="button" data-action="save-insight" ${state.saving ? "disabled" : ""}>${icon("download", 15)} ${state.saving ? "Saving…" : "Save"}</button><button class="insight-run-button" type="button" data-action="run-insight" ${state.running ? "disabled" : ""}>${icon("play", 15)} ${state.running ? "Running…" : "Run insight"}</button></div>
      </header>
      ${!usable.length ? '<div class="insight-connection-note">Import and connect an API collection on Connections to run an insight.</div>' : ""}
      ${parameterControls()}
      <div class="insight-workspace">
        <nav class="insight-library" aria-label="Saved insights"><header><span>Saved insights</span><button class="btn btn-sm" type="button" data-action="new-insight">New</button></header>${state.saved.length ? `<ul>${state.saved.map((insight) => `<li><button class="insight-library-item ${insight.id === state.activeId ? "is-active" : ""}" type="button" data-action="open-insight" data-id="${escapeAttr(insight.id)}"><strong>${escapeHtml(insight.name)}</strong>${insight.description ? `<small>${escapeHtml(insight.description)}</small>` : ""}</button><button class="insight-library-delete" type="button" data-action="delete-insight" data-id="${escapeAttr(insight.id)}" aria-label="Delete ${escapeAttr(insight.name)}">${icon("trash", 13)}</button></li>`).join("")}</ul>` : '<p class="insight-library-empty">Nothing saved yet. Build one, then press Save.</p>'}</nav>
        <section class="insight-editor-panel"><header><span>insight.rqd</span><small>Markdown · RQL · components</small></header><textarea id="insight-source" class="insight-plain-editor" spellcheck="false">${escapeHtml(state.source)}</textarea><footer>${diagnostics()}</footer></section>
        <section class="insight-preview-panel" aria-live="polite"><header><span>Live insight</span><small>${state.data ? "Last successful run" : "Run to fetch API data"}</small></header>${state.error ? `<div class="insight-run-error">${icon("alert", 15)} ${escapeHtml(state.error)}</div>` : ""}${state.data ? renderInsight(state.data) : `<div class="insight-preview-empty">${icon("file", 24)}<h2>Ready for a query</h2><p>Run the document to fetch data and render its datasets.</p></div>`}</section>
      </div>
    </section>`;
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

  on(outlet, "click", "[data-action]", async (_event, target) => {
    const { action, id } = target.dataset;
    if (action === "run-insight") {
      state.running = true;
      state.error = "";
      render();
      try {
        state.data = await api.loadInsightData({
          source: state.source,
          connectionId: state.connectionId || undefined,
          parameters: state.parameters,
        });
        state.analysis = { ...state.analysis, diagnostics: state.data.diagnostics, params: state.data.params, outline: state.data.outline };
      } catch (error) {
        state.error = message(error, "The insight could not be loaded.");
      } finally {
        state.running = false;
        render();
      }
    } else if (action === "save-insight") {
      state.saving = true;
      render();
      try {
        const payload = { name: state.name, source: state.source, connectionId: state.connectionId || null };
        const stored = state.activeId ? await api.updateInsight(state.activeId, payload) : await api.createInsight(payload);
        state.activeId = stored.id;
        state.saved = await api.listInsights();
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
      state.data = null;
      state.error = "";
      render();
      analyze();
    } else if (action === "open-insight") {
      const insight = state.saved.find((item) => item.id === id);
      if (!insight) return;
      state.activeId = insight.id;
      state.name = insight.name;
      state.source = insight.source;
      state.connectionId = insight.connectionId || "";
      state.data = null;
      state.error = "";
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
          state.data = null;
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
  analyze();
  return () => {
    abort.abort();
    clearTimeout(analysisTimer);
  };
}
