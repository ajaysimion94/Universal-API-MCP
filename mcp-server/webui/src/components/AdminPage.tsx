import { useState, useEffect, useCallback } from "react";
import {
  fetchAuditLog,
  AuditEntry,
  AuditResponse,
  listTools,
  enableTool,
  ApiToolInfo,
} from "../api";
import { ShieldIcon, ClockIcon, CheckIcon, XIcon } from "../icons";

function getEventBadgeStyle(eventType: string) {
  switch (eventType) {
    case "TOOL_EXECUTED":
      return { bg: "rgba(46, 160, 67, 0.15)", fg: "#3fb950" };
    case "TOOL_REJECTED":
      return { bg: "rgba(248, 81, 73, 0.15)", fg: "#f85149" };
    case "TOOL_APPROVED":
      return { bg: "rgba(210, 153, 34, 0.15)", fg: "var(--c-accent)" };
    case "TOOL_FAILED":
      return { bg: "rgba(248, 81, 73, 0.25)", fg: "#f85149" };
    case "TOOL_EXPIRED":
      return { bg: "rgba(139, 148, 158, 0.15)", fg: "#8b949e" };
    case "TOOL_INVOKED":
      return { bg: "rgba(88, 166, 255, 0.15)", fg: "#58a6ff" };
    case "SEARCH_PERFORMED":
      return { bg: "rgba(139, 148, 158, 0.1)", fg: "var(--c-fg-muted)" };
    default:
      return { bg: "rgba(139, 148, 158, 0.1)", fg: "var(--c-fg-muted)" };
  }
}

const inputStyle = {
  background: "var(--c-bg-subtle)",
  border: "1px solid var(--c-border)",
  color: "var(--c-fg-normal)",
  padding: "6px 12px",
  borderRadius: "4px",
  fontSize: "13px",
  outline: "none",
};

