import React, { useEffect, useMemo, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import {
  ApiToolInfo,
  PluginInfo,
  SearchResponse,
  SearchResult,
  listPlugins,
  listTools,
  search,
} from "../api";
import {
  AlertIcon,
  AtSignIcon,
  BookIcon,
  ChevronRightIcon,
  DownloadIcon,
  ExternalLinkIcon,
  FileIcon,
  GlobeIcon,
  HashIcon,
  PlusIcon,
  SearchIcon,
  TrashIcon,
  XIcon,
} from "../icons";
import { SummarySourceDialog } from "./SummarySourceDialog";
import { ToolConfirmPanel } from "./ToolConfirmPanel";
import { ToolFormPanel } from "./ToolFormPanel";
import { ToolResultPanel } from "./ToolResultPanel";

const STORE_KEY = "mcp.search.sessions.v1";
const LEGACY_STORE_KEY = "mcp.chat.conversations.v2";
const LEGACY_HISTORY_KEY = "mcp.chat.history.v1";
const MAX_STORED_SESSIONS = 25;

type SearchStatus = "idle" | "loading" | "success" | "error";

interface SearchSession {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  query: string;
  web: boolean;
  status: SearchStatus;
  response?: SearchResponse;
  error?: string;
}

interface SearchStore {
  activeId: string;
  sessions: SearchSession[];
}

interface FileGroup {
  sourceName: string;
  sourcePath: string;
  sourceUrl: string;
  sourceKind: string;
  chunks: SearchResult[];
  bestScore: number;
}

interface LegacyTurn {
  role?: "user" | "assistant";
  createdAt?: number;
  text?: string;
  payload?: {
    kind?: string;
    data?: SearchResponse;
    message?: string;
  };
}

interface LegacyConversation {
  id?: string;
  title?: string;
  createdAt?: number;
  updatedAt?: number;
  turns?: LegacyTurn[];
}

function freshSession(): SearchSession {
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

function titleFromQuery(query: string): string {
  return query.length > 52 ? `${query.slice(0, 52).trimEnd()}…` : query;
}

function sessionFromLegacy(conversation: LegacyConversation): SearchSession | null {
  const turns = Array.isArray(conversation.turns) ? conversation.turns : [];
  let queryTurnIndex = -1;
  for (let i = turns.length - 1; i >= 0; i -= 1) {
    if (turns[i].role === "user" && turns[i].text?.trim()) {
      queryTurnIndex = i;
      break;
    }
  }
  if (queryTurnIndex < 0) return null;

  const queryTurn = turns[queryTurnIndex];
  const resultTurn = turns
    .slice(queryTurnIndex + 1)
    .find((turn) => turn.role === "assistant" && turn.payload);
  const query = queryTurn.text?.trim() ?? "";
  const createdAt = conversation.createdAt ?? queryTurn.createdAt ?? Date.now();
  const updatedAt = conversation.updatedAt ?? resultTurn?.createdAt ?? createdAt;
  const response = resultTurn?.payload?.kind === "search" ? resultTurn.payload.data : undefined;
  const error = resultTurn?.payload?.kind === "error" ? resultTurn.payload.message : undefined;

  return {
    id: conversation.id ?? crypto.randomUUID(),
    title: query ? titleFromQuery(query) : conversation.title ?? "Saved search",
    createdAt,
    updatedAt,
    query,
    web: response?.web ?? false,
    status: response ? "success" : error ? "error" : "idle",
    response,
    error,
  };
}

function loadStore(): SearchStore {
  try {
    const raw = localStorage.getItem(STORE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<SearchStore>;
      if (Array.isArray(parsed.sessions) && parsed.sessions.length > 0) {
        const sessions = parsed.sessions.map((session) => ({
          ...session,
          status: session.status === "loading" ? "error" as const : session.status,
          error: session.status === "loading" ? "Search was interrupted. Run it again." : session.error,
        }));
        const activeId = sessions.some((session) => session.id === parsed.activeId)
          ? parsed.activeId as string
          : sessions[0].id;
        return { activeId, sessions };
      }
    }

    const legacyRaw = localStorage.getItem(LEGACY_STORE_KEY);
    if (legacyRaw) {
      const parsed = JSON.parse(legacyRaw) as {
        activeId?: string;
        conversations?: LegacyConversation[];
      };
      const migrated = (parsed.conversations ?? [])
        .map(sessionFromLegacy)
        .filter((session): session is SearchSession => session !== null);
      if (migrated.length > 0) {
        const activeId = migrated.some((session) => session.id === parsed.activeId)
          ? parsed.activeId as string
          : migrated[0].id;
        return { activeId, sessions: migrated };
      }
    }

    const legacyHistory = localStorage.getItem(LEGACY_HISTORY_KEY);
    if (legacyHistory) {
      const migrated = sessionFromLegacy({ turns: JSON.parse(legacyHistory) as LegacyTurn[] });
      if (migrated) return { activeId: migrated.id, sessions: [migrated] };
    }
  } catch {
    // Unreadable browser storage should never prevent searching.
  }
  const session = freshSession();
  return { activeId: session.id, sessions: [session] };
}

function saveStore(store: SearchStore) {
  try {
    const sessions = store.sessions
      .filter((session) => session.query || session.id === store.activeId)
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .slice(0, MAX_STORED_SESSIONS);
    localStorage.setItem(STORE_KEY, JSON.stringify({ activeId: store.activeId, sessions }));
  } catch {
    // History is a convenience; search remains available if storage is full or disabled.
  }
}

function formatSessionTime(timestamp: number): string {
  const date = new Date(timestamp);
  if (date.toDateString() === new Date().toDateString()) {
    return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }
  return date.toLocaleDateString([], { month: "short", day: "numeric" });
}

function isToolInvocation(query: string): boolean {
  const trimmed = query.trimStart();
  return trimmed.startsWith("#") || trimmed.startsWith("@");
}

function sessionScope(session: SearchSession): string {
  if (isToolInvocation(session.query)) return "App";
  return session.web ? "Knowledge + web" : "Knowledge";
}

function activeToken(input: string): { sigil: "@" | "#"; fragment: string; start: number } | null {
  const trimmedStart = input.trimStart();
  if (!trimmedStart.startsWith("@") && !trimmedStart.startsWith("#")) return null;
  const match = /(^|\s)([@#])([\w-]*)$/.exec(input);
  if (!match) return null;
  return {
    sigil: match[2] as "@" | "#",
    fragment: match[3].toLowerCase(),
    start: match.index + match[1].length,
  };
}

function groupLocalResults(results: SearchResult[]): FileGroup[] {
  const groups = new Map<string, FileGroup>();
  for (const result of results) {
    const key = `${result.sourceKind}:${result.sourceName}:${result.sourcePath}:${result.sourceUrl}`;
    const existing = groups.get(key);
    if (existing) {
      existing.chunks.push(result);
      existing.bestScore = Math.max(existing.bestScore, result.score);
    } else {
      groups.set(key, {
        sourceName: result.sourceName,
        sourcePath: result.sourcePath,
        sourceUrl: result.sourceUrl,
        sourceKind: result.sourceKind,
        chunks: [result],
        bestScore: result.score,
      });
    }
  }
  return [...groups.values()]
    .sort((a, b) => b.bestScore - a.bestScore)
    .map((group) => ({ ...group, chunks: [...group.chunks].sort((a, b) => b.score - a.score) }));
}

export function SearchPage() {
  const [params, setParams] = useSearchParams();
  const [store, setStore] = useState<SearchStore>(() => loadStore());
  const [input, setInput] = useState("");
  const [webOn, setWebOn] = useState(false);
  const [plugins, setPlugins] = useState<PluginInfo[]>([]);
  const [pluginsLoaded, setPluginsLoaded] = useState(false);
  const [allTools, setAllTools] = useState<ApiToolInfo[]>([]);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [guideOpen, setGuideOpen] = useState(false);
  const [summaryDialogOpen, setSummaryDialogOpen] = useState(false);
  const [initialWebWarning, setInitialWebWarning] = useState(false);
  const [acIndex, setAcIndex] = useState(0);
  const [acDismissed, setAcDismissed] = useState(false);
  const [exportNotice, setExportNotice] = useState<{
    filename: string;
    sourceCount: number;
    chunkCount: number;
  } | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const lastConsumedQuery = useRef<string | null>(null);

  const active = store.sessions.find((session) => session.id === store.activeId) ?? store.sessions[0];
  const sortedSessions = useMemo(
    () => [...store.sessions].sort((a, b) => b.updatedAt - a.updatedAt),
    [store.sessions],
  );
  const searxngReady = useMemo(
    () => plugins.find((plugin) => plugin.id === "searxng")?.status === "ACTIVE",
    [plugins],
  );

  useEffect(() => {
    listTools().then(setAllTools).catch(() => {});
    listPlugins()
      .then(setPlugins)
      .catch(() => {})
      .finally(() => setPluginsLoaded(true));
  }, []);

  useEffect(() => {
    saveStore(store);
  }, [store]);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  useEffect(() => {
    setInput(active.query);
  }, [active.id]);

  const patchSession = (id: string, patch: Partial<SearchSession>) => {
    setStore((current) => ({
      ...current,
      sessions: current.sessions.map((session) =>
        session.id === id ? { ...session, ...patch } : session),
    }));
  };

  const newSearch = () => {
    setStore((current) => {
      const selected = current.sessions.find((session) => session.id === current.activeId);
      if (selected && !selected.query) return current;
      const session = freshSession();
      return { activeId: session.id, sessions: [session, ...current.sessions] };
    });
    setInput("");
    setHistoryOpen(false);
    inputRef.current?.focus();
  };

  const selectSession = (id: string) => {
    const session = store.sessions.find((candidate) => candidate.id === id);
    if (!session) return;
    setStore((current) => ({ ...current, activeId: id }));
    setInput(session.query);
    setWebOn(session.web && searxngReady);
    setHistoryOpen(false);
    inputRef.current?.focus();
  };

  const deleteSession = (id: string) => {
    setStore((current) => {
      const remaining = current.sessions.filter((session) => session.id !== id);
      if (remaining.length === 0) {
        const session = freshSession();
        return { activeId: session.id, sessions: [session] };
      }
      return {
        activeId: current.activeId === id ? remaining[0].id : current.activeId,
        sessions: remaining,
      };
    });
    setHistoryOpen(false);
  };

  const runSearch = async (sessionId: string, query: string, includeWeb: boolean) => {
    const startedAt = Date.now();
    patchSession(sessionId, {
      title: titleFromQuery(query),
      query,
      web: includeWeb,
      status: "loading",
      response: undefined,
      error: undefined,
      updatedAt: startedAt,
    });
    try {
      const response = await search(query, 20, includeWeb);
      patchSession(sessionId, {
        status: "success",
        response,
        updatedAt: Date.now(),
      });
    } catch (error) {
      patchSession(sessionId, {
        status: "error",
        error: error instanceof Error ? error.message : "Search failed",
        updatedAt: Date.now(),
      });
    }
  };

  const submit = (raw: string, includeWeb = webOn, reuseActive = false) => {
    const query = raw.trim();
    if (!query) return;
    lastConsumedQuery.current = query;
    setInput(query);

    if (reuseActive || !active.query) {
      void runSearch(active.id, query, includeWeb);
      return;
    }

    const session = freshSession();
    session.title = titleFromQuery(query);
    session.query = query;
    session.web = includeWeb;
    session.status = "loading";
    setStore((current) => ({
      activeId: session.id,
      sessions: [session, ...current.sessions],
    }));
    void runSearch(session.id, query, includeWeb);
  };

  useEffect(() => {
    if (!pluginsLoaded) return;
    const query = params.get("q")?.trim() ?? "";
    if (!query || query === lastConsumedQuery.current) return;
    const requestedWeb = params.get("web") === "1";
    const includeWeb = requestedWeb && searxngReady;
    setWebOn(includeWeb);
    setInitialWebWarning(requestedWeb && !includeWeb);
    setParams({}, { replace: true });
    submit(query, includeWeb);
    // submit intentionally reads the currently selected session.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pluginsLoaded, params]);

  const token = useMemo(() => activeToken(input), [input]);
  const acItems = useMemo(() => {
    if (!token || acDismissed) return [];
    if (token.sigil === "@") {
      const apps = new Map<string, number>();
      for (const tool of allTools) {
        if (tool.enabled && tool.appSlug.startsWith(token.fragment)) {
          apps.set(tool.appSlug, (apps.get(tool.appSlug) ?? 0) + 1);
        }
      }
      return [...apps.entries()].slice(0, 8).map(([app, count]) => ({
        key: `@${app}`,
        sigil: "@" as const,
        value: app,
        label: app,
        detail: `${count} ${count === 1 ? "tool" : "tools"}`,
        method: null as string | null,
      }));
    }
    const appScope = /^@([\w-]+)\s/.exec(input.trimStart())?.[1]?.toLowerCase();
    return allTools
      .filter((tool) => tool.enabled)
      .filter((tool) => !appScope || tool.appSlug === appScope)
      .filter((tool) =>
        !token.fragment ||
        tool.name.includes(token.fragment) ||
        tool.requestSlug.includes(token.fragment))
      .slice(0, 8)
      .map((tool) => ({
        key: `#${tool.name}`,
        sigil: "#" as const,
        value: appScope ? tool.requestSlug : tool.name,
        label: appScope ? tool.requestSlug : tool.name,
        detail: tool.displayName,
        method: tool.method,
      }));
  }, [token, acDismissed, allTools, input]);

  useEffect(() => {
    setAcIndex(0);
  }, [acItems.length, token?.fragment]);

  const acceptSuggestion = (item: { sigil: "@" | "#"; value: string }) => {
    if (!token) return;
    setInput(`${input.slice(0, token.start)}${item.sigil}${item.value} `);
    setAcDismissed(false);
    inputRef.current?.focus();
  };

  const handleInputKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (acItems.length === 0) return;
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setAcIndex((index) => (index + 1) % acItems.length);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setAcIndex((index) => (index - 1 + acItems.length) % acItems.length);
    } else if (
      event.key === "Tab" ||
      (event.key === "Enter" && token && token.fragment !== acItems[acIndex]?.value)
    ) {
      event.preventDefault();
      acceptSuggestion(acItems[acIndex]);
    } else if (event.key === "Escape") {
      setAcDismissed(true);
    }
  };

  const chooseExample = (value: string) => {
    setInput(value);
    setGuideOpen(false);
    inputRef.current?.focus();
  };

  return (
    <div className="search-workspace">
      <aside
        id="search-history"
        className={`search-history-rail ${historyOpen ? "is-mobile-open" : ""}`}
        aria-label="Search sessions"
      >
        <div className="search-history-header">
          <span className="search-history-title">Search sessions</span>
          <button type="button" className="btn btn-sm" onClick={newSearch}>
            <PlusIcon size={13} />
            New
          </button>
        </div>
        <div className="search-history-list">
          {sortedSessions.map((session) => (
            <div
              key={session.id}
              className={`search-history-item ${session.id === active.id ? "active" : ""}`}
            >
              <button
                type="button"
                className="search-history-item-main"
                onClick={() => selectSession(session.id)}
                aria-current={session.id === active.id ? "page" : undefined}
              >
                <span className="search-history-item-title">{session.title}</span>
                <span className="search-history-item-meta mono">
                  {session.query ? sessionScope(session) : "Ready"}
                  {" · "}
                  {formatSessionTime(session.updatedAt)}
                </span>
              </button>
              <button
                type="button"
                className="search-history-item-delete"
                onClick={() => deleteSession(session.id)}
                title="Delete saved search"
                aria-label={`Delete saved search: ${session.title}`}
              >
                <TrashIcon size={13} />
              </button>
            </div>
          ))}
        </div>
        <p className="search-history-note">
          Results are saved in this browser. Select a session to reuse its query.
        </p>
      </aside>

      <section className="search-main" aria-labelledby="search-workspace-title">
        <header className="search-workspace-header">
          <button
            type="button"
            className="btn btn-sm search-history-mobile-toggle"
            aria-controls="search-history"
            aria-expanded={historyOpen}
            onClick={() => setHistoryOpen((open) => !open)}
          >
            <BookIcon size={14} />
            Sessions
          </button>
          <div className="search-workspace-heading">
            <h1 id="search-workspace-title">{active.query ? active.title : "Knowledge search"}</h1>
            <span className="mono">
              {active.query ? `${sessionScope(active)} · saved ${formatSessionTime(active.updatedAt)}` : "Search files, connected sources, and apps"}
            </span>
          </div>
          <div className="search-workspace-actions">
            <button
              type="button"
              className="btn btn-ghost"
              aria-expanded={guideOpen}
              aria-controls="search-guide"
              onClick={() => setGuideOpen((open) => !open)}
            >
              <BookIcon size={14} />
              Search guide
            </button>
            <button
              type="button"
              className="btn btn-ghost summary-open-button"
              onClick={() => setSummaryDialogOpen(true)}
            >
              <DownloadIcon size={14} />
              Export evidence
            </button>
          </div>
        </header>

        {guideOpen && <SearchGuide id="search-guide" onChoose={chooseExample} />}

        {exportNotice && (
          <div className="summary-export-notice" role="status">
            <DownloadIcon size={14} />
            <span>
              <strong>{exportNotice.filename}</strong>
              <span className="mono">
                {exportNotice.sourceCount} sources · {exportNotice.chunkCount} chunks
              </span>
            </span>
            <button
              type="button"
              onClick={() => setExportNotice(null)}
              aria-label="Dismiss export notice"
            >
              <XIcon size={13} />
            </button>
          </div>
        )}

        <div className="search-query-region">
          <SearchForm
            input={input}
            setInput={(value) => {
              setInput(value);
              setAcDismissed(false);
            }}
            inputRef={inputRef}
            webOn={webOn}
            setWebOn={setWebOn}
            searxngReady={searxngReady}
            isLoading={active.status === "loading"}
            isToolQuery={isToolInvocation(input)}
            acItems={acItems}
            acIndex={acIndex}
            setAcIndex={setAcIndex}
            acceptSuggestion={acceptSuggestion}
            handleInputKeyDown={handleInputKeyDown}
            onSubmit={() => submit(input)}
          />
          <p className="search-query-hint">
            Plain text searches your knowledge base. Start with <code>@app</code> or <code>#tool</code> to run an app action.
          </p>
        </div>

        <div className="search-results-region" aria-live="polite">
          {initialWebWarning && (
            <div className="warning-banner" role="status">
              <AlertIcon size={16} />
              <span>Web search is unavailable because SearXNG is not active.</span>
              <Link to="/plugins" className="btn btn-sm">Open Plugins</Link>
            </div>
          )}

          {!active.query && (
            <SearchEmptyState onChoose={chooseExample} />
          )}

          {active.query && (
            <SearchSnapshot
              session={active}
              setInput={setInput}
              focusInput={() => inputRef.current?.focus()}
              onRerun={() => submit(active.query, active.web && searxngReady, true)}
            />
          )}
        </div>
      </section>

      <SummarySourceDialog
        open={summaryDialogOpen}
        onClose={() => setSummaryDialogOpen(false)}
        onExported={setExportNotice}
      />
    </div>
  );
}

// Keep the old export name for extensions importing this module directly.
export const ChatPage = SearchPage;

function SearchForm({
  input,
  setInput,
  inputRef,
  webOn,
  setWebOn,
  searxngReady,
  isLoading,
  isToolQuery,
  acItems,
  acIndex,
  setAcIndex,
  acceptSuggestion,
  handleInputKeyDown,
  onSubmit,
}: {
  input: string;
  setInput: (value: string) => void;
  inputRef: React.RefObject<HTMLInputElement>;
  webOn: boolean;
  setWebOn: (value: boolean) => void;
  searxngReady: boolean;
  isLoading: boolean;
  isToolQuery: boolean;
  acItems: {
    key: string;
    sigil: "@" | "#";
    value: string;
    label: string;
    detail: string;
    method: string | null;
  }[];
  acIndex: number;
  setAcIndex: (value: number) => void;
  acceptSuggestion: (item: { sigil: "@" | "#"; value: string }) => void;
  handleInputKeyDown: (event: React.KeyboardEvent<HTMLInputElement>) => void;
  onSubmit: () => void;
}) {
  return (
    <form
      className="search-box search-workspace-form"
      role="search"
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit();
      }}
    >
      <SearchIcon size={18} className="search-box-icon" />
      <input
        ref={inputRef}
        type="search"
        className={`search-box-input ${isToolQuery ? "is-tool" : ""}`}
        value={input}
        onChange={(event) => setInput(event.target.value)}
        onKeyDown={handleInputKeyDown}
        placeholder="Search knowledge, or use @app #tool…"
        aria-label="Knowledge or app search"
        aria-autocomplete="list"
        aria-controls={acItems.length > 0 ? "search-tool-suggestions" : undefined}
        aria-expanded={acItems.length > 0}
        autoComplete="off"
      />
      {acItems.length > 0 && (
        <ul id="search-tool-suggestions" className="tool-autocomplete" role="listbox">
          {acItems.map((item, index) => (
            <li key={item.key} role="option" aria-selected={index === acIndex}>
              <button
                type="button"
                className={`tool-ac-item ${index === acIndex ? "tool-ac-active" : ""}`}
                onMouseDown={(event) => {
                  event.preventDefault();
                  acceptSuggestion(item);
                }}
                onMouseEnter={() => setAcIndex(index)}
              >
                {item.sigil === "@" ? (
                  <AtSignIcon size={13} className="tool-ac-icon" />
                ) : (
                  <HashIcon size={13} className="tool-ac-icon" />
                )}
                <span className="tool-ac-name mono">{item.label}</span>
                {item.method && (
                  <span className={`method-badge mono ${item.method === "GET" ? "" : "method-write"}`}>
                    {item.method}
                  </span>
                )}
                <span className="tool-ac-detail">{item.detail}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      <label
        className="web-toggle"
        title={searxngReady ? "Include live web sources" : "Activate SearXNG on the Plugins page to use web search"}
      >
        <input
          type="checkbox"
          checked={webOn}
          disabled={!searxngReady}
          onChange={(event) => setWebOn(event.target.checked)}
        />
        <GlobeIcon size={15} />
        <span className="web-toggle-label">Web</span>
      </label>
      <button
        type="submit"
        className="btn btn-primary search-box-submit"
        disabled={!input.trim() || isLoading}
      >
        <SearchIcon size={15} />
        {isLoading ? "Searching" : "Search"}
      </button>
    </form>
  );
}

function SearchGuide({ id, onChoose }: { id: string; onChoose: (value: string) => void }) {
  return (
    <section id={id} className="search-guide-panel" aria-label="How to search">
      <div>
        <span className="search-guide-kicker mono">KNOWLEDGE</span>
        <strong>Ask in plain language</strong>
        <p>Find cited excerpts across files and synced connections.</p>
        <button type="button" onClick={() => onChoose("What are the release approval steps?")}>
          What are the release approval steps?
        </button>
      </div>
      <div>
        <span className="search-guide-kicker mono">APP + TOOL</span>
        <strong>Choose an app, then an action</strong>
        <p>Type <code>@</code> for an app and <code>#</code> for one of its tools.</p>
        <button type="button" onClick={() => onChoose("@jira #")}>
          @jira #
        </button>
      </div>
      <div>
        <span className="search-guide-kicker mono">DIRECT TOOL</span>
        <strong>Run a known tool</strong>
        <p>Start with <code>#</code> when you already know the imported tool name.</p>
        <button type="button" onClick={() => onChoose("#")}>
          Browse available tools
        </button>
      </div>
      <Link to="/guide" className="search-guide-full-link">
        Open the full workspace guide <ChevronRightIcon size={13} />
      </Link>
    </section>
  );
}

function SearchEmptyState({ onChoose }: { onChoose: (value: string) => void }) {
  return (
    <section className="search-home">
      <div className="search-home-copy">
        <span className="eyebrow mono">RETRIEVAL WORKSPACE</span>
        <h2>Find evidence. Run tools.</h2>
        <p>
          Search returns source-backed results from your knowledge base. App commands are
          deterministic and stay separate from normal queries.
        </p>
      </div>
      <div className="search-home-options">
        <button type="button" onClick={() => onChoose("Summarize the onboarding policy")}>
          <span><SearchIcon size={16} /> Search knowledge</span>
          <small>Use a natural-language question</small>
          <code>Summarize the onboarding policy</code>
        </button>
        <button type="button" onClick={() => onChoose("@jira #")}>
          <span><AtSignIcon size={16} /> Use an app</span>
          <small>Scope actions to one connected app</small>
          <code>@jira #</code>
        </button>
        <button type="button" onClick={() => onChoose("#")}>
          <span><HashIcon size={16} /> Run a tool</span>
          <small>Choose an imported API operation</small>
          <code>#tool_name</code>
        </button>
      </div>
      <p className="search-home-footnote">
        Each completed query is saved as a reusable search session in the rail.
      </p>
    </section>
  );
}

function SearchSnapshot({
  session,
  setInput,
  focusInput,
  onRerun,
}: {
  session: SearchSession;
  setInput: (value: string) => void;
  focusInput: () => void;
  onRerun: () => void;
}) {
  return (
    <article className="search-snapshot">
      <header className="search-snapshot-header">
        <div>
          <span className="search-snapshot-label mono">RESULTS FOR</span>
          <h2>{session.query}</h2>
        </div>
        <button
          type="button"
          className="btn btn-ghost"
          onClick={onRerun}
          disabled={session.status === "loading"}
        >
          <SearchIcon size={14} />
          Run again
        </button>
      </header>

      {session.status === "loading" && (
        <div className="search-loading" role="status">
          <span className="search-loading-indicator" aria-hidden />
          <div>
            <strong>Searching sources</strong>
            <span>Retrieving and ranking matching evidence…</span>
          </div>
        </div>
      )}

      {session.status === "error" && (
        <div className="search-request-error" role="alert">
          <AlertIcon size={16} />
          <div>
            <strong>Search could not be completed</strong>
            <span>{session.error}</span>
          </div>
          <button type="button" className="btn btn-sm" onClick={onRerun}>Try again</button>
        </div>
      )}

      {session.status === "success" && session.response && (
        <SearchResponseView
          response={session.response}
          setInput={setInput}
          focusInput={focusInput}
        />
      )}
    </article>
  );
}

function SearchResponseView({
  response,
  setInput,
  focusInput,
}: {
  response: SearchResponse;
  setInput: (value: string) => void;
  focusInput: () => void;
}) {
  const localGroups = useMemo(
    () => response.mode === "rag"
      ? groupLocalResults(response.results.filter((result) => result.sourceKind !== "web"))
      : [],
    [response],
  );
  const webResults = useMemo(
    () => response.mode === "rag"
      ? response.results.filter((result) => result.sourceKind === "web")
      : [],
    [response],
  );

  if (response.mode === "notReady") {
    return (
      <div className="setup-banner search-setup-banner" role="status">
        <div className="setup-banner-content">
          <span className="setup-banner-title">Search needs setup</span>
          <span className="setup-banner-text">{response.message}</span>
          {response.pluginStatus && response.pluginStatus.length > 0 && (
            <ul className="setup-plugin-list">
              {response.pluginStatus.map((plugin) => (
                <li key={plugin.id} className="setup-plugin-item">
                  <span className="setup-plugin-name">{plugin.name}</span>
                  <span className="setup-plugin-status mono">{plugin.status}</span>
                  <span className="setup-plugin-health">{plugin.health}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
        <Link to="/plugins" className="btn btn-primary">Open Plugins</Link>
      </div>
    );
  }

  if (response.mode === "tool") {
    return (
      <div className="tool-result">
        <div className="tool-result-header">
          <HashIcon size={16} className="tool-result-icon" />
          <span className="tool-result-name mono">{response.tool}</span>
        </div>
        <p className="tool-result-message">{response.message}</p>
        {response.suggestions && response.suggestions.length > 0 && (
          <ul className="tool-suggestion-list">
            {response.suggestions.map((suggestion) => (
              <li key={suggestion.id}>
                <button
                  type="button"
                  className="tool-ac-item"
                  onClick={() => {
                    setInput(`#${suggestion.name} `);
                    focusInput();
                  }}
                >
                  <HashIcon size={13} className="tool-ac-icon" />
                  <span className="tool-ac-name mono">{suggestion.name}</span>
                  <span className={`method-badge mono ${suggestion.method === "GET" ? "" : "method-write"}`}>
                    {suggestion.method}
                  </span>
                  <span className="tool-ac-detail">{suggestion.displayName}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    );
  }

  if (response.mode === "tool-form" && response.toolInfo) {
    return (
      <ToolFormPanel
        tool={response.toolInfo}
        prefill={response.prefill}
        missingRequired={response.missingRequired}
        violations={response.violations}
        parseError={response.error}
      />
    );
  }

  if (response.mode === "tool-confirm" && response.toolInfo && response.preview) {
    return (
      <ToolConfirmPanel
        tool={response.toolInfo}
        preview={response.preview}
        args={response.args ?? {}}
        confirmationToken={response.confirmationToken}
        tokenExpiresAt={response.tokenExpiresAt}
        onCancel={() => {}}
      />
    );
  }

  if (response.mode === "tool-result" && response.toolInfo && response.result) {
    return <ToolResultPanel toolName={response.toolInfo.name} result={response.result} />;
  }

  if (response.mode === "rag") {
    if (localGroups.length === 0 && webResults.length === 0) {
      return (
        <div className="search-empty">
          <span className="empty-line">No matching sources</span>
          <span className="empty-hint">Try fewer terms, check your connection sync, or upload files first.</span>
        </div>
      );
    }
    return (
      <div className="search-evidence">
        <div className="search-evidence-summary">
          <span>
            <FileIcon size={14} />
            <strong>{localGroups.length}</strong> knowledge {localGroups.length === 1 ? "source" : "sources"}
          </span>
          <span>
            <GlobeIcon size={14} />
            <strong>{webResults.length}</strong> web {webResults.length === 1 ? "source" : "sources"}
          </span>
          {response.lexicalOnly && <span className="mono">LEXICAL MODE</span>}
        </div>
        {response.lexicalMessage && <p className="search-mode-note">{response.lexicalMessage}</p>}
        {localGroups.length > 0 && (
          <section className="search-evidence-section">
            <h3>Knowledge sources <span className="mono">{localGroups.length}</span></h3>
            <ol className="file-list">
              {localGroups.map((group, index) => (
                <FileGroupCard
                  key={`${group.sourceKind}-${group.sourceName}-${group.sourcePath}-${group.sourceUrl}`}
                  group={group}
                  rank={index + 1}
                  query={response.query}
                  defaultExpanded={index === 0}
                />
              ))}
            </ol>
          </section>
        )}
        {webResults.length > 0 && (
          <section className="search-evidence-section">
            <h3>Web sources <span className="mono">{webResults.length}</span></h3>
            <ol className="web-result-list">
              {webResults.map((result, index) => (
                <WebResultCard key={result.id} result={result} rank={index + 1} query={response.query} />
              ))}
            </ol>
          </section>
        )}
      </div>
    );
  }

  return (
    <div className="search-empty">
      <span className="empty-line">{response.message ?? "No results returned"}</span>
      <span className="empty-hint">Refine the query and run this search again.</span>
    </div>
  );
}

function FileGroupCard({
  group,
  rank,
  query,
  defaultExpanded,
}: {
  group: FileGroup;
  rank: number;
  query: string;
  defaultExpanded: boolean;
}) {
  const [expanded, setExpanded] = useState(defaultExpanded);
  const [showAllMatches, setShowAllMatches] = useState(false);
  const visibleChunks = showAllMatches ? group.chunks : group.chunks.slice(0, 3);
  return (
    <li className="file-group">
      <button
        type="button"
        className="file-group-header"
        onClick={() => setExpanded((open) => !open)}
        aria-expanded={expanded}
      >
        <ChevronRightIcon
          size={14}
          className={`file-group-chevron ${expanded ? "chev-open" : ""}`}
        />
        <span className="file-group-rank mono">{rank}</span>
        <FileIcon size={15} className="file-group-icon" />
        <span className="file-group-name">{group.sourceName}</span>
        <span className="file-group-count mono">
          {group.chunks.length} {group.chunks.length === 1 ? "match" : "matches"}
        </span>
      </button>
      <div className="file-group-path mono">{group.sourcePath}</div>
      {expanded && (
        <div className="file-group-chunks">
          {visibleChunks.map((chunk, index) => (
            <ChunkCard key={chunk.id} chunk={chunk} rank={index + 1} query={query} />
          ))}
          {group.chunks.length > 3 && (
            <button
              type="button"
              className="file-group-more"
              onClick={() => setShowAllMatches((showAll) => !showAll)}
            >
              {showAllMatches
                ? "Show fewer matches"
                : `Show ${group.chunks.length - visibleChunks.length} more matches`}
            </button>
          )}
        </div>
      )}
    </li>
  );
}

function WebResultCard({
  result,
  rank,
  query,
}: {
  result: SearchResult;
  rank: number;
  query: string;
}) {
  const hostname = useMemo(() => {
    try {
      return new URL(result.sourceUrl).hostname;
    } catch {
      return result.sourceUrl;
    }
  }, [result.sourceUrl]);
  return (
    <li className="web-result-card">
      <div className="web-result-rank mono">{rank}</div>
      <div className="web-result-body">
        <div className="web-result-title-row">
          <a
            href={result.sourceUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="web-result-title"
          >
            {result.sourceName || result.sourceUrl}
          </a>
          <a
            href={result.sourceUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="web-result-link"
            title={`Open ${result.sourceUrl}`}
            aria-label={`Open ${result.sourceName || "web source"} in a new tab`}
          >
            <ExternalLinkIcon size={14} />
          </a>
        </div>
        {result.description && (
          <p className="web-result-description">
            <HighlightedText text={result.description} query={query} />
          </p>
        )}
        <div className="web-result-host mono">{hostname}</div>
      </div>
    </li>
  );
}

function ChunkCard({
  chunk,
  rank,
  query,
}: {
  chunk: SearchResult;
  rank: number;
  query: string;
}) {
  return (
    <div className="chunk-card">
      <div className="chunk-card-header">
        <span className="chunk-rank mono">{rank}</span>
        <span className="chunk-score mono">score {chunk.score.toFixed(3)}</span>
        <span className="chunk-pos mono">chunk {chunk.position}</span>
        {chunk.aclTags.length > 0 && (
          <span className="chunk-acl mono">{chunk.aclTags.join(" ")}</span>
        )}
      </div>
      <p className="chunk-content">
        <HighlightedText text={chunk.content} query={query} />
      </p>
    </div>
  );
}

function HighlightedText({ text, query }: { text: string; query: string }) {
  const terms = useMemo(
    () => query
      .toLowerCase()
      .replace(/#/g, "")
      .split(/[^a-z0-9]+/i)
      .filter((term) => term.length >= 2)
      .map(escapeRegex),
    [query],
  );
  if (terms.length === 0) return <>{text}</>;
  const expression = new RegExp(`(${terms.join("|")})`, "gi");
  const lowerTerms = terms.map((term) => term.toLowerCase());
  return (
    <>
      {text.split(expression).map((part, index) =>
        lowerTerms.includes(part.toLowerCase()) ? (
          <mark key={index} className="search-highlight">{part}</mark>
        ) : (
          <React.Fragment key={index}>{part}</React.Fragment>
        ),
      )}
    </>
  );
}

function escapeRegex(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
