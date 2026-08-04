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
    copied: "",
  };
  const abort = new AbortController();

  function done(stepIndex) {
    return Boolean(state.progress[state.selectedId]?.includes(stepIndex));
  }

  function completedCount() {
    return (state.progress[state.selectedId] || []).length;
  }

  function summaryOf(id) {
    return state.tutorials.find((item) => item.id === id);
  }

  function picker() {
    if (state.tutorials.length < 2) return "";
    return `<nav class="tutorial-picker" aria-label="Tutorials">${state.tutorials.map((item) => {
      const ticked = (state.progress[item.id] || []).length;
      return `<button type="button" class="tutorial-picker-item ${item.id === state.selectedId ? "is-active" : ""}" data-action="select-tutorial" data-id="${escapeAttr(item.id)}" ${item.id === state.selectedId ? 'aria-current="page"' : ""}>
        <span class="tutorial-picker-level" data-level="${escapeAttr((item.level || "").toLowerCase())}">${escapeHtml(item.level || "Walkthrough")}</span>
        <strong>${escapeHtml(item.title)}</strong>
        <small>${item.steps} steps · ${item.examples} examples · ${escapeHtml(item.duration)}</small>
        ${ticked ? `<span class="tutorial-picker-progress mono">${ticked}/${item.steps} done</span>` : ""}
      </button>`;
    }).join("")}</nav>`;
  }

  /**
   * An example is a labelled unit — what it is, why you would write it this way, the code, and what
   * the app should do back. The last part is what lets a reader check themselves rather than assume
   * a snippet worked.
   */
  function example(item, stepIndex, exampleIndex) {
    const key = `${stepIndex}:${exampleIndex}`;
    return `<figure class="tutorial-example">
      <figcaption>
        <span class="tutorial-example-label">${escapeHtml(item.label)}</span>
        ${item.language ? `<span class="tutorial-example-lang mono">${escapeHtml(item.language)}</span>` : ""}
        <button type="button" class="tutorial-example-copy" data-action="copy-example" data-key="${escapeAttr(key)}"
          aria-label="Copy this example">${state.copied === key ? `${icon("check", 13)} Copied` : "Copy"}</button>
      </figcaption>
      ${item.description ? `<p class="tutorial-example-note">${escapeHtml(item.description)}</p>` : ""}
      <pre class="tutorial-code"><code>${escapeHtml(item.code)}</code></pre>
      ${item.result ? `<p class="tutorial-example-result">${icon("chevron", 13)}<span>${escapeHtml(item.result)}</span></p>` : ""}
    </figure>`;
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
        ${item.examples?.length ? `<div class="tutorial-examples">${item.examples.map((entry, position) => example(entry, index, position)).join("")}</div>` : ""}
        ${item.verify ? `<p class="tutorial-verify">${icon("check", 14)} <span>${escapeHtml(item.verify)}</span></p>` : ""}
        ${item.note ? `<aside class="tutorial-note">${icon("alert", 14)}<span>${escapeHtml(item.note)}</span></aside>` : ""}
        ${item.troubleshooting?.length ? `<details class="tutorial-fixes"><summary>${icon("alert", 13)} If it does not work (${item.troubleshooting.length})</summary><dl>${item.troubleshooting.map((entry) => `<div><dt>${escapeHtml(entry.symptom)}</dt><dd>${escapeHtml(entry.fix)}</dd></div>`).join("")}</dl></details>` : ""}
        ${item.route ? `<a class="btn btn-sm tutorial-route" href="${escapeAttr(item.route)}" data-link>${escapeHtml(item.routeLabel || "Open")} ${icon("chevron", 14)}</a>` : ""}
      </div>
    </li>`;
  }

  function nextUp(complete, total) {
    const next = (state.tutorial.nextTutorials || []).map(summaryOf).filter(Boolean);
    if (!next.length) return "";
    return `<section class="tutorial-next" aria-label="What to read next">
      <h3>${complete === total && total ? "Where to go next" : "Related walkthroughs"}</h3>
      <div class="tutorial-next-grid">${next.map((item) => `<a class="tutorial-next-card" href="/tutorial?id=${encodeURIComponent(item.id)}" data-link data-action="select-tutorial" data-id="${escapeAttr(item.id)}">
        <strong>${escapeHtml(item.title)}</strong>
        <small>${escapeHtml(item.summary)}</small>
        <span class="mono">${item.steps} steps · ${escapeHtml(item.duration)}</span>
      </a>`).join("")}</div>
    </section>`;
  }

  function body() {
    if (state.loading) {
      return '<div class="help-article-skeleton" aria-label="Loading tutorial"><span></span><span></span><span></span><span></span></div>';
    }
    if (!state.tutorial) {
      return `<div class="tutorial-empty">${icon("route", 24)}<h2>No tutorial selected</h2><p>Pick a walkthrough to get started.</p></div>`;
    }
    const total = state.tutorial.steps.length;
    const complete = completedCount();
    const examples = state.tutorial.steps.reduce((count, item) => count + (item.examples?.length || 0), 0);
    return `<article class="tutorial-body">
      <header class="tutorial-body-header">
        <div>
          <span class="tutorial-body-level" data-level="${escapeAttr((state.tutorial.level || "").toLowerCase())}">${escapeHtml(state.tutorial.level || "Walkthrough")}</span>
          <h2>${escapeHtml(state.tutorial.title)}</h2>
          <p>${escapeHtml(state.tutorial.summary)}</p>
          <p class="tutorial-body-meta mono">${total} steps · ${examples} examples · ${escapeHtml(state.tutorial.duration)}</p>
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
      ${state.tutorial.prerequisites?.length ? `<aside class="tutorial-prereqs"><strong>Before you start</strong><ul>${state.tutorial.prerequisites.map((item) => `<li>${escapeHtml(item)}</li>`).join("")}</ul></aside>` : ""}
      <ol class="tutorial-steps">${state.tutorial.steps.map(step).join("")}</ol>
      ${complete === total && total ? `<div class="tutorial-complete">${icon("check", 18)}<div><strong>Walkthrough complete.</strong><p>Browse <a href="/help" data-link>Help</a> for the reference version of anything here.</p></div></div>` : ""}
      ${nextUp(complete, total)}
    </article>`;
  }

  function render() {
    outlet.innerHTML = `<section class="tutorial-page" aria-labelledby="tutorial-page-title">
      <header class="tutorial-page-header">
        <div>
          <p class="help-eyebrow">${icon("route", 15)} Tutorial</p>
          <h1 id="tutorial-page-title">Learn it by doing it.</h1>
          <p>Follow along with the app open — each step names where it happens, shows worked examples, and says how to tell it worked.</p>
        </div>
        <a class="btn" href="/help" data-link>${icon("help", 15)} Help</a>
      </header>
      ${state.error ? `<div class="help-error" role="alert">${icon("alert", 15)} ${escapeHtml(state.error)}</div>` : ""}
      <div class="tutorial-layout">
        ${picker()}
        <div class="tutorial-main">${body()}</div>
      </div>
    </section>`;
  }

  async function loadTutorial(id) {
    state.selectedId = id;
    state.tutorial = null;
    state.loading = true;
    state.copied = "";
    render();
    try {
      state.tutorial = await api.getTutorial(id);
      state.error = "";
    } catch (error) {
      state.error = message(error, "The tutorial could not be loaded.");
    }
    state.loading = false;
    render();
    outlet.querySelector(".tutorial-main")?.scrollIntoView({ block: "start" });
  }

  on(outlet, "click", "[data-action]", async (event, target) => {
    const { action } = target.dataset;
    if (action === "select-tutorial") {
      // The next-up cards are links as well as buttons, so the router must not also handle them.
      event.preventDefault();
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
    } else if (action === "copy-example") {
      // Read the rendered code rather than a data attribute so what is copied is what is shown.
      const code = target.closest(".tutorial-example")?.querySelector("code")?.textContent || "";
      try {
        await navigator.clipboard.writeText(code);
        state.copied = target.dataset.key;
        render();
      } catch {
        // Clipboard access can be refused; the code stays selectable on the page either way.
      }
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