function AuditLogTab() {
  const [logResponse, setLogResponse] = useState<AuditResponse | null>(null);
  const [actorFilter, setActorFilter] = useState("");
  const [typeFilter, setTypeFilter] = useState("");
  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");
  const [page, setPage] = useState(0);

  const loadData = useCallback(async () => {
    try {
      const res = await fetchAuditLog({
        actor: actorFilter || undefined,
        eventType: typeFilter || undefined,
        from: fromDate ? new Date(fromDate).toISOString() : undefined,
        to: toDate ? new Date(toDate).toISOString() : undefined,
        page,
        size: 50,
      });
      setLogResponse(res);
    } catch (e) {
      console.error(e);
    }
  }, [actorFilter, typeFilter, fromDate, toDate, page]);

  useEffect(() => {
    loadData();
    const interval = setInterval(loadData, 10000);
    return () => clearInterval(interval);
  }, [loadData]);

  const totalPages = logResponse ? Math.ceil(logResponse.total / logResponse.size) : 0;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "16px" }}>
      <div style={{ display: "flex", gap: "12px", flexWrap: "wrap", alignItems: "center" }}>
        <input
          type="text"
          placeholder="Filter by actor..."
          value={actorFilter}
          onChange={(e) => { setActorFilter(e.target.value); setPage(0); }}
          style={inputStyle}
        />
        <select
          value={typeFilter}
          onChange={(e) => { setTypeFilter(e.target.value); setPage(0); }}
          style={inputStyle}
        >
          <option value="">All Event Types</option>
          <option value="TOOL_EXECUTED">TOOL_EXECUTED</option>
          <option value="TOOL_REJECTED">TOOL_REJECTED</option>
          <option value="TOOL_APPROVED">TOOL_APPROVED</option>
          <option value="TOOL_FAILED">TOOL_FAILED</option>
          <option value="TOOL_EXPIRED">TOOL_EXPIRED</option>
          <option value="TOOL_INVOKED">TOOL_INVOKED</option>
          <option value="SEARCH_PERFORMED">SEARCH_PERFORMED</option>
        </select>
        <input
          type="date"
          value={fromDate}
          onChange={(e) => { setFromDate(e.target.value); setPage(0); }}
          style={inputStyle}
          title="From Date"
        />
        <input
          type="date"
          value={toDate}
          onChange={(e) => { setToDate(e.target.value); setPage(0); }}
          style={inputStyle}
          title="To Date"
        />
      </div>

      <div style={{ border: "1px solid var(--c-border)", borderRadius: "6px", overflowX: "auto" }}>
        <table style={{ width: "100%", borderCollapse: "collapse", fontSize: "13px", textAlign: "left" }}>
          <thead>
            <tr style={{ background: "var(--c-bg-subtle)", borderBottom: "1px solid var(--c-border)" }}>
              <th style={{ padding: "10px 16px", fontWeight: 500, color: "var(--c-fg-muted)" }}>Time</th>
              <th style={{ padding: "10px 16px", fontWeight: 500, color: "var(--c-fg-muted)" }}>Event Type</th>
              <th style={{ padding: "10px 16px", fontWeight: 500, color: "var(--c-fg-muted)" }}>Tool</th>
              <th style={{ padding: "10px 16px", fontWeight: 500, color: "var(--c-fg-muted)" }}>Actor</th>
              <th style={{ padding: "10px 16px", fontWeight: 500, color: "var(--c-fg-muted)" }}>Details</th>
            </tr>
          </thead>
          <tbody>
            {logResponse?.items.map((entry: AuditEntry) => {
              const badgeStyle = getEventBadgeStyle(entry.eventType);
              return (
                <tr key={entry.id} style={{ borderBottom: "1px solid var(--c-border)" }}>
                  <td style={{ padding: "10px 16px", whiteSpace: "nowrap" }}>
                    {new Date(entry.createdAt).toLocaleString()}
                  </td>
                  <td style={{ padding: "10px 16px" }}>
                    <span style={{
                      display: "inline-block",
                      padding: "2px 8px",
                      borderRadius: "12px",
                      fontSize: "11px",
                      fontWeight: 600,
                      backgroundColor: badgeStyle.bg,
                      color: badgeStyle.fg,
                    }}>
                      {entry.eventType}
                    </span>
                  </td>
                  <td style={{ padding: "10px 16px", fontFamily: "'JetBrains Mono', monospace", color: "var(--c-fg-normal)" }}>
                    {entry.toolName || "-"}
                  </td>
                  <td style={{ padding: "10px 16px" }}>{entry.actor || "-"}</td>
                  <td style={{ padding: "10px 16px", color: "var(--c-fg-muted)", maxWidth: "300px", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }} title={entry.error || entry.resultSummary || entry.arguments || ""}>
                    {entry.error || entry.resultSummary || entry.arguments || "-"}
                  </td>
                </tr>
              );
            })}
            {(!logResponse?.items || logResponse.items.length === 0) && (
              <tr>
                <td colSpan={5} style={{ padding: "24px", textAlign: "center", color: "var(--c-fg-muted)" }}>
                  No audit events found.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", fontSize: "13px" }}>
        <span style={{ color: "var(--c-fg-muted)" }}>
          Showing {logResponse?.items.length || 0} events
        </span>
        <div style={{ display: "flex", gap: "8px" }}>
          <button
            disabled={page === 0}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            style={{ ...inputStyle, cursor: page === 0 ? "not-allowed" : "pointer", opacity: page === 0 ? 0.5 : 1 }}
          >
            Previous
          </button>
          <span style={{ padding: "6px 12px", color: "var(--c-fg-muted)" }}>
            Page {page + 1} of {Math.max(1, totalPages)}
          </span>
          <button
            disabled={page >= totalPages - 1}
            onClick={() => setPage((p) => p + 1)}
            style={{ ...inputStyle, cursor: page >= totalPages - 1 ? "not-allowed" : "pointer", opacity: page >= totalPages - 1 ? 0.5 : 1 }}
          >
            Next
          </button>
        </div>
      </div>
    </div>
  );
}

