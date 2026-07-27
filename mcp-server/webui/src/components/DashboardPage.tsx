import { useEffect, useMemo, useState } from "react";
import {
  analyzeDashboard,
  type ConnectionInfo,
  type DashboardAnalysis,
  type DashboardComponent,
  type DashboardData,
  type DashboardDataset,
  type DashboardParam,
  type QueryDiagnostic,
  listConnections,
  listTools,
  loadDashboardData,
} from "../api";
import { AlertIcon, CodeIcon, PlayIcon } from "../icons";
import { DashboardEditor } from "./DashboardEditor";

const EXAMPLE_DASHBOARD = `---
title: API activity
params:
  minUser: { type: number, default: 1 }
---

# API activity

Choose a connected API collection, then edit this document and run it.

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
  return error instanceof Error ? error.message : "The dashboard could not be loaded.";
}

function datasetName(component: DashboardComponent): string | null {
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

function columnsFrom(component: DashboardComponent, dataset: DashboardDataset): string[] {
  const raw = component.props.columns;
  if (!raw) return dataset.columns;
  const values = Array.from(raw.matchAll(/"([^"\\]+)"/g)).map((match) => match[1]);
  return values.length > 0 ? values : dataset.columns;
}

function scalar(expression: string, datasets: Record<string, DashboardDataset>): string {
  const count = /^count\(([^)]+)\)$/.exec(expression.trim());
  if (count) return String(datasets[count[1].trim()]?.rows.length ?? 0);
  const aggregate = /^(avg|sum|min|max)\(([^.)]+)(?:\.([^)]+))?\)$/.exec(expression.trim());
  if (aggregate) {
    const dataset = datasets[aggregate[2]];
    const field = aggregate[3];
    const values = (dataset?.rows ?? [])
      .map((row) => asNumber(field ? row[field] : row.value))
      .filter((value): value is number => value !== null);
    if (values.length === 0) return "—";
    const value = aggregate[1] === "sum" ? values.reduce((total, item) => total + item, 0)
      : aggregate[1] === "avg" ? values.reduce((total, item) => total + item, 0) / values.length
        : aggregate[1] === "min" ? Math.min(...values) : Math.max(...values);
    return Number.isInteger(value) ? String(value) : value.toFixed(1);
  }
  const [datasetName, field] = expression.trim().split(".", 2);
  const dataset = datasets[datasetName];
  if (!dataset) return "—";
  if (!field) return String(dataset.rows.length);
  return asText(dataset.rows[0]?.[field]);
}

function TableTwin({ dataset, columns }: { dataset: DashboardDataset; columns: string[] }) {
  return (
    <div className="dashboard-table-scroll">
      <table className="dashboard-data-table">
        <thead>
          <tr>{columns.map((column) => <th key={column}>{column}</th>)}</tr>
        </thead>
        <tbody>
          {dataset.rows.slice(0, 100).map((row, index) => (
            <tr key={index}>{columns.map((column) => <td key={column}>{asText(row[column])}</td>)}</tr>
          ))}
        </tbody>
      </table>
      {dataset.rows.length > 100 && <p className="dashboard-table-note">Showing the first 100 of {dataset.rows.length} rows.</p>}
    </div>
  );
}

function BarChart({ component, dataset }: { component: DashboardComponent; dataset: DashboardDataset }) {
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
    <section className="dashboard-card dashboard-chart-card">
      <header className="dashboard-card-heading">
        <div><p className="dashboard-card-kicker">Comparison</p><h2>{title}</h2></div>
        <span>{points.length} categories</span>
      </header>
      {points.length === 0 ? <p className="dashboard-no-data">No numeric rows to chart.</p> : (
        <svg className="dashboard-bar-chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label={title}>
          <title>{title}</title>
          {[0, 0.5, 1].map((tick) => {
            const yPosition = top + plotHeight - tick * plotHeight;
            return <g key={tick}>
              <line x1={left} x2={width - right} y1={yPosition} y2={yPosition} className="dashboard-grid-line" />
              <text x={left - 9} y={yPosition + 4} textAnchor="end" className="dashboard-axis-label">{Math.round(maximum * tick)}</text>
            </g>;
          })}
          {points.map((point, index) => {
            const barHeight = (point.value / maximum) * plotHeight;
            const barX = left + index * slot + (slot - barWidth) / 2;
            const barY = top + plotHeight - barHeight;
            return <g key={`${point.label}-${index}`}>
              <rect x={barX} y={barY} width={barWidth} height={barHeight} rx="4" className="dashboard-bar" />
              <text x={barX + barWidth / 2} y={barY - 6} textAnchor="middle" className="dashboard-value-label">{point.value}</text>
              <text x={barX + barWidth / 2} y={top + plotHeight + 20} textAnchor="end" transform={`rotate(-35 ${barX + barWidth / 2} ${top + plotHeight + 20})`} className="dashboard-axis-label">{point.label}</text>
            </g>;
          })}
        </svg>
      )}
      <details className="dashboard-table-twin">
        <summary>Show chart data table</summary>
        <TableTwin dataset={dataset} columns={[x, y]} />
      </details>
    </section>
  );
}

function RenderDashboard({ data }: { data: DashboardData }) {
  const stats = data.outline.filter((component) => component.type === "Stat");
  const visualComponents = data.outline.filter((component) => component.type === "BarChart" || component.type === "DataTable");
  return (
    <div className="dashboard-rendered">
      {stats.length > 0 && <div className="dashboard-kpi-row">
        {stats.map((component, index) => (
          <section className="dashboard-stat" key={`${component.type}-${index}`}>
            <span>{component.props.label ?? "Metric"}</span>
            <strong>{scalar(component.props.value ?? "", data.datasets)}</strong>
          </section>
        ))}
      </div>}
      {visualComponents.map((component, index) => {
        const name = datasetName(component);
        const dataset = name ? data.datasets[name] : undefined;
        if (!dataset) return null;
        if (component.type === "BarChart") return <BarChart key={`${component.type}-${index}`} component={component} dataset={dataset} />;
        return (
          <section className="dashboard-card" key={`${component.type}-${index}`}>
            <header className="dashboard-card-heading">
              <div><p className="dashboard-card-kicker">Dataset</p><h2>{name}</h2></div>
              <span>{dataset.rows.length} rows</span>
            </header>
            <TableTwin dataset={dataset} columns={columnsFrom(component, dataset)} />
          </section>
        );
      })}
    </div>
  );
}

function ParameterControls({ params, values, onChange }: {
  params: DashboardParam[];
  values: Record<string, unknown>;
  onChange: (name: string, value: unknown) => void;
}) {
  if (params.length === 0) return null;
  return <div className="dashboard-params" aria-label="Dashboard parameters">
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
  if (relevant.length === 0) return <p className="dashboard-analysis-ok">Document checks clean.</p>;
  return <div className="dashboard-diagnostics">
    {relevant.map((diagnostic, index) => <p key={`${diagnostic.code}-${index}`} className={`is-${diagnostic.severity.toLowerCase()}`}>
      <AlertIcon size={14} /> <b>{diagnostic.code}</b> {diagnostic.message}
    </p>)}
    {diagnostics.length > relevant.length && <span>+{diagnostics.length - relevant.length} more checks</span>}
  </div>;
}

export function DashboardPage() {
  const [source, setSource] = useState(EXAMPLE_DASHBOARD);
  const [connections, setConnections] = useState<ConnectionInfo[]>([]);
  const [connectionId, setConnectionId] = useState("");
  const [analysis, setAnalysis] = useState<DashboardAnalysis | null>(null);
  const [data, setData] = useState<DashboardData | null>(null);
  const [parameters, setParameters] = useState<Record<string, unknown>>({});
  const [isRunning, setIsRunning] = useState(false);
  const [runError, setRunError] = useState("");
  const [starterPrepared, setStarterPrepared] = useState(false);

  const usableConnections = useMemo(() => connections.filter((connection) => connection.status === "CONNECTED"), [connections]);

  useEffect(() => {
    listConnections().then((items) => {
      setConnections(items);
      const first = items.find((item) => item.status === "CONNECTED");
      if (first) setConnectionId((current) => current || first.id);
    }).catch(() => {});
  }, []);

  useEffect(() => {
    if (!connectionId || starterPrepared) return;
    let current = true;
    listTools(undefined, connectionId).then((tools) => {
      const firstRead = tools.find((tool) => tool.enabled && tool.method === "GET");
      if (current && firstRead) {
        setSource(connectionStarter(firstRead.displayName));
        setStarterPrepared(true);
      }
    }).catch(() => {});
    return () => { current = false; };
  }, [connectionId, starterPrepared]);

  useEffect(() => {
    if (!connectionId) {
      setAnalysis(null);
      return;
    }
    let current = true;
    const timer = window.setTimeout(() => {
      analyzeDashboard({ source, connectionId })
        .then((next) => { if (current) setAnalysis(next); })
        .catch((error: unknown) => {
          if (current) setAnalysis({ diagnostics: [{ span: { startOffset: 0, endOffset: 0, startLine: 1, startCol: 1, endLine: 1, endCol: 1 }, severity: "ERROR", code: "RQD500", message: sourceError(error) }], completions: [], params: [], outline: [] });
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

  const run = async () => {
    if (!connectionId) {
      setRunError("Connect an API collection first, then choose it here.");
      return;
    }
    setIsRunning(true);
    setRunError("");
    try {
      const next = await loadDashboardData({ source, connectionId, parameters });
      setData(next);
      setAnalysis((current) => current ? { ...current, diagnostics: next.diagnostics, params: next.params, outline: next.outline } : current);
    } catch (error) {
      setRunError(sourceError(error));
    } finally {
      setIsRunning(false);
    }
  };

  return <main className="dashboard-page">
    <header className="dashboard-page-header">
      <div>
        <p className="eyebrow"><CodeIcon size={14} /> RQD workspace</p>
        <h1>API dashboards</h1>
        <p>Write RQL beside the view it drives. Queries run through enabled read tools only.</p>
      </div>
      <div className="dashboard-actions">
        <label className="dashboard-connection-picker">
          <span>API collection</span>
          <select value={connectionId} onChange={(event) => setConnectionId(event.target.value)}>
            <option value="">Choose a connected collection</option>
            {usableConnections.map((connection) => <option value={connection.id} key={connection.id}>{connection.name}</option>)}
          </select>
        </label>
        <button type="button" className="dashboard-run-button" onClick={run} disabled={isRunning || !connectionId}>
          <PlayIcon size={15} /> {isRunning ? "Running…" : "Run dashboard"}
        </button>
      </div>
    </header>

    {!connectionId && <div className="dashboard-connection-note">Import and connect an API collection on the Connections page to run this dashboard.</div>}
    <ParameterControls params={analysis?.params ?? data?.params ?? []} values={parameters}
      onChange={(name, value) => setParameters((current) => ({ ...current, [name]: value }))} />

    <div className="dashboard-workspace">
      <section className="dashboard-editor-panel">
        <header><span>dashboard.rqd</span><small>Markdown · RQL · components</small></header>
        <DashboardEditor value={source} onChange={setSource} diagnostics={analysis?.diagnostics ?? []} completions={analysis?.completions ?? []} />
        <footer><Diagnostics diagnostics={analysis?.diagnostics ?? []} /></footer>
      </section>
      <section className="dashboard-preview-panel" aria-live="polite">
        <header><span>Live dashboard</span><small>{data ? "Last successful run" : "Run to fetch API data"}</small></header>
        {runError && <div className="dashboard-run-error"><AlertIcon size={15} /> {runError}</div>}
        {data ? <RenderDashboard data={data} /> : <div className="dashboard-preview-empty">
          <CodeIcon size={24} />
          <h2>Ready for a query</h2>
          <p>Choose a collection, then run the document to render its datasets.</p>
        </div>}
      </section>
    </div>
  </main>;
}
