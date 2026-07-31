import { api } from "../api.js";
import { escapeAttr, escapeHtml, icon, message, on } from "../ui.js";

const STORE_KEY = "mcp.tutorial.progress.v1";

/**
 * Which steps have been ticked, per tutorial. Progress is a convenience, not a record — a reader
 * who clears storage or opens another browser simply starts the checkboxes empty.
 */
function loadProgress() {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORE_KEY));
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    // Progress is optional; the tutorial reads fine without it.
  }
  return {};
}

function saveProgress(progress) {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify(progress));
  } catch {
    // Remembering ticks is an optional convenience.
  }
}

export async function mount(outlet, context) {
  const state = {
    tutorials: [],
    tutorial: null,
    // Which tutorial to open: the ?id= parameter, else the first one listed.
    selectedId: context?.params?.get("id") || null,
    progress: loadProgress(),
    loading: true,
    error: "",
  };
  const abort = new AbortController();

  function done(stepIndex) {
    return Boolean(state.progress[state.selectedId]?.includes(stepIndex));
  }

  function completedCount() {
    return (state.progress[state.selectedId] || []).length;
  }

  function picker() {
    if (state.tutorials.length < 2) return "";
    return `<nav class="tutorial-picker" aria-label="Tutorials">${state.tutorials.map((item) => `<button type="button" class="tutorial-picker-item ${item.id === state.selectedId ? "is-active" : ""}" data-action="select-tutorial" data-id="${escapeAttr(item.id)}" ${item.id === state.selectedId ? 'aria-current="page"' : ""}><strong>${escapeHtml(item.title)}</strong><small>${item.steps} steps · ${escapeHtml(item.duration)}</small></button>`).join("")}</nav>`;
  }

  function step(item, index) {
    const complete = done(index);
    return `<li class="tutorial-step ${complete ? "is-done" : ""}">
      <div class="tutorial-step-rail">
        <button type="button" class="tutorial-step-tick" data-action="toggle-step" data-index="${index}"
          aria-pressed="${complete}" aria-label="${complete ? "Mark step incomplete" : "Mark step complete"}">
          ${complete ? icon("check", 15) : `<span class="tutorial-step-number mono">${String(index + 1).padStart(2, "0")}</span>`}
        </button>
      </div>
      <div class="tutorial-step-body">
        <h3>${escapeHtml(item.title)}</h3>
        <p>${escapeHtml(item.body)}</p>
        ${item.actions?.length ? `<ol class="tutorial-actions">${item.actions.map((action) => `<li>${escapeHtml(action)}</li>`).join("")}</ol>` : ""}
        ${item.code ? `<pre class="tutorial-code"><code>${escapeHtml(item.code)}</code></pre>` : ""}
        ${item.verify ? `<p class="tutorial-verify">${icon("check", 14)} <span>${escapeHtml(item.verify)}</span></p>` : ""}
        ${item.note ? `<aside class="tutorial-note">${icon("alert", 14)}<span>${escapeHtml(item.note)}</span></aside>` : ""}
        ${item.route ? `<a class="btn btn-sm tutorial-route" href="${escapeAttr(item.route)}" data-link>${escapeHtml(item.routeLabel || "Open")} ${icon("chevron", 14)}</a>` : ""}
      </div>
    </li>`;
  }

  function body() {
    if (state.loading) {
      return '<div class="guide-article-skeleton" aria-label="Loading tutorial"><span></span><span></span><span></span><span></span></div>';
    }
    if (!state.tutorial) {
      return `<div class="tutorial-empty">${icon("route", 24)}<h2>No tutorial selected</h2><p>Pick a walkthrough to get started.</p></div>`;
    }
    const total = state.tutorial.steps.length;
    const complete = completedCount();
    return `<article class="tutorial-body">
      <header class="tutorial-body-header">
        <div>
          <h2>${escapeHtml(state.tutorial.title)}</h2>
          <p>${escapeHtml(state.tutorial.summary)}</p>
        </div>
        <div class="tutorial-progress">
          <span class="mono">${complete} / ${total}</span>
          <div class="tutorial-progress-bar" role="progressbar" aria-valuenow="${complete}" aria-valuemin="0" aria-valuemax="${total}" aria-label="Tutorial progress">
            <span style="width: ${total ? Math.round((complete / total) * 100) : 0}%"></span>
          </div>
          ${complete ? '<button type="button" class="tutorial-reset" data-action="reset-progress">Reset</button>' : ""}
        </div>
      </header>
      <p class="tutorial-outcome">${icon("check", 14)} <span>You will end up with: ${escapeHtml(state.tutorial.outcome)}</span></p>
      <ol class="tutorial-steps">${state.tutorial.steps.map(step).join("")}</ol>
      ${complete === total && total ? `<div class="tutorial-complete">${icon("check", 18)}<div><strong>Walkthrough complete.</strong><p>Browse <a href="/help" data-link>Help</a> for the reference version of anything here.</p></div></div>` : ""}
    </article>`;
  }

  function render() {
    outlet.innerHTML = `<section class="tutorial-page" aria-labelledby="tutorial-page-title">
      <header class="tutorial-page-header">
        <div>
          <p class="guide-eyebrow">${icon("route", 15)} Tutorial</p>
          <h1 id="tutorial-page-title">Learn it by doing it.</h1>
          <p>Follow along with the app open — each step names where it happens and how to tell it worked.</p>
        </div>
        <a class="btn" href="/help" data-link>${icon("help", 15)} Help</a>
      </header>
      ${state.error ? `<div class="guide-error" role="alert">${icon("alert", 15)} ${escapeHtml(state.error)}</div>` : ""}
      ${picker()}
      ${body()}
    </section>`;
  }

  async function loadTutorial(id) {
    state.selectedId = id;
    state.tutorial = null;
    state.loading = true;
    render();
    try {
      state.tutorial = await api.getTutorial(id);
      state.error = "";
    } catch (error) {
      state.error = message(error, "The tutorial could not be loaded.");
    }
    state.loading = false;
    render();
  }

  on(outlet, "click", "[data-action]", async (_event, target) => {
    const { action } = target.dataset;
    if (action === "select-tutorial") {
      if (target.dataset.id === state.selectedId) return;
      context?.navigate?.(`/tutorial?id=${encodeURIComponent(target.dataset.id)}`, true);
      await loadTutorial(target.dataset.id);
    } else if (action === "toggle-step") {
      const index = Number(target.dataset.index);
      const current = state.progress[state.selectedId] || [];
      state.progress[state.selectedId] = current.includes(index)
        ? current.filter((value) => value !== index)
        : [...current, index];
      saveProgress(state.progress);
      render();
    } else if (action === "reset-progress") {
      delete state.progress[state.selectedId];
      saveProgress(state.progress);
      render();
    }
  });

  render();
  try {
    state.tutorials = await api.listTutorials();
    const wanted = state.tutorials.find((item) => item.id === state.selectedId) || state.tutorials[0];
    if (!wanted) {
      state.loading = false;
      render();
    } else {
      await loadTutorial(wanted.id);
    }
  } catch (error) {
    state.loading = false;
    state.error = message(error, "Tutorials could not be loaded.");
    render();
  }
  return () => abort.abort();
}
