import { api } from "../api.js";
import { escapeAttr, escapeHtml, icon, message, on } from "../ui.js";

export async function mount(outlet) {
  const state = { topics: [], tutorials: [], selectedId: "start", topic: null, loading: true, error: "" };
  const abort = new AbortController();

  /**
   * Hands-on walkthroughs are the first thing offered: reference is less useful than a first run.
   * The card carries level, length, and example count because those are what a reader picks on —
   * a title alone does not say whether this is the ten-minute one or the twenty-five-minute one.
   */
  function tutorialCards() {
    if (!state.tutorials.length) return "";
    return `<section class="help-tutorials" aria-labelledby="help-tutorials-title">
      <div class="help-tutorials-head">
        <h2 id="help-tutorials-title">${icon("route", 15)} Tutorials</h2>
        <p>Follow one with the app open. Every step names where it happens, gives worked examples, and says how to tell it worked.</p>
      </div>
      <div class="help-tutorial-grid">${state.tutorials.map((tutorial) => `<a class="help-tutorial-card" href="/tutorial?id=${encodeURIComponent(tutorial.id)}" data-link>
        <span class="help-tutorial-level" data-level="${escapeAttr((tutorial.level || "").toLowerCase())}">${escapeHtml(tutorial.level || "Walkthrough")}</span>
        <strong>${escapeHtml(tutorial.title)}</strong>
        <p>${escapeHtml(tutorial.summary)}</p>
        <span class="help-tutorial-outcome">${icon("check", 13)}<span>${escapeHtml(tutorial.outcome)}</span></span>
        <span class="help-tutorial-meta mono">${tutorial.steps} steps · ${tutorial.examples} examples · ${escapeHtml(tutorial.duration)}</span>
      </a>`).join("")}</div>
    </section>`;
  }

  function article() {
    if (!state.topic) return '<div class="help-article-skeleton" aria-label="Loading help topic"><span></span><span></span><span></span><span></span><span></span></div>';
    return `<header class="help-article-header"><span>${escapeHtml(state.topic.audience)}</span><h2>${escapeHtml(state.topic.title)}</h2><p>${escapeHtml(state.topic.summary)}</p></header>
      <div class="help-sections">${state.topic.sections.map((section, index) => `<section class="help-section"><div class="help-section-index">${String(index + 1).padStart(2, "0")}</div><div class="help-section-body"><h3>${escapeHtml(section.title)}</h3><p>${escapeHtml(section.body)}</p>${section.steps?.length ? `<ol>${section.steps.map((step) => `<li>${escapeHtml(step)}</li>`).join("")}</ol>` : ""}${section.code ? `<pre><code>${escapeHtml(section.code)}</code></pre>` : ""}${section.note ? `<aside class="help-note">${icon("alert", 15)}<span>${escapeHtml(section.note)}</span></aside>` : ""}</div></section>`).join("")}</div>`;
  }

  function render() {
    outlet.innerHTML = `<section class="help-page" aria-labelledby="help-page-title">
      <header class="help-page-header"><div><p class="help-eyebrow">${icon("help", 15)} Help</p><h1 id="help-page-title">Use the workspace with confidence.</h1><p>Start with a tutorial to learn by doing, or browse the reference topics for the rules behind any one screen.</p></div><div class="help-header-note">${icon("file", 15)}<span>Protocol guidance is also available through MCP resources and prompts.</span></div></header>
      ${state.error ? `<div class="help-error" role="alert">${icon("alert", 15)} ${escapeHtml(state.error)}</div>` : ""}
      ${tutorialCards()}
      <div class="help-layout"><nav class="help-rail" aria-label="Help topics"><p class="help-rail-label">Reference</p>${state.loading ? '<div class="help-rail-skeleton"><span></span><span></span><span></span><span></span></div>' : state.topics.map((topic) => `<button class="help-rail-item ${topic.id === state.selectedId ? "is-active" : ""}" type="button" data-action="select-topic" data-id="${escapeAttr(topic.id)}" ${topic.id === state.selectedId ? 'aria-current="page"' : ""}><span class="help-rail-copy"><strong>${escapeHtml(topic.title)}</strong><small>${escapeHtml(topic.audience)}</small></span>${icon("chevron", 15)}</button>`).join("")}<div class="help-rail-footnote"><strong>For client authors</strong><p>Connect to <code>/mcp</code>, then read the operating guide resource before using live tools.</p></div></nav><section class="help-article" aria-live="polite">${article()}</section></div>
    </section>`;
  }

  async function loadTopic(id) {
    state.selectedId = id;
    state.topic = null;
    render();
    try {
      state.topic = await api.getHelpTopic(id);
      state.error = "";
    } catch (error) {
      state.error = message(error, "The help topic could not be loaded.");
    }
    render();
  }

  on(outlet, "click", "[data-action='select-topic']", async (_event, target) => loadTopic(target.dataset.id));
  render();
  // A tutorial listing failure must not take the reference content down with it.
  api.listTutorials().then((tutorials) => {
    state.tutorials = tutorials;
    render();
  }).catch(() => {});
  try {
    state.topics = await api.listHelpTopics();
    if (state.topics.length && !state.topics.some((topic) => topic.id === state.selectedId)) state.selectedId = state.topics[0].id;
    state.loading = false;
    render();
    await loadTopic(state.selectedId);
  } catch (error) {
    state.loading = false;
    state.error = message(error, "The help topic could not be loaded.");
    render();
  }
  return () => abort.abort();
}
