import { useEffect, useMemo, useState } from "react";
import {
  analyzeInsight,
  type ConnectionInfo,
  type InsightAnalysis,
  type InsightComponent,
  type InsightData,
  type InsightDataset,
  type InsightParam,
  type QueryDiagnostic,
  listConnections,
  listTools,
  loadInsightData,
} from "../api";
import {
  createInsight,
  deleteInsight,
  listInsights,
  updateInsight,
  type SavedInsight,
} from "../api";
import { AlertIcon, CodeIcon, PlayIcon, SaveIcon, TrashIcon } from "../icons";
import { InsightEditor } from "./InsightEditor";
import { evaluateText, parseList, parseRows, type Datasets } from "../insightExpr";

const EXAMPLE_INSIGHT = `---
title: API activity
params:
  minUser: { type: number, default: 1 }
---

# API activity

Edit this document and run it. Requests resolve across every connected app; qualify a
name as \`request "App name: Request name"\` when two apps use the same request name.

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

function connectionStarter(requestName: string): string {
  return `---
title: API activity
---

# API activity

This starter uses an enabled read request from the selected collection. Add pipelines to shape the data, then add a chart once you know the fields.

\`\`\`rql
let records = request "${requestName}"
  |> limit 100;
\`\`\`

<KpiRow>
  <Stat value={count(records)} label="Rows returned" />
</KpiRow>

<DataTable data={records} />
`;
}

function sourceError(error: unknown): string {
  return error instanceof Error ? error.message : "The insight could not be loaded.";
}

function datasetName(component: InsightComponent): string | null {
  const value = component.props.data;
  return value ? value.replace(/^\{|}$/g, "").trim() : null;
}

