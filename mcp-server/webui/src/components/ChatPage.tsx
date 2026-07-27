import React, { useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams, Link } from "react-router-dom";
import {
  search,
  listPlugins,
  listTools,
  ApiToolInfo,
  PluginInfo,
  SearchResult,
  SearchResponse,
} from "../api";
import {
  SearchIcon,
  HashIcon,
  AtSignIcon,
  FileIcon,
  ChevronRightIcon,
  GlobeIcon,
  ExternalLinkIcon,
  AlertIcon,
  SendIcon,
  TrashIcon,
  PlusIcon,
  BookIcon,
} from "../icons";
import { ToolFormPanel } from "./ToolFormPanel";
import { ToolConfirmPanel } from "./ToolConfirmPanel";
import { ToolResultPanel } from "./ToolResultPanel";
import { MarkdownText } from "./MarkdownText";

const STORE_KEY = "mcp.chat.conversations.v2";
const LEGACY_HISTORY_KEY = "mcp.chat.history.v1";
const MAX_STORED_TURNS = 50;
const MAX_STORED_CONVERSATIONS = 25;

/** The @/# token being typed at the end of the input, if any — drives autocomplete. */
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

interface FileGroup {
  sourceName: string;
  sourcePath: string;
  sourceUrl: string;
  sourceKind: string;
  chunks: SearchResult[];
  bestScore: number;
}

type AssistantPayload =
  | { kind: "loading" }
  | { kind: "error"; message: string; sources?: SearchResult[]; retryText?: string }
  | {
      kind: "answer";
      text: string;
      sources: SearchResult[];
      streaming: boolean;
      stopped?: boolean;
    }
  | { kind: "search"; data: SearchResponse };

interface ChatTurn {
  id: string;
  role: "user" | "assistant";
  createdAt: number;
  text?: string;
  payload?: AssistantPayload;
}

interface ChatConversation {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  turns: ChatTurn[];
}

interface ChatStore {
  activeId: string;
  conversations: ChatConversation[];
}

function freshConversation(): ChatConversation {
  return {
    id: crypto.randomUUID(),
    title: "New chat",
    createdAt: Date.now(),
    updatedAt: Date.now(),
    turns: [],
  };
}

/** A reload mid-turn leaves stale in-flight states behind — settle them. */
function settleTurns(turns: ChatTurn[]): ChatTurn[] {
  if (!Array.isArray(turns)) return [];
  return turns.map((t) => {
    if (t.payload?.kind === "loading") {
      return { ...t, payload: { kind: "error", message: "Interrupted before a response arrived." } };
    }
    if (t.payload?.kind === "answer" && t.payload.streaming) {
      return { ...t, payload: { ...t.payload, streaming: false, stopped: true } };
    }
    return t;
  });
}

function titleFromText(text: string): string {
  return text.length > 48 ? text.slice(0, 48).trimEnd() + "…" : text;
}

function loadStore(): ChatStore {
  try {
    const raw = localStorage.getItem(STORE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (parsed && Array.isArray(parsed.conversations) && parsed.conversations.length > 0) {
        const conversations: ChatConversation[] = parsed.conversations.map((raw: unknown) => {
          const { id, title, createdAt, updatedAt, turns } = raw as ChatConversation;
          return { id, title, createdAt, updatedAt, turns: settleTurns(turns) };
        });
        const activeId = conversations.some((c) => c.id === parsed.activeId)
          ? parsed.activeId
          : conversations[0].id;
        return { activeId, conversations };
      }
    }
    // One-time migration from the single-thread v1 keys.
    const legacyRaw = localStorage.getItem(LEGACY_HISTORY_KEY);
    const legacyTurns = settleTurns(legacyRaw ? JSON.parse(legacyRaw) : []);
    localStorage.removeItem(LEGACY_HISTORY_KEY);
    localStorage.removeItem("mcp.chat.conversation.v1");
    const migrated = freshConversation();
    migrated.turns = legacyTurns;
    const firstUser = legacyTurns.find((t) => t.role === "user" && t.text);
    if (firstUser?.text) migrated.title = titleFromText(firstUser.text);
    if (legacyTurns.length > 0) {
      migrated.createdAt = legacyTurns[0].createdAt;
      migrated.updatedAt = legacyTurns[legacyTurns.length - 1].createdAt;
    }
    return { activeId: migrated.id, conversations: [migrated] };
  } catch {
    // corrupted storage — start fresh
  }
  const conv = freshConversation();
  return { activeId: conv.id, conversations: [conv] };
}

