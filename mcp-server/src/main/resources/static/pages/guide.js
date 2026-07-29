import { api } from "../api.js";
import { escapeAttr, escapeHtml, icon, message, on } from "../ui.js";

export async function mount(outlet) {
  const state = { guides: [], selectedId: "start", article: null, loading: true, error: "" };
  const abort = new AbortController();

  function article() {
    if (!state.article) return '<div class="guide-article-skeleton" aria-label="Loading guide article"><span></span><span></span><span></span><span></span><span></span></div>';
    return `<header class="guide-article-header"><span>${escapeHtml(state.article.audience)}</span><h2>${escapeHtml(state.article.title)}</h2><p>${escapeHtml(state.article.summary)}</p></header>
      <div class="guide-sections">${state.article.sections.map((section, index) => `<section class="guide-section"><div class="guide-section-index">${String(index + 1).padStart(2, "0")}</div><div class="guide-section-body"><h3>${escapeHtml(section.title)}</h3><p>${escapeHtml(section.body)}</p>${section.steps?.length ? `<ol>${section.steps.map((step) => `<li>${escapeHtml(step)}</li>`).join("")}</ol>` : ""}${section.code ? `<pre><code>${escapeHtml(section.code)}</code></pre>` : ""}${section.note ? `<aside class="guide-note">${icon("alert", 15)}<span>${escapeHtml(section.note)}</span></aside>` : ""}</div></section>`).join("")}</div>`;
  }

  function render() {
    outlet.innerHTML = `<section class="guide-page" aria-labelledby="guide-page-title">
      <header class="guide-page-header"><div><p class="guide-eyebrow">${icon("book", 15)} Working guide</p><h1 id="guide-page-title">Use the workspace with confidence.</h1><p>Short, current instructions for people using the app and clients connecting through MCP.</p></div><div class="guide-header-note">${icon("file", 15)}<span>Protocol guidance is also available through MCP resources and prompts.</span></div></header>
      ${state.error ? `<div class="guide-error" role="alert">${icon("alert", 15)} ${escapeHtml(state.error)}</div>` : ""}
      <div class="guide-layout"><nav class="guide-rail" aria-label="Guide topics"><p class="guide-rail-label">Topics</p>${state.loading ? '<div class="guide-rail-skeleton"><span></span><span></span><span></span><span></span></div>' : state.guides.map((guide) => `<button class="guide-rail-item ${guide.id === state.selectedId ? "is-active" : ""}" type="button" data-action="select-guide" data-id="${escapeAttr(guide.id)}" ${guide.id === state.selectedId ? 'aria-current="page"' : ""}><span class="guide-rail-copy"><strong>${escapeHtml(guide.title)}</strong><small>${escapeHtml(guide.audience)}</small></span>${icon("chevron", 15)}</button>`).join("")}<div class="guide-rail-footnote"><strong>For client authors</strong><p>Connect to <code>/mcp</code>, then read the operating guide resource before using live tools.</p></div></nav><section class="guide-article" aria-live="polite">${article()}</section></div>
    </section>`;
  }

  async function loadArticle(id) {
    state.selectedId = id;
    state.article = null;
    render();
    try {
      state.article = await api.getGuide(id);
      state.error = "";
    } catch (error) {
      state.error = message(error, "The guide could not be loaded.");
    }
    render();
  }

  on(outlet, "click", "[data-action='select-guide']", async (_event, target) => loadArticle(target.dataset.id));
  render();
  try {
    state.guides = await api.listGuides();
    if (state.guides.length && !state.guides.some((guide) => guide.id === state.selectedId)) state.selectedId = state.guides[0].id;
    state.loading = false;
    render();
    await loadArticle(state.selectedId);
  } catch (error) {
    state.loading = false;
    state.error = message(error, "The guide could not be loaded.");
    render();
  }
  return () => abort.abort();
}
