import { useEffect, useState } from "react";
import { getGuide, listGuides, type GuideArticle, type GuideSummary } from "../api";
import { AlertIcon, BookIcon, ChevronRightIcon, CodeIcon } from "../icons";

function messageFor(error: unknown): string {
  return error instanceof Error ? error.message : "The guide could not be loaded.";
}

export function GuidePage() {
  const [guides, setGuides] = useState<GuideSummary[]>([]);
  const [selectedId, setSelectedId] = useState("start");
  const [article, setArticle] = useState<GuideArticle | null>(null);
  const [loadingList, setLoadingList] = useState(true);
  const [loadingArticle, setLoadingArticle] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    listGuides()
      .then((items) => {
        if (!active) return;
        setGuides(items);
        if (items.length > 0 && !items.some((item) => item.id === selectedId)) {
          setSelectedId(items[0].id);
        }
        setError(null);
      })
      .catch((reason: unknown) => active && setError(messageFor(reason)))
      .finally(() => active && setLoadingList(false));
    return () => { active = false; };
  }, [selectedId]);

  useEffect(() => {
    let active = true;
    setLoadingArticle(true);
    getGuide(selectedId)
      .then((item) => {
        if (!active) return;
        setArticle(item);
        setError(null);
      })
      .catch((reason: unknown) => active && setError(messageFor(reason)))
      .finally(() => active && setLoadingArticle(false));
    return () => { active = false; };
  }, [selectedId]);

  return (
    <main className="guide-page">
      <header className="guide-page-header">
        <div>
          <p className="guide-eyebrow"><BookIcon size={15} /> Working guide</p>
          <h1>Use the workspace with confidence.</h1>
          <p>Short, current instructions for people using the app and clients connecting through MCP.</p>
        </div>
        <div className="guide-header-note">
          <CodeIcon size={15} />
          <span>Protocol guidance is also available through MCP resources and prompts.</span>
        </div>
      </header>

      {error && <div className="guide-error" role="alert"><AlertIcon size={15} /> {error}</div>}

      <div className="guide-layout">
        <nav className="guide-rail" aria-label="Guide topics">
          <p className="guide-rail-label">Topics</p>
          {loadingList ? <GuideRailSkeleton /> : guides.map((guide) => (
            <button
              key={guide.id}
              className={`guide-rail-item${guide.id === selectedId ? " is-active" : ""}`}
              type="button"
              onClick={() => setSelectedId(guide.id)}
              aria-current={guide.id === selectedId ? "page" : undefined}
            >
              <span className="guide-rail-copy"><strong>{guide.title}</strong><small>{guide.audience}</small></span>
              <ChevronRightIcon size={15} />
            </button>
          ))}
          <div className="guide-rail-footnote">
            <strong>For client authors</strong>
            <p>Connect to <code>/mcp</code>, then read the operating guide resource before using live tools.</p>
          </div>
        </nav>

        <section className="guide-article" aria-live="polite">
          {loadingArticle || !article ? <GuideArticleSkeleton /> : <Article article={article} />}
        </section>
      </div>
    </main>
  );
}

function Article({ article }: { article: GuideArticle }) {
  return <>
    <header className="guide-article-header">
      <span>{article.audience}</span>
      <h2>{article.title}</h2>
      <p>{article.summary}</p>
    </header>
    <div className="guide-sections">
      {article.sections.map((section, index) => (
        <section className="guide-section" key={`${section.title}-${index}`}>
          <div className="guide-section-index">{String(index + 1).padStart(2, "0")}</div>
          <div className="guide-section-body">
            <h3>{section.title}</h3>
            <p>{section.body}</p>
            {section.steps.length > 0 && <ol>
              {section.steps.map((step) => <li key={step}>{step}</li>)}
            </ol>}
            {section.code && <pre><code>{section.code}</code></pre>}
            {section.note && <aside className="guide-note"><AlertIcon size={15} /><span>{section.note}</span></aside>}
          </div>
        </section>
      ))}
    </div>
  </>;
}

function GuideRailSkeleton() {
  return <div className="guide-rail-skeleton" aria-label="Loading guide topics">
    {[0, 1, 2, 3, 4].map((item) => <span key={item} />)}
  </div>;
}

function GuideArticleSkeleton() {
  return <div className="guide-article-skeleton" aria-label="Loading guide article">
    <span /><span /><span /><span /><span />
  </div>;
}
