import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import {
  ApiToolInfo,
  ConnectionInfo,
  ToolGroupDetail,
  ToolGroupInfo,
  ToolGroupMemberInput,
  createGroup,
  deleteGroup,
  disableGroup,
  disableTool,
  enableGroup,
  enableTool,
  getGroup,
  listConnections,
  listGroups,
  listTools,
  setGroupMembers,
} from "../api";
import {
  AlertIcon,
  CheckCircleIcon,
  ChevronRightIcon,
  GroupIcon,
  PlusIcon,
  SearchIcon,
  TrashIcon,
  XIcon,
} from "../icons";
import { RequestBuilderPanel } from "./RequestBuilderPanel";

const TABS_STORAGE_KEY = "mcp.apps.openTabs.v1";

type OpenTab =
  | { kind: "tool"; id: string; toolId: string }
  | { kind: "draft"; id: string; connectionId: string };

interface StoredTabs {
  toolIds: string[];
  activeToolId: string | null;
}

function loadStoredTabs(): StoredTabs | null {
  try {
    const raw = localStorage.getItem(TABS_STORAGE_KEY);
    return raw ? (JSON.parse(raw) as StoredTabs) : null;
  } catch {
    return null;
  }
}

function plural(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? "" : "s"}`;
}

export function AppsPage() {
  const [connections, setConnections] = useState<ConnectionInfo[]>([]);
  const [tools, setTools] = useState<ApiToolInfo[]>([]);
  const [groups, setGroups] = useState<ToolGroupInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});

  // group selection / browse
  const [selectedGroupId, setSelectedGroupId] = useState<string | null>(null);
  const [groupDetail, setGroupDetail] = useState<ToolGroupDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [showGroupForm, setShowGroupForm] = useState(false);

  // edit-members mode
  const [editing, setEditing] = useState(false);
  const [editApps, setEditApps] = useState<Set<string>>(new Set());
  const [editTools, setEditTools] = useState<Set<string>>(new Set());
  const [saving, setSaving] = useState(false);

  // open request tabs — the Postman-style workspace
  const [openTabs, setOpenTabs] = useState<OpenTab[]>([]);
  const [activeTabId, setActiveTabId] = useState<string | null>(null);
  const hydratedRef = useRef(false);

  const load = useCallback(async () => {
    try {
      const [conns, allTools, allGroups] = await Promise.all([
        listConnections(),
        listTools(),
        listGroups(),
      ]);
      setConnections(conns.filter((c) => c.type === "API_COLLECTION"));
      setTools(allTools);
      setGroups(allGroups);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load apps");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  // Restore previously open tabs once tools have loaded.
  useEffect(() => {
    if (hydratedRef.current || loading) return;
    hydratedRef.current = true;
    const stored = loadStoredTabs();
    if (!stored) return;
    const validIds = stored.toolIds.filter((id) => tools.some((t) => t.id === id));
    if (validIds.length === 0) return;
    setOpenTabs(validIds.map((id) => ({ kind: "tool", id, toolId: id })));
    setActiveTabId(
      stored.activeToolId && validIds.includes(stored.activeToolId) ? stored.activeToolId : validIds[0],
    );
  }, [loading, tools]);

  // Persist open tool tabs across reloads (drafts are ephemeral, not persisted).
  useEffect(() => {
    const toolIds = openTabs.filter((t) => t.kind === "tool").map((t) => t.toolId);
    const activeToolId = openTabs.some((t) => t.kind === "tool" && t.id === activeTabId)
      ? activeTabId
      : null;
    localStorage.setItem(TABS_STORAGE_KEY, JSON.stringify({ toolIds, activeToolId }));
  }, [openTabs, activeTabId]);

  // Drop tabs whose tool was deleted elsewhere (e.g. connection removed).
  useEffect(() => {
    if (loading) return;
    const stillValid = openTabs.filter((t) => t.kind !== "tool" || tools.some((x) => x.id === t.toolId));
    if (stillValid.length !== openTabs.length) {
      setOpenTabs(stillValid);
      setActiveTabId((cur) => (cur && stillValid.some((t) => t.id === cur) ? cur : (stillValid[0]?.id ?? null)));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tools, loading]);

  const refreshDetail = useCallback(async (id: string) => {
    try {
      setGroupDetail(await getGroup(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load group");
    }
  }, []);

  const selectGroup = useCallback(async (id: string | null) => {
    setSelectedGroupId(id);
    setGroupDetail(null);
    setEditing(false);
    setNotice(null);
    if (!id) return;
    setDetailLoading(true);
    try {
      setGroupDetail(await getGroup(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load group");
      setSelectedGroupId(null);
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const toolsByConnection = useMemo(() => {
    const map = new Map<string, ApiToolInfo[]>();
    for (const tool of tools) {
      const list = map.get(tool.connectionId) ?? [];
      list.push(tool);
      map.set(tool.connectionId, list);
    }
    return map;
  }, [tools]);

  // A connection's @-slug only exists on its tools, not on the connection itself.
  const slugByConnection = useMemo(() => {
    const map = new Map<string, string>();
    for (const [connId, connTools] of toolsByConnection) {
      if (connTools.length > 0) map.set(connId, connTools[0].appSlug);
    }
    return map;
  }, [toolsByConnection]);

  const memberAppIds = useMemo(
    () => new Set((groupDetail?.apps ?? []).map((a) => a.id)),
    [groupDetail],
  );
  const memberToolIds = useMemo(
    () => new Set((groupDetail?.tools ?? []).map((t) => t.id)),
    [groupDetail],
  );

  const sections = useMemo(() => {
    const q = query.trim().toLowerCase();
    const result: {
      conn: ConnectionInfo;
      visible: ApiToolInfo[];
      scopedEnabled: number;
      scopedTotal: number;
    }[] = [];
    for (const conn of connections) {
      const all = toolsByConnection.get(conn.id) ?? [];
      // Browse mode with a group selected: whole-app members show every endpoint,
      // other apps show only their direct endpoint members.
      const scoped =
        !editing && selectedGroupId && groupDetail
          ? memberAppIds.has(conn.id)
            ? all
            : all.filter((t) => memberToolIds.has(t.id))
          : all;
      const slug = slugByConnection.get(conn.id) ?? "";
      const appHit = !!q && (conn.name.toLowerCase().includes(q) || slug.includes(q));
      const visible = !q
        ? scoped
        : appHit
          ? scoped
          : scoped.filter(
              (t) =>
                t.name.toLowerCase().includes(q) ||
                t.displayName.toLowerCase().includes(q) ||
                t.description.toLowerCase().includes(q),
            );
      const inScope =
        editing ||
        !selectedGroupId ||
        !groupDetail ||
        memberAppIds.has(conn.id) ||
        all.some((t) => memberToolIds.has(t.id));
      if (!inScope) continue;
      if (q && !appHit && visible.length === 0) continue;
      result.push({
        conn,
        visible,
        scopedEnabled: scoped.filter((t) => t.enabled).length,
        scopedTotal: scoped.length,
      });
    }
    return result;
  }, [
    connections,
    toolsByConnection,
    slugByConnection,
    query,
    editing,
    selectedGroupId,
    groupDetail,
    memberAppIds,
    memberToolIds,
  ]);

  const handleCreateGroup = async (name: string, description?: string) => {
    try {
      const created = await createGroup(name, description);
      setShowGroupForm(false);
      await load();
      await selectGroup(created.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to create group");
    }
  };

  const handleDeleteGroup = async () => {
    if (!groupDetail) return;
    if (
      !window.confirm(
        `Delete group "${groupDetail.name}"? Apps and endpoints stay connected — only the grouping is removed.`,
      )
    )
      return;
    try {
      await deleteGroup(groupDetail.id);
      await load();
      await selectGroup(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to delete group");
    }
  };

  const handleBatch = async (enable: boolean) => {
    if (!groupDetail) return;
    try {
      const { updated } = enable
        ? await enableGroup(groupDetail.id)
        : await disableGroup(groupDetail.id);
      setNotice(`${updated} endpoint${updated === 1 ? "" : "s"} ${enable ? "enabled" : "disabled"}`);
      await load();
      await refreshDetail(groupDetail.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to update group");
    }
  };

  const handleToggleTool = async (tool: ApiToolInfo, enabled: boolean) => {
    try {
      const updated = enabled ? await enableTool(tool.id) : await disableTool(tool.id);
      setTools((prev) => prev.map((t) => (t.id === updated.id ? updated : t)));
      setGroups(await listGroups());
      if (selectedGroupId) await refreshDetail(selectedGroupId);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to update endpoint");
    }
  };

  const openToolTab = (toolId: string) => {
    setOpenTabs((prev) => (prev.some((t) => t.id === toolId) ? prev : [...prev, { kind: "tool", id: toolId, toolId }]));
    setActiveTabId(toolId);
  };

  const openDraftTab = (connectionId: string) => {
    const id = `draft-${connectionId}-${Date.now()}`;
    setOpenTabs((prev) => [...prev, { kind: "draft", id, connectionId }]);
    setActiveTabId(id);
  };

  const closeTab = (id: string) => {
    setOpenTabs((prev) => {
      const idx = prev.findIndex((t) => t.id === id);
      const next = prev.filter((t) => t.id !== id);
      if (activeTabId === id) {
        const fallback = next[idx] ?? next[idx - 1];
        setActiveTabId(fallback ? fallback.id : null);
      }
      return next;
    });
  };

  const handleDraftSaved = async (draftId: string, tool: ApiToolInfo) => {
    setOpenTabs((prev) =>
      prev.map((t) => (t.id === draftId ? { kind: "tool", id: tool.id, toolId: tool.id } : t)),
    );
    setActiveTabId(tool.id);
    await load();
  };

  const handleToolDeleted = async (tabId: string) => {
    closeTab(tabId);
    await load();
    if (selectedGroupId) await refreshDetail(selectedGroupId);
  };

  const startEditing = () => {
    if (!groupDetail) return;
    setEditApps(new Set(groupDetail.apps.map((a) => a.id)));
    setEditTools(new Set(groupDetail.tools.map((t) => t.id)));
    // Expand every app so endpoint checkboxes are reachable without extra clicks.
    setExpanded(Object.fromEntries(connections.map((c) => [c.id, true])));
    setNotice(null);
    setEditing(true);
  };

  const toggleEditApp = (id: string, checked: boolean) => {
    setEditApps((prev) => {
      const next = new Set(prev);
      if (checked) next.add(id);
      else next.delete(id);
      return next;
    });
  };

  const toggleEditTool = (id: string, checked: boolean) => {
    setEditTools((prev) => {
      const next = new Set(prev);
      if (checked) next.add(id);
      else next.delete(id);
      return next;
    });
  };

  const handleSaveMembers = async () => {
    if (!groupDetail) return;
    setSaving(true);
    try {
      const members: ToolGroupMemberInput[] = [];
      for (const appId of editApps) members.push({ memberType: "APP", memberId: appId });
      for (const toolId of editTools) {
        const tool = tools.find((t) => t.id === toolId);
        // Endpoints under a whole-app member are implied — don't store them directly.
        if (!tool || editApps.has(tool.connectionId)) continue;
        members.push({ memberType: "TOOL", memberId: toolId });
      }
      await setGroupMembers(groupDetail.id, members);
      setEditing(false);
      setNotice("Membership saved");
      await load();
      await refreshDetail(groupDetail.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to save members");
    } finally {
      setSaving(false);
    }
  };

  const groupInfo = groups.find((g) => g.id === selectedGroupId) ?? null;

  return (
    <>
      <aside className="sidebar apps-sidebar">
        <div className="apps-groups-header">
          <span className="apps-groups-title">Groups</span>
          <button
            className="btn btn-ghost apps-new-group-btn"
            onClick={() => setShowGroupForm((v) => !v)}
          >
            <PlusIcon size={13} />
            New group
          </button>
        </div>

        {showGroupForm && (
          <NewGroupForm onCancel={() => setShowGroupForm(false)} onCreate={handleCreateGroup} />
        )}

        <button
          className={`group-row ${selectedGroupId === null ? "is-active" : ""}`}
          onClick={() => selectGroup(null)}
        >
          <span className="group-row-name">All apps</span>
          <span className="group-row-counts mono">
            {plural(connections.length, "app")} · {plural(tools.length, "endpoint")}
          </span>
        </button>

        {groups.map((g) => (
          <button
            key={g.id}
            className={`group-row ${selectedGroupId === g.id ? "is-active" : ""}`}
            onClick={() => selectGroup(g.id)}
          >
            <span className="group-row-name">{g.name}</span>
            <span className="group-row-slug mono">@{g.slug}</span>
            <span className="group-row-counts mono">
              {plural(g.appCount, "app")} · {plural(g.toolCount, "endpoint")} · {g.enabledToolCount}{" "}
              enabled
            </span>
          </button>
        ))}

        {!loading && groups.length === 0 && !showGroupForm && (
          <p className="group-empty">
            No groups yet — create one to batch-enable endpoints and address them as @group in
            search.
          </p>
        )}

        <div className="apps-sidebar-divider" />

        <div className="search-field apps-sidebar-search">
          <SearchIcon size={14} className="search-icon" />
          <input
            type="text"
            className="search-input"
            placeholder="Filter apps and endpoints"
            aria-label="Filter apps and endpoints"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>

        <div className="apps-tree">
          {loading ? (
            <div className="plugins-skeleton" style={{ padding: "0 var(--space-sm)" }}>
              {[0, 1].map((i) => (
                <div key={i} className="plugin-row-skeleton">
                  <div className="skel-line skel-title" />
                  <div className="skel-line skel-excerpt" />
                </div>
              ))}
            </div>
          ) : connections.length === 0 ? (
            <p className="group-empty">
              No apps connected yet. Import a collection on the Connections page to get started.
            </p>
          ) : sections.length === 0 ? (
            <p className="group-empty">
              Nothing matches{selectedGroupId ? " this group" : ""} — try a different search.
            </p>
          ) : (
            sections.map(({ conn, visible }) => (
              <AppTreeSection
                key={conn.id}
                conn={conn}
                slug={slugByConnection.get(conn.id) ?? ""}
                tools={visible}
                open={query.trim() !== "" || !!expanded[conn.id]}
                onToggleOpen={() => setExpanded((prev) => ({ ...prev, [conn.id]: !prev[conn.id] }))}
                activeToolId={activeTabId}
                onOpenTool={openToolTab}
                onNewRequest={() => openDraftTab(conn.id)}
              />
            ))
          )}
        </div>
      </aside>

      <main className="main">
        {error && (
          <div className="error-banner" role="alert">
            {error}
            <button className="error-dismiss" onClick={() => setError(null)} aria-label="Dismiss">
              ×
            </button>
          </div>
        )}

        {selectedGroupId && (
          <div className="group-banner">
            {detailLoading || !groupDetail ? (
              <span className="group-banner-loading">Loading group…</span>
            ) : (
              <>
                <div className="group-banner-info">
                  <div className="group-banner-title">
                    <GroupIcon size={15} />
                    <span>{groupDetail.name}</span>
                    <span className="group-banner-slug mono">@{groupDetail.slug}</span>
                  </div>
                  {groupDetail.description && (
                    <p className="group-banner-desc">{groupDetail.description}</p>
                  )}
                  {editing && (
                    <p className="group-banner-desc">
                      Tick whole apps or individual endpoints. A ticked app already includes all of
                      its endpoints.
                    </p>
                  )}
                  <div className="group-banner-counts mono">
                    {plural(groupInfo?.appCount ?? groupDetail.apps.length, "app")} ·{" "}
                    {plural(groupInfo?.toolCount ?? groupDetail.tools.length, "endpoint")} ·{" "}
                    {groupInfo?.enabledToolCount ?? 0} enabled
                    {notice && <span className="group-banner-notice"> — {notice}</span>}
                  </div>
                </div>
                <div className="group-banner-actions">
                  {editing ? (
                    <>
                      <button
                        className="btn btn-ghost"
                        onClick={() => setEditing(false)}
                        disabled={saving}
                      >
                        Cancel
                      </button>
                      <button
                        className="btn btn-primary"
                        onClick={handleSaveMembers}
                        disabled={saving}
                      >
                        {saving ? "Saving…" : "Save members"}
                      </button>
                    </>
                  ) : (
                    <>
                      <button className="btn btn-ghost" onClick={() => handleBatch(true)}>
                        Enable all
                      </button>
                      <button className="btn btn-ghost" onClick={() => handleBatch(false)}>
                        Disable all
                      </button>
                      <button className="btn btn-ghost" onClick={startEditing}>
                        Edit members
                      </button>
                      <button className="btn btn-ghost btn-danger" onClick={handleDeleteGroup}>
                        <TrashIcon size={13} />
                        Delete
                      </button>
                    </>
                  )}
                </div>
              </>
            )}
          </div>
        )}

        {loading ? (
          <div className="plugins-skeleton" style={{ padding: "var(--space-md)" }}>
            {[0, 1, 2].map((i) => (
              <div key={i} className="plugin-row-skeleton">
                <div className="skel-line skel-title" />
                <div className="skel-line skel-excerpt" />
              </div>
            ))}
          </div>
        ) : editing ? (
          <div className="apps-directory">
            {sections.map(({ conn, visible, scopedEnabled, scopedTotal }) => (
              <AppSection
                key={conn.id}
                conn={conn}
                slug={slugByConnection.get(conn.id) ?? ""}
                tools={visible}
                enabledCount={scopedEnabled}
                totalCount={scopedTotal}
                open={!!expanded[conn.id]}
                onToggleOpen={() => setExpanded((prev) => ({ ...prev, [conn.id]: !prev[conn.id] }))}
                appChecked={editApps.has(conn.id)}
                editTools={editTools}
                onToggleApp={toggleEditApp}
                onToggleTool={toggleEditTool}
              />
            ))}
          </div>
        ) : connections.length === 0 ? (
          <div className="table-empty">
            <span className="empty-line">No apps connected yet.</span>
            <span className="empty-hint">
              Import a Postman collection or OpenAPI spec on the{" "}
              <Link to="/connections" className="empty-link">
                Connections
              </Link>{" "}
              page — every request becomes a callable endpoint here.
            </span>
          </div>
        ) : (
          <>
            <div className="rb-tabstrip">
              {openTabs.map((tab) => {
                const label =
                  tab.kind === "draft"
                    ? { method: "NEW", name: "New request" }
                    : (() => {
                        const tool = tools.find((t) => t.id === tab.toolId);
                        return tool ? { method: tool.method, name: tool.displayName || tool.name } : null;
                      })();
                if (!label) return null;
                return (
                  <button
                    key={tab.id}
                    type="button"
                    className={`rb-tabstrip-tab ${activeTabId === tab.id ? "is-active" : ""}`}
                    onClick={() => setActiveTabId(tab.id)}
                  >
                    <span
                      className={`method-badge mono rb-tabstrip-method ${label.method === "GET" || label.method === "NEW" ? "" : "method-write"} ${label.method === "DELETE" ? "method-danger" : ""}`}
                    >
                      {label.method}
                    </span>
                    <span className="rb-tabstrip-name">{label.name}</span>
                    <span
                      className="rb-tabstrip-close"
                      role="button"
                      tabIndex={-1}
                      onClick={(e) => {
                        e.stopPropagation();
                        closeTab(tab.id);
                      }}
                      aria-label="Close tab"
                    >
                      <XIcon size={11} />
                    </span>
                  </button>
                );
              })}
            </div>

            <div className="rb-workspace">
              {openTabs.length === 0 ? (
                <div className="table-empty">
                  <span className="empty-line">No request open.</span>
                  <span className="empty-hint">
                    Pick an endpoint from the sidebar, or hover an app and click + to start a new
                    request.
                  </span>
                </div>
              ) : (
                openTabs.map((tab) => (
                  <div key={tab.id} style={{ display: tab.id === activeTabId ? "block" : "none" }}>
                    {tab.kind === "draft" ? (
                      <RequestBuilderPanel
                        tool={null}
                        connectionId={tab.connectionId}
                        onSaved={(saved) => handleDraftSaved(tab.id, saved)}
                      />
                    ) : (
                      (() => {
                        const tool = tools.find((t) => t.id === tab.toolId);
                        if (!tool) return null;
                        return (
                          <RequestBuilderPanel
                            key={tool.id}
                            tool={tool}
                            onDeleted={() => handleToolDeleted(tab.id)}
                            onToggleEnable={(enabled) => handleToggleTool(tool, enabled)}
                            onUpdated={() => load()}
                          />
                        );
                      })()
                    )}
                  </div>
                ))
              )}
            </div>
          </>
        )}
      </main>
    </>
  );
}

/** One collapsible app in the sidebar collection tree: header row + endpoint rows grouped by category. */
function AppTreeSection({
  conn,
  slug,
  tools,
  open,
  onToggleOpen,
  activeToolId,
  onOpenTool,
  onNewRequest,
}: {
  conn: ConnectionInfo;
  slug: string;
  tools: ApiToolInfo[];
  open: boolean;
  onToggleOpen: () => void;
  activeToolId: string | null;
  onOpenTool: (toolId: string) => void;
  onNewRequest: () => void;
}) {
  const categories = new Map<string, ApiToolInfo[]>();
  for (const tool of tools) {
    const list = categories.get(tool.category) ?? [];
    list.push(tool);
    categories.set(tool.category, list);
  }
  const multiCategory = categories.size > 1;

  return (
    <div>
      <div
        className={`tree-row app-tree-row ${open ? "is-active" : ""}`}
        onClick={onToggleOpen}
        role="button"
        tabIndex={0}
      >
        <button
          className="tree-chevron"
          onClick={(e) => {
            e.stopPropagation();
            onToggleOpen();
          }}
          aria-label={open ? "Collapse" : "Expand"}
        >
          <ChevronRightIcon size={14} className={open ? "chev-open" : ""} />
        </button>
        <span
          className={`status-dot ${conn.status === "CONNECTED" ? "is-active" : conn.status === "ERROR" ? "is-error" : ""}`}
        />
        <div className="app-tree-name-wrap">
          <span className="app-tree-name">{conn.name}</span>
          <span className="app-tree-meta mono">
            {slug && `@${slug} · `}
            {plural(tools.length, "endpoint")}
          </span>
        </div>
        <button
          type="button"
          className="btn btn-ghost rb-icon-btn app-tree-add-btn"
          onClick={(e) => {
            e.stopPropagation();
            onNewRequest();
          }}
          title="New request in this app"
          aria-label={`New request in ${conn.name}`}
        >
          <PlusIcon size={12} />
        </button>
      </div>
      {open && (
        <div className="tree-children">
          {tools.length === 0 ? (
            <p className="app-tools-empty">No endpoints to show.</p>
          ) : (
            Array.from(categories.entries()).map(([category, categoryTools]) => (
              <div key={category}>
                {multiCategory && <div className="tool-tree-category mono">{category}</div>}
                {categoryTools.map((tool) => (
                  <button
                    type="button"
                    key={tool.id}
                    className={`tree-row tool-tree-row ${activeToolId === tool.id ? "is-active" : ""}`}
                    onClick={() => onOpenTool(tool.id)}
                    title={tool.description || tool.displayName}
                  >
                    <span
                      className={`method-badge mono tool-tree-method ${tool.method === "GET" ? "" : "method-write"} ${tool.method === "DELETE" ? "method-danger" : ""}`}
                    >
                      {tool.method}
                    </span>
                    <span className="tool-tree-name">{tool.displayName || tool.name}</span>
                    <span
                      className={`tool-tree-dot ${tool.enabled ? "is-enabled" : ""}`}
                      title={tool.enabled ? "Enabled" : tool.pending ? "Pending approval" : "Disabled"}
                    />
                  </button>
                ))}
              </div>
            ))
          )}
        </div>
      )}
    </div>
  );
}

/** One expandable app in the group-members editor: header + checkbox tree. */
function AppSection({
  conn,
  slug,
  tools,
  enabledCount,
  totalCount,
  open,
  onToggleOpen,
  appChecked,
  editTools,
  onToggleApp,
  onToggleTool,
}: {
  conn: ConnectionInfo;
  slug: string;
  tools: ApiToolInfo[];
  enabledCount: number;
  totalCount: number;
  open: boolean;
  onToggleOpen: () => void;
  appChecked: boolean;
  editTools: Set<string>;
  onToggleApp: (id: string, checked: boolean) => void;
  onToggleTool: (id: string, checked: boolean) => void;
}) {
  const categories = new Map<string, ApiToolInfo[]>();
  for (const tool of tools) {
    const list = categories.get(tool.category) ?? [];
    list.push(tool);
    categories.set(tool.category, list);
  }

  return (
    <section className="app-section">
      <div className="app-header">
        <input
          type="checkbox"
          className="member-check"
          checked={appChecked}
          onChange={(e) => onToggleApp(conn.id, e.target.checked)}
          title="Include the whole app — all of its endpoints — in the group"
          aria-label={`Include whole app ${conn.name} in the group`}
        />
        <button className="app-header-main" onClick={onToggleOpen} aria-expanded={open}>
          <ChevronRightIcon size={14} className={`app-chevron ${open ? "chev-open" : ""}`} />
          <div className="app-heading">
            <div className="app-name-row">
              <span className="app-name">{conn.name}</span>
              {slug && <span className="app-slug mono">@{slug}</span>}
              {conn.specFormat && (
                <span className="plugin-category mono optional">{conn.specFormat}</span>
              )}
              <span className={`status-pill ${statusClass(conn.status)}`}>
                {statusIcon(conn.status)}
                {statusLabel(conn.status)}
              </span>
            </div>
            <div className="app-meta mono">
              {conn.baseUrl || conn.specSourceUrl || "No base URL"} — {enabledCount} enabled /{" "}
              {totalCount} {totalCount === 1 ? "endpoint" : "endpoints"}
            </div>
          </div>
        </button>
      </div>

      {open && (
        <div className="app-tools">
          {tools.length === 0 ? (
            <p className="app-tools-empty">No endpoints to show.</p>
          ) : (
            Array.from(categories.entries()).map(([category, categoryTools]) => (
              <div key={category} className="tool-category">
                <div className="tool-category-name mono">{category}</div>
                {categoryTools.map((tool) => (
                  <div key={tool.id} className="tool-row-wrap">
                    <div className="tool-row">
                      <input
                        type="checkbox"
                        className="member-check"
                        checked={appChecked || editTools.has(tool.id)}
                        disabled={appChecked}
                        onChange={(e) => onToggleTool(tool.id, e.target.checked)}
                        title={
                          appChecked
                            ? "Included via whole-app membership"
                            : "Include this endpoint in the group"
                        }
                        aria-label={`Include endpoint ${tool.name} in the group`}
                      />
                      <span
                        className={`method-badge mono ${tool.method === "GET" ? "" : "method-write"} ${tool.method === "DELETE" ? "method-danger" : ""}`}
                      >
                        {tool.method}
                      </span>
                      <div className="tool-row-info">
                        <span className="tool-row-name mono">{tool.name}</span>
                        <span className="tool-row-desc">
                          {tool.displayName}
                          {tool.description && tool.description !== tool.displayName
                            ? ` — ${tool.description}`
                            : ""}
                        </span>
                      </div>
                      {tool.origin === "MANUAL" && <span className="tool-pending-badge mono">manual</span>}
                      {tool.pending && <span className="tool-pending-badge mono">pending</span>}
                    </div>
                  </div>
                ))}
              </div>
            ))
          )}
        </div>
      )}
    </section>
  );
}

function NewGroupForm({
  onCancel,
  onCreate,
}: {
  onCancel: () => void;
  onCreate: (name: string, description?: string) => void;
}) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");

  return (
    <form
      className="group-form"
      onSubmit={(e) => {
        e.preventDefault();
        if (name.trim()) onCreate(name.trim(), description.trim() || undefined);
      }}
    >
      <input
        className="form-input"
        value={name}
        onChange={(e) => setName(e.target.value)}
        placeholder="Name — becomes the @group handle"
        required
        autoFocus
      />
      <input
        className="form-input"
        value={description}
        onChange={(e) => setDescription(e.target.value)}
        placeholder="Description (optional)"
      />
      <div className="form-actions">
        <button type="button" className="btn btn-ghost" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={!name.trim()}>
          Create
        </button>
      </div>
    </form>
  );
}

function statusIcon(status: ConnectionInfo["status"]) {
  switch (status) {
    case "CONNECTED":
      return <CheckCircleIcon size={12} />;
    case "ERROR":
      return <AlertIcon size={12} />;
    default:
      return null;
  }
}

function statusLabel(status: ConnectionInfo["status"]) {
  switch (status) {
    case "PENDING":
      return "Pending";
    case "CONNECTED":
      return "Connected";
    case "ERROR":
      return "Error";
    case "DISABLED":
      return "Disabled";
    default:
      return status;
  }
}

function statusClass(status: ConnectionInfo["status"]) {
  switch (status) {
    case "CONNECTED":
      return "status-active";
    case "ERROR":
      return "status-error";
    case "DISABLED":
      return "status-disabled";
    default:
      return "status-default";
  }
}