function asText(value: unknown): string {
  if (value === null || value === undefined) return "—";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function asNumber(value: unknown): number | null {
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value === "bigint") return Number(value);
  if (typeof value === "string" && value.trim() !== "") {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

interface TableColumn {
  field: string;
  header: string;
}

/** columns={["id AS \"Post ID\"", "title"]} — the field is read, the header is displayed. */
function columnsFrom(component: InsightComponent, dataset: InsightDataset): TableColumn[] {
  const raw = component.props.columns;
  if (!raw) return dataset.columns.map((column) => ({ field: column, header: column }));
  const entries = parseList(raw);
  if (entries.length === 0) return dataset.columns.map((column) => ({ field: column, header: column }));
  return entries.map((entry) => {
    const renamed = /^(.+?)\s+as\s+(.+)$/i.exec(entry.trim());
    if (!renamed) return { field: entry.trim(), header: entry.trim() };
    return { field: renamed[1].trim(), header: renamed[2].trim().replace(/^["']|["']$/g, "") };
  });
}

/** true/false read as status, so a boolean cell is legible at a glance without author colors. */
function BooleanCell({ value }: { value: unknown }) {
  const text = asText(value);
  if (text !== "true" && text !== "false") return <>{text === "" ? "—" : text}</>;
  return <span className={`insight-flag is-${text}`}>{text}</span>;
}

function TableTwin({ dataset, columns }: { dataset: InsightDataset; columns: TableColumn[] }) {
  return (
    <div className="insight-table-scroll">
      <table className="insight-data-table">
        <thead>
          <tr>{columns.map((column) => <th key={column.field}>{column.header}</th>)}</tr>
        </thead>
        <tbody>
          {dataset.rows.slice(0, 100).map((row, index) => (
            <tr key={index}>
              {columns.map((column) => (
                <td key={column.field}><BooleanCell value={row[column.field]} /></td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {dataset.rows.length > 100 && <p className="insight-table-note">Showing the first 100 of {dataset.rows.length} rows.</p>}
    </div>
  );
}

function BarChart({ component, dataset }: { component: InsightComponent; dataset: InsightDataset }) {
  const x = component.props.x;
  const y = component.props.y;
  const points = dataset.rows
    .map((row) => ({ label: asText(row[x]), value: asNumber(row[y]) }))
    .filter((row): row is { label: string; value: number } => row.value !== null);
  const width = 700;
  const height = 280;
  const left = 48;
  const right = 18;
  const top = 20;
  const bottom = 56;
  const plotWidth = width - left - right;
  const plotHeight = height - top - bottom;
  const maximum = Math.max(...points.map((point) => point.value), 1);
  const slot = points.length === 0 ? plotWidth : plotWidth / points.length;
  const barWidth = Math.max(8, Math.min(42, slot - 8));
  const title = component.props.title ?? `${y} by ${x}`;

  return (
    <section className="insight-card insight-chart-card">
      <header className="insight-card-heading">
        <div><p className="insight-card-kicker">Comparison</p><h2>{title}</h2></div>
        <span>{points.length} categories</span>
      </header>
      {points.length === 0 ? <p className="insight-no-data">No numeric rows to chart.</p> : (
        <svg className="insight-bar-chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label={title}>
          <title>{title}</title>
          {[0, 0.5, 1].map((tick) => {
            const yPosition = top + plotHeight - tick * plotHeight;
            return <g key={tick}>
              <line x1={left} x2={width - right} y1={yPosition} y2={yPosition} className="insight-grid-line" />
              <text x={left - 9} y={yPosition + 4} textAnchor="end" className="insight-axis-label">{Math.round(maximum * tick)}</text>
            </g>;
          })}
          {points.map((point, index) => {
            const barHeight = (point.value / maximum) * plotHeight;
            const barX = left + index * slot + (slot - barWidth) / 2;
            const barY = top + plotHeight - barHeight;
            return <g key={`${point.label}-${index}`}>
              <rect x={barX} y={barY} width={barWidth} height={barHeight} rx="4" className="insight-bar" />
              <text x={barX + barWidth / 2} y={barY - 6} textAnchor="middle" className="insight-value-label">{point.value}</text>
              <text x={barX + barWidth / 2} y={top + plotHeight + 20} textAnchor="end" transform={`rotate(-35 ${barX + barWidth / 2} ${top + plotHeight + 20})`} className="insight-axis-label">{point.label}</text>
            </g>;
          })}
        </svg>
      )}
      <details className="insight-table-twin">
        <summary>Show chart data table</summary>
        <TableTwin dataset={dataset} columns={[{ field: x, header: x }, { field: y, header: y }]} />
      </details>
    </section>
  );
}

/** KV / LV: a label beside one value. LabelValue keeps the label plain rather than bold. */
function LabelledValue({ component, datasets }: { component: InsightComponent; datasets: Datasets }) {
  const value = evaluateText(component.props.value ?? "", datasets);
  return (
    <div className={`insight-kv${component.type === "LabelValue" ? " is-plain" : ""}`}>
      <span className="insight-kv-label">{component.props.label ?? "Value"}</span>
      <span className="insight-kv-value"><BooleanCell value={value} /></span>
    </div>
  );
}

/** QT / LABEL_TABLE: an inline table written in the document, not read from a dataset. */
function InlineTable({ component, datasets }: { component: InsightComponent; datasets: Datasets }) {
  const rows = parseRows(component.props.rows);
  const headers = parseList(component.props.headers ?? component.props.columns);
  const showHeaders = component.type === "QuickTable"
    ? (headers.length > 0 ? headers : ["Label", "Value"])
    : headers;
  return (
    <section className="insight-card insight-inline-table">
      {component.props.title && (
        <header className="insight-card-heading">
          <div><p className="insight-card-kicker">Summary</p><h2>{component.props.title}</h2></div>
        </header>
      )}
      <div className="insight-table-scroll">
        <table className="insight-data-table">
          {showHeaders.length > 0 && (
            <thead><tr>{showHeaders.map((header) => <th key={header}>{header}</th>)}</tr></thead>
          )}
          <tbody>
            {rows.map((row, rowIndex) => (
              <tr key={rowIndex}>
                {row.map((cell, cellIndex) => (
                  <td key={cellIndex}><BooleanCell value={evaluateText(cell, datasets)} /></td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/** STATUS: one row per request this run issued. */
function RequestStatus({ data }: { data: InsightData }) {
  if (data.requests.length === 0) return null;
  return (
    <section className="insight-card">
      <header className="insight-card-heading">
        <div><p className="insight-card-kicker">Execution</p><h2>Request status</h2></div>
        <span>{data.requests.length} requests</span>
      </header>
      <div className="insight-table-scroll">
        <table className="insight-data-table">
          <thead>
            <tr><th>Request</th><th>Method</th><th>Status</th><th>Success</th><th>Duration (ms)</th></tr>
          </thead>
          <tbody>
            {data.requests.map((request) => (
              <tr key={request.request}>
                <td>{request.request}</td>
                <td className="mono">{request.method}</td>
                <td className={`mono insight-status-code is-${request.success ? "ok" : "failed"}`}>{request.status}</td>
                <td><BooleanCell value={String(request.success)} /></td>
                <td className="mono">{request.cached ? "cached" : request.durationMs}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/** METRICS: the aggregate counterpart of STATUS. */
function ExecutionMetrics({ data }: { data: InsightData }) {
  const total = data.requests.length;
  const failed = data.requests.filter((request) => !request.success).length;
  const duration = data.requests.reduce((sum, request) => sum + request.durationMs, 0);
  const rows = data.datasets ? Object.values(data.datasets).reduce((sum, set) => sum + set.rows.length, 0) : 0;
  const entries: [string, string][] = [
    ["Requests", String(total)],
    ["Succeeded", String(total - failed)],
    ["Failed", String(failed)],
    ["Rows returned", String(rows)],
    ["Total duration (ms)", String(duration)],
  ];
  return (
    <section className="insight-card insight-metrics">
      <header className="insight-card-heading">
        <div><p className="insight-card-kicker">Execution</p><h2>Metrics</h2></div>
      </header>
      <div className="insight-metric-rows">
        {entries.map(([label, value]) => (
          <div className="insight-kv is-plain" key={label}>
            <span className="insight-kv-label">{label}</span>
            <span className="insight-kv-value">{value}</span>
          </div>
        ))}
      </div>
    </section>
  );
}

/** Renders the outline in document order; consecutive Stats collapse into one KPI row. */
export function RenderInsight({ data }: { data: InsightData }) {
  const blocks: React.ReactNode[] = [];
  let statRun: InsightComponent[] = [];

  const flushStats = (key: string) => {
    if (statRun.length === 0) return;
    const run = statRun;
    statRun = [];
    blocks.push(
      <div className="insight-kpi-row" key={`kpi-${key}`}>
        {run.map((component, index) => (
          <section className="insight-stat" key={`stat-${key}-${index}`}>
            <span>{component.props.label ?? "Metric"}</span>
            <strong>{evaluateText(component.props.value ?? "", data.datasets)}</strong>
          </section>
        ))}
      </div>,
    );
  };

  data.outline.forEach((component, index) => {
    const key = `${component.type}-${index}`;
    if (component.type === "Stat") {
      statRun.push(component);
      return;
    }
    if (component.type === "KpiRow" || component.type === "Filter") return;
    flushStats(key);

    if (component.type === "Text") {
      blocks.push(
        <p className="insight-text" key={key}>{evaluateText(component.props.value ?? "", data.datasets)}</p>,
      );
      return;
    }
    if (component.type === "KeyValue" || component.type === "LabelValue") {
      blocks.push(<LabelledValue component={component} datasets={data.datasets} key={key} />);
      return;
    }
    if (component.type === "QuickTable" || component.type === "LabelTable") {
      blocks.push(<InlineTable component={component} datasets={data.datasets} key={key} />);
      return;
    }
    if (component.type === "Status") {
      blocks.push(<RequestStatus data={data} key={key} />);
      return;
    }
    if (component.type === "Metrics") {
      blocks.push(<ExecutionMetrics data={data} key={key} />);
      return;
    }

    const name = datasetName(component);
    const dataset = name ? data.datasets[name] : undefined;
    if (!dataset) return;
    if (component.type === "BarChart") {
      blocks.push(<BarChart component={component} dataset={dataset} key={key} />);
      return;
    }
    if (component.type === "DataTable") {
      blocks.push(
        <section className="insight-card" key={key}>
          <header className="insight-card-heading">
            <div>
              <p className="insight-card-kicker">Dataset</p>
              <h2>{component.props.title ?? name}</h2>
            </div>
            <span>{dataset.rows.length} rows</span>
          </header>
          <TableTwin dataset={dataset} columns={columnsFrom(component, dataset)} />
        </section>,
      );
    }
  });
  flushStats("tail");

  return <div className="insight-rendered">{blocks}</div>;
}

function ParameterControls({ params, values, onChange }: {
  params: InsightParam[];
  values: Record<string, unknown>;
  onChange: (name: string, value: unknown) => void;
}) {
  if (params.length === 0) return null;
  return <div className="insight-params" aria-label="Insight parameters">
    {params.map((param) => (
      <label key={param.name}>
        <span>{param.name}</span>
        <input
          type={param.type === "number" ? "number" : "text"}
          value={String(values[param.name] ?? "")}
          onChange={(event) => onChange(param.name, param.type === "number" ? Number(event.target.value) : event.target.value)}
        />
      </label>
    ))}
  </div>;
}

function Diagnostics({ diagnostics }: { diagnostics: QueryDiagnostic[] }) {
  const relevant = diagnostics.slice(0, 4);
  if (relevant.length === 0) return <p className="insight-analysis-ok">Document checks clean.</p>;
  return <div className="insight-diagnostics">
    {relevant.map((diagnostic, index) => <p key={`${diagnostic.code}-${index}`} className={`is-${diagnostic.severity.toLowerCase()}`}>
      <AlertIcon size={14} /> <b>{diagnostic.code}</b> {diagnostic.message}
    </p>)}
    {diagnostics.length > relevant.length && <span>+{diagnostics.length - relevant.length} more checks</span>}
  </div>;
}

/** The saved-insight library: as many insights as you care to keep, newest first. */
function InsightLibrary({ insights, activeId, onOpen, onNew, onDelete }: {
  insights: SavedInsight[];
  activeId: string | null;
  onOpen: (insight: SavedInsight) => void;
  onNew: () => void;
  onDelete: (insight: SavedInsight) => void;
}) {
  return (
    <nav className="insight-library" aria-label="Saved insights">
      <header>
        <span>Saved insights</span>
        <button type="button" className="btn btn-sm" onClick={onNew}>New</button>
      </header>
      {insights.length === 0 ? (
        <p className="insight-library-empty">Nothing saved yet. Build one, then press Save.</p>
      ) : (
        <ul>
          {insights.map((insight) => (
            <li key={insight.id}>
              <button
                type="button"
                className={`insight-library-item${insight.id === activeId ? " is-active" : ""}`}
                onClick={() => onOpen(insight)}
                aria-current={insight.id === activeId ? "true" : undefined}
              >
                <strong>{insight.name}</strong>
                {insight.description && <small>{insight.description}</small>}
              </button>
              <button
                type="button"
                className="insight-library-delete"
                onClick={() => onDelete(insight)}
                aria-label={`Delete ${insight.name}`}
              >
                <TrashIcon size={13} />
              </button>
            </li>
          ))}
        </ul>
      )}
    </nav>
  );
}

export function InsightPage() {
  const [source, setSource] = useState(EXAMPLE_INSIGHT);
  const [connections, setConnections] = useState<ConnectionInfo[]>([]);
  const [connectionId, setConnectionId] = useState("");
  const [analysis, setAnalysis] = useState<InsightAnalysis | null>(null);
  const [data, setData] = useState<InsightData | null>(null);
  const [parameters, setParameters] = useState<Record<string, unknown>>({});
  const [isRunning, setIsRunning] = useState(false);
  const [runError, setRunError] = useState("");
  const [starterPrepared, setStarterPrepared] = useState(false);
  const [saved, setSaved] = useState<SavedInsight[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [name, setName] = useState("Untitled insight");
  const [saveState, setSaveState] = useState<"idle" | "saving" | "saved">("idle");

  const usableConnections = useMemo(() => connections.filter((connection) => connection.status === "CONNECTED"), [connections]);

  const refreshLibrary = () => listInsights().then(setSaved).catch(() => {});

  useEffect(() => {
    listConnections().then((items) => {
      setConnections(items);
      const first = items.find((item) => item.status === "CONNECTED");
      if (first) setConnectionId((current) => current || first.id);
    }).catch(() => {});
    refreshLibrary();
  }, []);

  useEffect(() => {
    if (!connectionId || starterPrepared || activeId) return;
    let current = true;
    listTools(undefined, connectionId).then((tools) => {
      const firstRead = tools.find((tool) => tool.enabled && tool.method === "GET");
      if (current && firstRead) {
        setSource(connectionStarter(firstRead.displayName));
        setStarterPrepared(true);
      }
    }).catch(() => {});
    return () => { current = false; };
  }, [connectionId, starterPrepared, activeId]);

  // Analysis no longer needs a chosen collection: requests resolve across every connected app.
  useEffect(() => {
    let current = true;
    const timer = window.setTimeout(() => {
      analyzeInsight({ source, connectionId: connectionId || undefined })
        .then((next) => { if (current) setAnalysis(next); })
        .catch((error: unknown) => {
          if (current) setAnalysis({ diagnostics: [{ span: { startOffset: 0, endOffset: 0, startLine: 1, startCol: 1, endLine: 1, endCol: 1 }, severity: "ERROR", code: "RQI500", message: sourceError(error) }], completions: [], params: [], outline: [] });
        });
    }, 260);
    return () => { current = false; window.clearTimeout(timer); };
  }, [source, connectionId]);

  useEffect(() => {
    if (!analysis) return;
    setParameters((current) => {
      const next = { ...current };
      for (const param of analysis.params) if (!(param.name in next)) next[param.name] = param.defaultValue;
      return next;
    });
  }, [analysis]);

  useEffect(() => setSaveState("idle"), [source, name, connectionId]);

  const run = async () => {
    setIsRunning(true);
    setRunError("");
    try {
      const next = await loadInsightData({ source, connectionId: connectionId || undefined, parameters });
      setData(next);
      setAnalysis((current) => current ? { ...current, diagnostics: next.diagnostics, params: next.params, outline: next.outline } : current);
    } catch (error) {
      setRunError(sourceError(error));
    } finally {
      setIsRunning(false);
    }
  };

  const save = async () => {
    setSaveState("saving");
    setRunError("");
    try {
      const payload = { name, source, connectionId: connectionId || null };
      const stored = activeId ? await updateInsight(activeId, payload) : await createInsight(payload);
      setActiveId(stored.id);
      setSaveState("saved");
      await refreshLibrary();
    } catch (error) {
      setSaveState("idle");
      setRunError(sourceError(error));
    }
  };

  const open = (insight: SavedInsight) => {
    setActiveId(insight.id);
    setName(insight.name);
    setSource(insight.source);
    setConnectionId(insight.connectionId ?? "");
    setData(null);
    setStarterPrepared(true);
  };

  const startNew = () => {
    setActiveId(null);
    setName("Untitled insight");
    setSource(EXAMPLE_INSIGHT);
    setData(null);
    setStarterPrepared(true);
  };

  const remove = async (insight: SavedInsight) => {
    try {
      await deleteInsight(insight.id);
      if (insight.id === activeId) startNew();
      await refreshLibrary();
    } catch (error) {
      setRunError(sourceError(error));
    }
  };

  return <section className="insight-page" aria-labelledby="insight-page-title">
    <header className="insight-page-header">
      <div>
        <p className="eyebrow"><CodeIcon size={14} /> Insight workspace</p>
        <h1 id="insight-page-title">Insights</h1>
        <p>Write RQL beside the view it drives. One insight can read from several connected apps.</p>
      </div>
      <div className="insight-actions">
        <label className="insight-name-field">
          <span>Name</span>
          <input value={name} onChange={(event) => setName(event.target.value)} aria-label="Insight name" />
        </label>
        <label className="insight-connection-picker">
          <span>Default app</span>
          <select value={connectionId} onChange={(event) => setConnectionId(event.target.value)}>
            <option value="">All connected apps</option>
            {usableConnections.map((connection) => <option value={connection.id} key={connection.id}>{connection.name}</option>)}
          </select>
        </label>
        <button type="button" className="btn" onClick={save} disabled={saveState === "saving"}>
          <SaveIcon size={15} /> {saveState === "saving" ? "Saving…" : saveState === "saved" ? "Saved" : "Save"}
        </button>
        <button type="button" className="insight-run-button" onClick={run} disabled={isRunning}>
          <PlayIcon size={15} /> {isRunning ? "Running…" : "Run insight"}
        </button>
      </div>
    </header>

    {usableConnections.length === 0 && <div className="insight-connection-note">Import and connect an API collection on the Connections page to run an insight.</div>}
    {usableConnections.length > 1 && <p className="insight-scope-hint">
      Requests resolve across every connected app. Qualify a name when two apps share it —
      <code>request "{usableConnections[0].name}: List orders"</code> — or scope a whole section with
      <code>use collection "{usableConnections[0].name}";</code>.
    </p>}
    <ParameterControls params={analysis?.params ?? data?.params ?? []} values={parameters}
      onChange={(name, value) => setParameters((current) => ({ ...current, [name]: value }))} />

    <div className="insight-workspace">
      <InsightLibrary insights={saved} activeId={activeId} onOpen={open} onNew={startNew} onDelete={remove} />
      <section className="insight-editor-panel">
        <header><span>insight.rqd</span><small>Markdown · RQL · components</small></header>
        <InsightEditor value={source} onChange={setSource} diagnostics={analysis?.diagnostics ?? []} completions={analysis?.completions ?? []} />
        <footer><Diagnostics diagnostics={analysis?.diagnostics ?? []} /></footer>
      </section>
      <section className="insight-preview-panel" aria-live="polite">
        <header><span>Live insight</span><small>{data ? "Last successful run" : "Run to fetch API data"}</small></header>
        {runError && <div className="insight-run-error"><AlertIcon size={15} /> {runError}</div>}
        {data ? <RenderInsight data={data} /> : <div className="insight-preview-empty">
          <CodeIcon size={24} />
          <h2>Ready for a query</h2>
          <p>Run the document to fetch data and render its datasets.</p>
        </div>}
      </section>
    </div>
  </section>;
}
