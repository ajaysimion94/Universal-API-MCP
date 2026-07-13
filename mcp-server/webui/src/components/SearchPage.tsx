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
} from "../icons";
import { ToolFormPanel } from "./ToolFormPanel";
import { ToolConfirmPanel } from "./ToolConfirmPanel";
import { ToolResultPanel } from "./ToolResultPanel";

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

export function SearchPage() {
  const [params, setParams] = useSearchParams();
  const initialQuery = params.get("q") ?? "";
  const [input, setInput] = useState(initialQuery);
  const [webOn, setWebOn] = useState(false);
  const [response, setResponse] = useState<SearchResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [plugins, setPlugins] = useState<PluginInfo[]>([]);
  const [pluginsLoaded, setPluginsLoaded] = useState(false);
  const [initialWebWarning, setInitialWebWarning] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  // Tool autocomplete: the full tool list is small — fetch once, filter as you type.
  const [allTools, setAllTools] = useState<ApiToolInfo[]>([]);
  const [acIndex, setAcIndex] = useState(0);
  const [acDismissed, setAcDismissed] = useState(false);

  const searxngReady = useMemo(() => {
    const p = plugins.find((p) => p.id === "searxng");
    return p?.status === "ACTIVE";
  }, [plugins]);

  useEffect(() => {
    listTools().then(setAllTools).catch(() => {});
  }, []);

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

  const runSearch = async (query: string, web: boolean) => {
    const trimmed = query.trim();
    if (!trimmed) {
      setResponse(null);
      setParams({});
      return;
    }
    setLoading(true);
    setError(null);
    setParams({ q: trimmed, ...(web ? { web: "1" } : {}) });
    try {
      const res = await search(trimmed, 20, web);
      setResponse(res);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Search failed");
      setResponse(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    listPlugins()
      .then(setPlugins)
      .catch(() => {})
      .finally(() => setPluginsLoaded(true));
  }, []);

  useEffect(() => {
    if (!pluginsLoaded) return;
    const urlWeb = params.get("web") === "1";
    const q = params.get("q") ?? "";
    if (urlWeb && !searxngReady) {
      setWebOn(false);
      setInitialWebWarning(true);
      if (q.trim()) runSearch(q, false);
    } else {
      setWebOn(urlWeb);
      if (q.trim()) runSearch(q, urlWeb);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pluginsLoaded]);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  const isToolQuery = input.trim().startsWith("#") || input.trim().startsWith("@");

  const { localGroups, webResults } = useMemo(() => {
    if (!response || response.mode !== "rag") return { localGroups: [], webResults: [] };
    const groupBy = (results: SearchResult[]) => {
      const map = new Map<string, FileGroup>();
      for (const r of results) {
        const key = r.sourceName;
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
    };
    return {
      localGroups: groupBy(response.results.filter((r) => r.sourceKind !== "web")),
      webResults: response.results.filter((r) => r.sourceKind === "web"),
    };
  }, [response]);

  return (
    <div className="search-page">
      <div className="search-hero">
        <div className="search-hero-inner">
          <h1 className="search-title">Search the knowledge base</h1>
          <p className="search-subtitle">
            Plain keywords run RAG retrieval — cited, grounded context.
            Type{" "}
            <code className="hash-hint">
              <HashIcon size={12} /> keyword
            </code>{" "}
            to invoke a tool deterministically.
          </p>

          <form
            className="search-box"
            onSubmit={(e) => {
              e.preventDefault();
              runSearch(input, webOn);
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
              placeholder="Search documents, runbooks, SOPs… or @app #tool_name"
              aria-label="Search query"
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
                onChange={(e) => {
                  setWebOn(e.target.checked);
                  if (input.trim()) runSearch(input, e.target.checked);
                }}
              />
              <GlobeIcon size={15} />
              <span className="web-toggle-label">Web</span>
            </label>
            <button type="submit" className="btn btn-primary search-box-submit">
              Search
            </button>
          </form>
        </div>
      </div>

      <div className="search-results-area">
        {error && (
          <div className="error-banner" role="alert">
            {error}
          </div>
        )}

        {initialWebWarning && (
          <div className="warning-banner" role="status">
            <AlertIcon size={16} />
            <span>Web search is unavailable — the SearXNG plugin is not installed or active.</span>
            <Link to="/plugins" className="btn btn-sm">Go to Plugins</Link>
          </div>
        )}

        {!initialWebWarning && response?.mode === "rag" && response.webReady === false && (
          <div className="warning-banner" role="status">
            <AlertIcon size={16} />
            <span>{response.webMessage || "Web augmentation requires the SearXNG plugin."}</span>
            <Link to="/plugins" className="btn btn-sm">Go to Plugins</Link>
          </div>
        )}

        {response?.mode === "rag" && response.lexicalOnly && (
          <div className="warning-banner" role="status">
            <AlertIcon size={16} />
            <span>
              {response.lexicalMessage ||
                "Semantic search is off — showing keyword matches only."}
            </span>
            <Link to="/plugins" className="btn btn-sm">Go to Plugins</Link>
          </div>
        )}

        {loading && (
          <div className="search-skeleton">
            {[0, 1, 2].map((i) => (
              <div key={i} className="search-result-skeleton">
                <div className="skel-line skel-title" />
                <div className="skel-line skel-excerpt" />
                <div className="skel-line skel-excerpt" />
                <div className="skel-line skel-meta" />
              </div>
            ))}
          </div>
        )}

        {!loading && !error && response && response.mode === "notReady" && (
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
            <Link to="/plugins" className="btn btn-primary">
              Go to Plugins
            </Link>
          </div>
        )}

        {!loading && !error && response && response.mode === "tool" && (
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
                        const q = `#${s.name} `;
                        setInput(q);
                        inputRef.current?.focus();
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
        )}

        {!loading && !error && response?.mode === "tool-form" && response.toolInfo && (
          <ToolFormPanel
            key={response.query}
            tool={response.toolInfo}
            prefill={response.prefill}
            missingRequired={response.missingRequired}
            violations={response.violations}
            parseError={response.error}
          />
        )}

        {!loading && !error && response?.mode === "tool-confirm" && response.toolInfo && response.preview && (
          <ToolConfirmPanel
            key={response.query}
            tool={response.toolInfo}
            preview={response.preview}
            args={response.args ?? {}}
            onCancel={() => setResponse(null)}
          />
        )}

        {!loading && !error && response?.mode === "tool-result" && response.toolInfo && response.result && (
          <ToolResultPanel toolName={response.toolInfo.name} result={response.result} />
        )}

        {!loading && !error && response && response.mode === "rag" && (
          <>
            <div className="results-meta">
              <span className="results-count mono">
                {localGroups.length} {localGroups.length === 1 ? "file" : "files"}
              </span>
              {webOn && webResults.length > 0 && (
                <>
                  <span className="results-sep">·</span>
                  <span className="results-web mono">
                    <GlobeIcon size={12} /> {webResults.length} web
                  </span>
                </>
              )}
              <span className="results-sep">·</span>
              <span className="results-chunks mono">
                {response.total} {response.total === 1 ? "match" : "matches"}
              </span>
              <span className="results-query mono">for “{response.query}”</span>
            </div>

            {localGroups.length === 0 && webResults.length === 0 ? (
              <div className="search-empty">
                <span className="empty-line">No matching documents.</span>
                <span className="empty-hint">
                  Upload files via the Files page, then search again.
                  Zero results means zero results — nothing is fabricated.
                </span>
              </div>
            ) : (
              <>
                {localGroups.length > 0 && (
                  <section className="results-section">
                    {webOn && <h2 className="results-section-title">Local knowledge base</h2>}
                    <ol className="file-list">
                      {localGroups.map((group, i) => (
                        <FileGroupCard
                          key={"local-" + group.sourceName}
                          group={group}
                          rank={i + 1}
                          query={response.query}
                        />
                      ))}
                    </ol>
                  </section>
                )}

                {webOn && webResults.length > 0 && (
                  <section className="results-section results-section-web">
                    <h2 className="results-section-title">
                      <GlobeIcon size={14} /> From the web
                    </h2>
                    <ol className="web-result-list">
                      {webResults.map((r, i) => (
                        <WebResultCard
                          key={r.id}
                          result={r}
                          rank={i + 1}
                          query={response.query}
                        />
                      ))}
                    </ol>
                  </section>
                )}

                {webOn && webResults.length === 0 && localGroups.length > 0 && (
                  <p className="web-empty-hint">
                    Web results unavailable — is SearXNG running on 127.0.0.1:8888?
                  </p>
                )}
              </>
            )}
          </>
        )}

        {!loading && !error && !response && (
          <div className="search-landing-hint">
            <span>Start typing above to search the knowledge base.</span>
          </div>
        )}
      </div>
    </div>
  );
}

function FileGroupCard({
  group,
  rank,
  query,
}: {
  group: FileGroup;
  rank: number;
  query: string;
}) {
  const [expanded, setExpanded] = useState(rank === 1);
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