function saveStore(store: ChatStore) {
  try {
    const conversations = store.conversations
      .filter((c) => c.turns.length > 0 || c.id === store.activeId)
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .slice(0, MAX_STORED_CONVERSATIONS)
      .map((c) => ({ ...c, turns: c.turns.slice(-MAX_STORED_TURNS) }));
    localStorage.setItem(STORE_KEY, JSON.stringify({ activeId: store.activeId, conversations }));
  } catch {
    // storage full/unavailable — history just won't persist this session
  }
}

/** Today → clock time, older → short date. */
function formatConversationTime(ts: number): string {
  const d = new Date(ts);
  const sameDay = d.toDateString() === new Date().toDateString();
  if (sameDay) return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  return d.toLocaleDateString([], { month: "short", day: "numeric" });
}

function groupLocalResults(results: SearchResult[]): FileGroup[] {
  const map = new Map<string, FileGroup>();
  for (const r of results) {
    const key = `${r.sourceKind}:${r.sourceName}:${r.sourcePath}:${r.sourceUrl}`;
    let g = map.get(key);
    if (!g) {
      g = {
        sourceName: r.sourceName,
        sourcePath: r.sourcePath,
        sourceUrl: r.sourceUrl,
        sourceKind: r.sourceKind,
        chunks: [],
        bestScore: r.score,
      };
      map.set(key, g);
    }
    g.chunks.push(r);
    if (r.score > g.bestScore) g.bestScore = r.score;
  }
  const groups = Array.from(map.values());
  groups.sort((a, b) => b.bestScore - a.bestScore);
  for (const g of groups) g.chunks.sort((a, b) => b.score - a.score);
  return groups;
}

/** The #/@ grammar invokes tools; every other message retrieves knowledge-base evidence. */
function isToolInvocation(text: string): boolean {
  const t = text.trimStart();
  return t.startsWith("#") || t.startsWith("@");
}