function ToolApprovalsTab() {
  const [tools, setTools] = useState<ApiToolInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [errorMsg, setErrorMsg] = useState("");
  const [successMsg, setSuccessMsg] = useState("");

  const loadTools = useCallback(async () => {
    try {
      const res = await listTools();
      setTools(res.filter((t) => t.pending));
    } catch (e: any) {
      setErrorMsg(e.message || "Failed to load tools");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadTools();
  }, [loadTools]);

  const handleApprove = async (id: string) => {
    try {
      setErrorMsg("");
      setSuccessMsg("");
      await enableTool(id);
      setSuccessMsg("Tool approved successfully.");
      await loadTools();
    } catch (e: any) {
      setErrorMsg(e.message || "Failed to approve tool");
    }
  };

  if (loading) {
    return <div style={{ color: "var(--c-fg-muted)", fontSize: "14px" }}>Loading pending tools...</div>;
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "16px" }}>
      {errorMsg && <div style={{ color: "#f85149", fontSize: "14px", padding: "12px", background: "rgba(248, 81, 73, 0.1)", borderRadius: "4px" }}>{errorMsg}</div>}
      {successMsg && <div style={{ color: "#3fb950", fontSize: "14px", padding: "12px", background: "rgba(46, 160, 67, 0.1)", borderRadius: "4px" }}>{successMsg}</div>}

      <div style={{ display: "flex", flexDirection: "column", gap: "12px" }}>
        {tools.length === 0 ? (
          <div style={{ padding: "32px", textAlign: "center", color: "var(--c-fg-muted)", border: "1px dashed var(--c-border)", borderRadius: "6px" }}>
            No pending tools to approve.
          </div>
        ) : (
          tools.map((tool) => (
            <div key={tool.id} style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "16px", border: "1px solid var(--c-border)", borderRadius: "6px", background: "var(--c-bg-subtle)" }}>
              <div>
                <div style={{ display: "flex", alignItems: "center", gap: "8px", marginBottom: "4px" }}>
                  <span style={{ fontFamily: "'JetBrains Mono', monospace", fontSize: "14px", color: "var(--c-accent)", fontWeight: 600 }}>{tool.name}</span>
                  <span style={{ fontSize: "10px", padding: "2px 6px", borderRadius: "4px", background: "rgba(139, 148, 158, 0.15)", color: "var(--c-fg-normal)", fontWeight: 600 }}>
                    {tool.method.toUpperCase()}
                  </span>
                </div>
                <div style={{ fontSize: "13px", color: "var(--c-fg-muted)" }}>
                  {tool.displayName} • App: {tool.appSlug}
                </div>
              </div>
              <div style={{ display: "flex", alignItems: "center", gap: "12px" }}>
                <button
                  style={{ display: "flex", alignItems: "center", gap: "6px", background: "none", border: "none", color: "var(--c-fg-muted)", cursor: "not-allowed", fontSize: "13px", fontWeight: 500, opacity: 0.5 }}
                  disabled
                  title="Rejecting is not yet supported in Phase 3"
                >
                  <XIcon size={14} /> Reject
                </button>
                <button
                  onClick={() => handleApprove(tool.id)}
                  style={{ display: "flex", alignItems: "center", gap: "6px", background: "var(--c-accent)", border: "none", color: "#000", padding: "6px 12px", borderRadius: "4px", cursor: "pointer", fontSize: "13px", fontWeight: 600 }}
                >
                  <CheckIcon size={14} /> Approve
                </button>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

export function AdminPage() {
  const [activeTab, setActiveTab] = useState<"audit" | "approvals">("audit");

  return (
    <div style={{ padding: "32px", maxWidth: "1200px", margin: "0 auto", color: "var(--c-fg-normal)", fontFamily: "'Hanken Grotesk', sans-serif" }}>
      <header style={{ display: "flex", alignItems: "center", gap: "12px", marginBottom: "32px" }}>
        <ShieldIcon size={24} style={{ color: "var(--c-accent)" }} />
        <h1 style={{ margin: 0, fontSize: "24px", fontWeight: 600 }}>Admin Console</h1>
      </header>

      <div style={{ display: "flex", gap: "24px", borderBottom: "1px solid var(--c-border)", marginBottom: "24px" }}>
        <button
          onClick={() => setActiveTab("audit")}
          style={{
            background: "none",
            border: "none",
            borderBottom: activeTab === "audit" ? "2px solid var(--c-accent)" : "2px solid transparent",
            color: activeTab === "audit" ? "var(--c-fg-normal)" : "var(--c-fg-muted)",
            padding: "8px 16px",
            fontSize: "14px",
            cursor: "pointer",
            fontWeight: 500,
            display: "flex",
            alignItems: "center",
            gap: "8px",
          }}
        >
          <ClockIcon size={16} />
          Audit Log
        </button>
        <button
          onClick={() => setActiveTab("approvals")}
          style={{
            background: "none",
            border: "none",
            borderBottom: activeTab === "approvals" ? "2px solid var(--c-accent)" : "2px solid transparent",
            color: activeTab === "approvals" ? "var(--c-fg-normal)" : "var(--c-fg-muted)",
            padding: "8px 16px",
            fontSize: "14px",
            cursor: "pointer",
            fontWeight: 500,
            display: "flex",
            alignItems: "center",
            gap: "8px",
          }}
        >
          <CheckIcon size={16} />
          Tool Approvals
        </button>
      </div>

      {activeTab === "audit" && <AuditLogTab />}
      {activeTab === "approvals" && <ToolApprovalsTab />}
    </div>
  );
}