export function ChatPage() {
  const [params, setParams] = useSearchParams();
  const [store, setStore] = useState<ChatStore>(() => loadStore());
  const [input, setInput] = useState("");
  const [webOn, setWebOn] = useState(false);
  const [plugins, setPlugins] = useState<PluginInfo[]>([]);
  const [pluginsLoaded, setPluginsLoaded] = useState(false);
  const [initialWebWarning, setInitialWebWarning] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const threadEndRef = useRef<HTMLDivElement>(null);
  const lastConsumedQuery = useRef<string | null>(null);

  // Tool autocomplete: the full tool list is small — fetch once, filter as you type.
  const [allTools, setAllTools] = useState<ApiToolInfo[]>([]);
  const [acIndex, setAcIndex] = useState(0);
  const [acDismissed, setAcDismissed] = useState(false);

  const active = store.conversations.find((c) => c.id === store.activeId) ?? store.conversations[0];
  const turns = active.turns;

  const sortedConversations = useMemo(
    () => [...store.conversations].sort((a, b) => b.updatedAt - a.updatedAt),
    [store.conversations],
  );

  const searxngReady = useMemo(() => {
    const p = plugins.find((p) => p.id === "searxng");
    return p?.status === "ACTIVE";
  }, [plugins]);

  useEffect(() => {
    listTools().then(setAllTools).catch(() => {});
  }, []);

  useEffect(() => {
    listPlugins()
      .then(setPlugins)
      .catch(() => {})
      .finally(() => setPluginsLoaded(true));
  }, []);

  useEffect(() => {
    saveStore(store);
  }, [store]);

  useEffect(() => {
    threadEndRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [turns, store.activeId]);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  /** Patch a conversation by id — a submitted turn keeps targeting the chat it started in. */
  const patchConversation = (id: string, fn: (c: ChatConversation) => ChatConversation) =>
    setStore((prev) => ({
      ...prev,
      conversations: prev.conversations.map((c) => (c.id === id ? fn(c) : c)),
    }));

  const newChat = () => {
    setStore((prev) => {
      const current = prev.conversations.find((c) => c.id === prev.activeId);
      if (current && current.turns.length === 0) return prev; // already on a fresh chat
      const conv = freshConversation();
      return { activeId: conv.id, conversations: [conv, ...prev.conversations] };
    });
    setHistoryOpen(false);
    inputRef.current?.focus();
  };

  const selectConversation = (id: string) => {
    if (id === store.activeId) return;
    setStore((prev) => ({ ...prev, activeId: id }));
    setHistoryOpen(false);
    inputRef.current?.focus();
  };

  const deleteConversation = (id: string) => {
    setStore((prev) => {
      const remaining = prev.conversations.filter((c) => c.id !== id);
      if (remaining.length === 0) {
        const conv = freshConversation();
        return { activeId: conv.id, conversations: [conv] };
      }
      return {
        activeId: prev.activeId === id ? remaining[0].id : prev.activeId,
        conversations: remaining,
      };
    });
    setHistoryOpen(false);
  };

  const token = useMemo(() => activeToken(input), [input]);

  const acItems = useMemo(() => {
    if (!token || acDismissed) return [];
    if (token.sigil === "@") {
      const apps = new Map<string, number>();
      for (const t of allTools) {
        if (t.appSlug.startsWith(token.fragment)) {
          apps.set(t.appSlug, (apps.get(t.appSlug) ?? 0) + 1);
        }
      }
      return Array.from(apps.entries()).slice(0, 8).map(([app, count]) => ({
        key: "@" + app,
        sigil: "@" as const,
        value: app,
        label: app,
        detail: `${count} ${count === 1 ? "tool" : "tools"}`,
        method: null as string | null,
      }));
    }
    // '#' — scope by a preceding @app if one was typed
    const appMatch = /^@([\w-]+)\s/.exec(input.trimStart());
    const appScope = appMatch ? appMatch[1].toLowerCase() : null;
    return allTools
      .filter((t) => t.enabled)
      .filter((t) => !appScope || t.appSlug === appScope)
      .filter((t) =>
        token.fragment === "" ||
        t.name.includes(token.fragment) ||
        t.requestSlug.includes(token.fragment))
      .slice(0, 8)
      .map((t) => ({
        key: "#" + t.name,
        sigil: "#" as const,
        value: appScope ? t.requestSlug : t.name,
        label: appScope ? t.requestSlug : t.name,
        detail: t.displayName,
        method: t.method,
      }));
  }, [token, acDismissed, allTools, input]);

  useEffect(() => {
    setAcIndex(0);
  }, [acItems.length, token?.fragment]);

  const acceptSuggestion = (item: { sigil: "@" | "#"; value: string }) => {
    if (!token) return;
    const next = input.slice(0, token.start) + item.sigil + item.value + " ";
    setInput(next);
    setAcDismissed(false);
    inputRef.current?.focus();
  };

  const handleInputKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (acItems.length === 0) return;
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setAcIndex((i) => (i + 1) % acItems.length);
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setAcIndex((i) => (i - 1 + acItems.length) % acItems.length);
    } else if (e.key === "Tab" || (e.key === "Enter" && token && token.fragment !== acItems[acIndex]?.value)) {
      e.preventDefault();
      acceptSuggestion(acItems[acIndex]);
    } else if (e.key === "Escape") {
      setAcDismissed(true);
    }
  };

  const submit = async (raw: string, includeWeb = webOn) => {
    const trimmed = raw.trim();
    if (!trimmed) return;

    lastConsumedQuery.current = trimmed;

    const convId = active.id;
    const userTurn: ChatTurn = {
      id: crypto.randomUUID(),
      role: "user",
      createdAt: Date.now(),
      text: trimmed,
    };
    const assistantTurn: ChatTurn = {
      id: crypto.randomUUID(),
      role: "assistant",
      createdAt: Date.now(),
      payload: { kind: "loading" },
    };

    patchConversation(convId, (c) => ({
      ...c,
      title: c.turns.length === 0 ? titleFromText(trimmed) : c.title,
      updatedAt: Date.now(),
      turns: [...c.turns, userTurn, assistantTurn],
    }));
    setInput("");

    const patchAssistant = (fn: (t: ChatTurn) => ChatTurn) =>
      patchConversation(convId, (c) => ({
        ...c,
        updatedAt: Date.now(),
        turns: c.turns.map((t) => (t.id === assistantTurn.id ? fn(t) : t)),
      }));

    // #/@ — deterministic tool path, unchanged (no answer generation involved).
    if (isToolInvocation(trimmed)) {
      try {
        const data = await search(trimmed, 20, includeWeb);
        patchAssistant((t) => ({ ...t, payload: { kind: "search", data } }));
      } catch (e) {
        const message = e instanceof Error ? e.message : "Request failed";
        patchAssistant((t) => ({ ...t, payload: { kind: "error", message } }));
      }
      return;
    }

    // Plain messages return retrieved evidence. No external answer model is invoked.
    try {
      const data = await search(trimmed, 20, includeWeb);
      patchAssistant((t) => ({ ...t, payload: { kind: "search", data } }));
    } catch (e) {
      const message = e instanceof Error ? e.message : "Request failed";
      patchAssistant((t) => ({
        ...t,
        payload: { kind: "error", message, retryText: trimmed },
      }));
    }
  };

  // Topbar's independent quick-search box (`/?q=...`) — each new value becomes a new turn.
  useEffect(() => {
    if (!pluginsLoaded) return;
    const urlWeb = params.get("web") === "1";
    const q = params.get("q") ?? "";
    if (!q.trim() || q === lastConsumedQuery.current) return;
    lastConsumedQuery.current = q;
    const includeWeb = urlWeb && searxngReady;
    if (urlWeb && !includeWeb) {
      setWebOn(false);
      setInitialWebWarning(true);
    } else {
      setWebOn(includeWeb);
    }
    setParams({}, { replace: true });
    submit(q, includeWeb);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pluginsLoaded, params]);

  const isToolQuery = isToolInvocation(input);
  const lastTurn = turns.length > 0 ? turns[turns.length - 1] : null;
  const isGenerating =
    !!lastTurn &&
    lastTurn.payload?.kind === "loading";
  const activeMessageCount = turns.filter((turn) => turn.role === "user").length;

  return (
    <div className="chat-page">
      <aside
        id="chat-history"
        className={`chat-history-rail ${historyOpen ? "is-mobile-open" : ""}`}
        aria-label="Conversation history"
      >
        <div className="chat-history-header">
          <span className="chat-history-title">Conversations</span>
          <button type="button" className="btn btn-sm chat-history-new" onClick={newChat}>
            <PlusIcon size={13} />
            New chat
          </button>
        </div>
        <div className="chat-history-list">
          {sortedConversations.map((c) => {
            const messageCount = c.turns.filter((t) => t.role === "user").length;
            return (
              <div key={c.id} className={`chat-history-item ${c.id === active.id ? "active" : ""}`}>
                <button
                  type="button"
                  className="chat-history-item-main"
                  onClick={() => selectConversation(c.id)}
                  aria-current={c.id === active.id ? "true" : undefined}
                >
                  <span className="chat-history-item-title">{c.title}</span>
                  <span className="chat-history-item-meta mono">
                    {formatConversationTime(c.updatedAt)}
                    {messageCount > 0 && ` · ${messageCount} ${messageCount === 1 ? "message" : "messages"}`}
                  </span>
                </button>
                <button
                  type="button"
                  className="chat-history-item-delete"
                  onClick={() => deleteConversation(c.id)}
                  title="Delete conversation"
                  aria-label={`Delete conversation: ${c.title}`}
                >
                  <TrashIcon size={13} />
                </button>
              </div>
            );
          })}
        </div>
      </aside>

      <div className="chat-main">
        <header className="chat-conversation-header">
          <button
            type="button"
            className="btn btn-sm chat-history-mobile-toggle"
            aria-controls="chat-history"
            aria-expanded={historyOpen}
            onClick={() => setHistoryOpen((open) => !open)}
          >
            <BookIcon size={14} />
            History
          </button>
          <div className="chat-conversation-heading">
            <h1 className="chat-conversation-title">{active.title}</h1>
            <span className="chat-conversation-meta mono">
              {activeMessageCount} {activeMessageCount === 1 ? "message" : "messages"}
            </span>
          </div>
        </header>

        <div className="chat-thread">
          {turns.length === 0 && (
            <div className="chat-empty-state">
              <h1 className="search-title">Chat with the knowledge base</h1>
              <p className="search-subtitle">
                Search retrieves cited context from your knowledge base.{" "}
                Type{" "}
                <code className="hash-hint">
                  <HashIcon size={12} /> keyword
                </code>{" "}
                to invoke a tool deterministically.
              </p>
            </div>
          )}

          {initialWebWarning && (
            <div className="warning-banner" role="status">
              <AlertIcon size={16} />
              <span>Web search is unavailable — the SearXNG plugin is not installed or active.</span>
              <Link to="/plugins" className="btn btn-sm">Go to Plugins</Link>
            </div>
          )}

          {turns.map((turn) =>
            turn.role === "user" ? (
              <UserTurnBlock key={turn.id} text={turn.text ?? ""} createdAt={turn.createdAt} />
            ) : (
              <AssistantTurnBlock
                key={turn.id}
                payload={turn.payload}
                setInput={setInput}
                focusInput={() => inputRef.current?.focus()}
                onRetry={submit}
              />
            ),
          )}
          <div ref={threadEndRef} />
        </div>

        <div className="chat-composer-bar">
          <form
            className="search-box chat-composer"
            onSubmit={(e) => {
              e.preventDefault();
              submit(input);
            }}
          >
            <SearchIcon size={18} className="search-box-icon" />
            <input
              ref={inputRef}
              type="text"
              className={`search-box-input ${isToolQuery ? "is-tool" : ""}`}
              value={input}
              onChange={(e) => {
                setInput(e.target.value);
                setAcDismissed(false);
              }}
              onKeyDown={handleInputKeyDown}
              placeholder="Ask a question, or @app #tool_name…"
              aria-label="Chat message"
              autoComplete="off"
            />
            {acItems.length > 0 && (
              <ul className="tool-autocomplete" role="listbox">
                {acItems.map((item, i) => (
                  <li key={item.key} role="option" aria-selected={i === acIndex}>
                    <button
                      type="button"
                      className={`tool-ac-item ${i === acIndex ? "tool-ac-active" : ""}`}
                      onMouseDown={(e) => {
                        e.preventDefault();
                        acceptSuggestion(item);
                      }}
                      onMouseEnter={() => setAcIndex(i)}
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
            <label className="web-toggle" title={!searxngReady ? "Web search requires SearXNG — install on the Plugins page" : "Augment results with live web content"}>
              <input
                type="checkbox"
                checked={webOn}
                disabled={!searxngReady}
                onChange={(e) => setWebOn(e.target.checked)}
              />
              <GlobeIcon size={15} />
              <span className="web-toggle-label">Web</span>
            </label>
            <button
              type="submit"
              className="btn btn-primary search-box-submit"
              disabled={!input.trim() || isGenerating}
            >
              <SendIcon size={16} />
              Search
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}

function UserTurnBlock({ text, createdAt }: { text: string; createdAt: number }) {
  return (
    <div className="chat-turn chat-turn-user">
      <div className="chat-turn-meta mono">
        <span className="chat-turn-role">you</span>
        <span className="chat-turn-time">{new Date(createdAt).toLocaleTimeString()}</span>
      </div>
      <div className="chat-turn-body">{text}</div>
    </div>
  );
}

function AssistantTurnBlock({
  payload,
  setInput,
  focusInput,
  onRetry,
}: {
  payload?: AssistantPayload;
  setInput: (v: string) => void;
  focusInput: () => void;
  onRetry: (text: string) => void;
}) {
  return (
    <div className="chat-turn chat-turn-assistant">
      <div className="chat-turn-meta mono">
        <span className="chat-turn-role">assistant</span>
      </div>
      <div className="chat-turn-body">
        {!payload || payload.kind === "loading" ? (
          <GeneratingIndicator />
        ) : payload.kind === "error" ? (
          <ErrorView payload={payload} onRetry={onRetry} />
        ) : payload.kind === "answer" ? (
          <AnswerView payload={payload} />
        ) : (
          <SearchResponseView response={payload.data} setInput={setInput} focusInput={focusInput} />
        )}
      </div>
    </div>
  );
}

function GeneratingIndicator() {
  return (
    <div className="chat-generating mono" role="status">
      <span className="chat-generating-dots">
        <span />
        <span />
        <span />
      </span>
      generating…
    </div>
  );
}

function ErrorView({
  payload,
  onRetry,
}: {
  payload: Extract<AssistantPayload, { kind: "error" }>;
  onRetry: (text: string) => void;
}) {
  const retryText = payload.retryText;
  return (
    <div className="chat-error">
      <div className="error-banner" role="alert">{payload.message}</div>
      <div className="chat-error-actions">
        {retryText && (
          <button type="button" className="btn btn-sm chat-retry-btn" onClick={() => onRetry(retryText)}>
            Retry
          </button>
        )}
      </div>
      {payload.sources && payload.sources.length > 0 && (
        <>
          <p className="chat-error-fallback">The retrieved excerpts are still available:</p>
          <SourcesDisclosure sources={payload.sources} />
        </>
      )}
    </div>
  );
}

function AnswerView({ payload }: { payload: Extract<AssistantPayload, { kind: "answer" }> }) {
  return (
    <div className="chat-answer">
      {payload.text ? (
        <MarkdownText text={payload.text} />
      ) : (
        payload.streaming && <GeneratingIndicator />
      )}
      {payload.streaming && payload.text !== "" && <span className="chat-cursor" aria-hidden>▍</span>}
      {payload.stopped && <div className="chat-note mono">stopped — the answer above is partial</div>}
      {payload.sources.length > 0 && <SourcesDisclosure sources={payload.sources} />}
    </div>
  );
}

/**
 * Per-turn evidence stays hidden behind per-type counts. Expanding it reveals each RAG
 * file group and web result without overwhelming the conversation transcript.
 */
function SourcesDisclosure({ sources, query = "" }: { sources: SearchResult[]; query?: string }) {
  const [open, setOpen] = useState(false);
  const localGroups = useMemo(() => groupLocalResults(sources.filter((s) => s.sourceKind !== "web")), [sources]);
  const webSources = useMemo(() => sources.filter((s) => s.sourceKind === "web"), [sources]);
  const evidenceCount = localGroups.length + webSources.length;

  return (
    <div className="chat-sources">
      <button
        type="button"
        className="chat-sources-toggle"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-label={`${open ? "Hide" : "View"} ${evidenceCount} evidence sources`}
      >
        <span className="chat-sources-label">Evidence</span>
        <span className="chat-source-count mono">
          <FileIcon size={12} />
          RAG <strong>{localGroups.length}</strong>
        </span>
        <span className="chat-source-count mono">
          <GlobeIcon size={12} />
          Web <strong>{webSources.length}</strong>
        </span>
        <span className="chat-sources-action">
          {open ? "Hide sources" : "View sources"}
          <ChevronRightIcon size={13} className={`file-group-chevron ${open ? "chev-open" : ""}`} />
        </span>
      </button>
      {open && (
        <div className="chat-sources-body">
          {localGroups.length > 0 && (
            <section className="chat-sources-section">
              <h4 className="chat-sources-section-title">
                RAG files <span className="mono">{localGroups.length}</span>
              </h4>
              <ol className="file-list">
                {localGroups.map((group, i) => (
                  <FileGroupCard
                    key={`src-${group.sourceKind}-${group.sourceName}-${group.sourcePath}-${group.sourceUrl}`}
                    group={group}
                    rank={i + 1}
                    query={query}
                    defaultExpanded={false}
                  />
                ))}
              </ol>
            </section>
          )}
          {webSources.length > 0 && (
            <section className="chat-sources-section">
              <h4 className="chat-sources-section-title">
                Web sources <span className="mono">{webSources.length}</span>
              </h4>
              <ol className="web-result-list">
                {webSources.map((r, i) => (
                  <WebResultCard key={"src-" + r.id} result={r} rank={i + 1} query={query} />
                ))}
              </ol>
            </section>
          )}
        </div>
      )}
    </div>
  );
}

function SearchResponseView({
  response,
  setInput,
  focusInput,
}: {
  response: SearchResponse;
  setInput: (v: string) => void;
  focusInput: () => void;
}) {
  const { localGroups, webResults } = useMemo(() => {
    if (response.mode !== "rag") return { localGroups: [] as FileGroup[], webResults: [] as SearchResult[] };
    return {
      localGroups: groupLocalResults(response.results.filter((r) => r.sourceKind !== "web")),
      webResults: response.results.filter((r) => r.sourceKind === "web"),
    };
  }, [response]);

  if (response.mode === "notReady") {
    return (
      <div className="setup-banner" role="status">
        <div className="setup-banner-content">
          <span className="setup-banner-title">Search requires setup</span>
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
        <Link to="/plugins" className="btn btn-primary">Go to Plugins</Link>
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
            {response.suggestions.map((s) => (
              <li key={s.id}>
                <button
                  type="button"
                  className="tool-ac-item"
                  onClick={() => {
                    setInput(`#${s.name} `);
                    focusInput();
                  }}
                >
                  <HashIcon size={13} className="tool-ac-icon" />
                  <span className="tool-ac-name mono">{s.name}</span>
                  <span className={`method-badge mono ${s.method === "GET" ? "" : "method-write"}`}>
                    {s.method}
                  </span>
                  <span className="tool-ac-detail">{s.displayName}</span>
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
          <span className="empty-line">No matching documents.</span>
          <span className="empty-hint">Upload files via the Files page, then ask again.</span>
        </div>
      );
    }
    return (
      <SourcesDisclosure sources={response.results} query={response.query} />
    );
  }

  return null;
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
  defaultExpanded?: boolean;
}) {
  const [expanded, setExpanded] = useState(defaultExpanded ?? rank === 1);
  const matchCount = group.chunks.length;

  return (
    <li className="file-group">
      <button
        className="file-group-header"
        onClick={() => setExpanded((v) => !v)}
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
          {matchCount} {matchCount === 1 ? "match" : "matches"}
        </span>
      </button>

      <div className="file-group-path mono">{group.sourcePath}</div>

      {expanded && (
        <div className="file-group-chunks">
          {group.chunks.map((chunk, i) => (
            <ChunkCard
              key={chunk.id}
              chunk={chunk}
              rank={i + 1}
              query={query}
            />
          ))}
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
            aria-label="Open in new tab"
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

/** Splits text into runs, marking query terms with <mark>. Case-insensitive match. */
function HighlightedText({ text, query }: { text: string; query: string }) {
  const terms = useMemo(() => {
    return query
      .toLowerCase()
      .replace(/[#]/g, "")
      .split(/[^a-z0-9]+/i)
      .filter((t) => t.length >= 2)
      .map(escapeRegex);
  }, [query]);

  if (terms.length === 0) return <>{text}</>;

  const re = new RegExp(`(${terms.join("|")})`, "gi");
  const parts = text.split(re);
  const lowerTerms = terms.map((t) => t.toLowerCase());

  return (
    <>
      {parts.map((part, i) =>
        lowerTerms.includes(part.toLowerCase()) ? (
          <mark key={i} className="search-highlight">
            {part}
          </mark>
        ) : (
          <React.Fragment key={i}>{part}</React.Fragment>
        ),
      )}
    </>
  );
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
