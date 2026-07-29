import { icon, escapeHtml } from "./ui.js";

const routes = {
  "/": () => import("./pages/search.js"),
  "/files": () => import("./pages/files.js"),
  "/plugins": () => import("./pages/plugins.js"),
  "/connections": () => import("./pages/connections.js?v=vanilla-3"),
  "/apps": () => import("./pages/apps.js?v=vanilla-4"),
  "/insights": () => import("./pages/insights.js"),
  "/guide": () => import("./pages/guide.js"),
};

const navItems = [
  ["/", "Search"],
  ["/files", "Files"],
  ["/plugins", "Plugins"],
  ["/connections", "Connections"],
  ["/apps", "Apps"],
  ["/insights", "Insights"],
  ["/guide", "Guide"],
];

let cleanupPage = null;
let routeVersion = 0;

function routeKey(pathname) {
  if (pathname.startsWith("/files")) return "/files";
  if (pathname.startsWith("/insights")) return "/insights";
  if (pathname.startsWith("/reports") || pathname.startsWith("/dashboards")) return "/insights";
  return routes[pathname] ? pathname : "/";
}

function shell(activePath) {
  const query = new URLSearchParams(location.search).get("q") || "";
  return `<div class="app">
    <a class="skip-link" href="#main-content">Skip to main content</a>
    <header class="topbar">
      <div class="topbar-left">
        <a href="/" class="wordmark" data-link aria-label="MCP workspace home">
          <span class="wordmark-mark">mcp</span><span class="wordmark-divider">/</span>
        </a>
        <nav class="topbar-nav" aria-label="Primary navigation">
          ${navItems.map(([path, label]) => `<a href="${path}" data-link class="nav-link ${path === activePath ? "is-active" : ""}" ${path === activePath ? 'aria-current="page"' : ""}>${label}</a>`).join("")}
        </nav>
      </div>
      <form class="search-field ${activePath === "/" ? "search-field-on-home" : ""}" id="quick-search" role="search" aria-label="Quick knowledge search">
        ${icon("search", 15, "search-icon")}
        <input type="search" name="q" class="search-input" placeholder="Search — or type # for a tool"
          aria-label="Universal search" value="${escapeHtml(query)}">
        <span class="search-hint">${icon("hash", 12)} keyword</span>
      </form>
    </header>
    <main id="main-content" class="app-body">
      <div class="boot-loading" role="status">Loading workspace…</div>
    </main>
  </div>`;
}

export function navigate(url, replace = false) {
  const target = new URL(url, location.origin);
  history[replace ? "replaceState" : "pushState"]({}, "", `${target.pathname}${target.search}${target.hash}`);
  renderRoute();
}

async function renderRoute() {
  const version = ++routeVersion;
  cleanupPage?.();
  cleanupPage = null;

  const key = routeKey(location.pathname);
  if (key !== location.pathname && (
    location.pathname.startsWith("/reports") ||
    location.pathname.startsWith("/dashboards") ||
    !routes[location.pathname]
  )) {
    history.replaceState({}, "", key === "/" ? "/" : key);
  }

  document.getElementById("root").innerHTML = shell(key);
  const outlet = document.getElementById("main-content");
  try {
    const module = await routes[key]();
    if (version !== routeVersion) return;
    cleanupPage = await module.mount(outlet, {
      navigate,
      pathname: location.pathname,
      params: new URLSearchParams(location.search),
    });
  } catch (error) {
    if (version !== routeVersion) return;
    outlet.innerHTML = `<div class="boot"><span class="boot-mark">mcp</span><span class="boot-error">${escapeHtml(error instanceof Error ? error.message : "Page failed to load")}</span></div>`;
    console.error(error);
  }
}

document.addEventListener("click", (event) => {
  const link = event.target.closest("a[data-link]");
  if (!link || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
  const url = new URL(link.href);
  if (url.origin !== location.origin) return;
  event.preventDefault();
  navigate(`${url.pathname}${url.search}${url.hash}`);
});

document.addEventListener("submit", (event) => {
  if (event.target.id !== "quick-search") return;
  event.preventDefault();
  const query = new FormData(event.target).get("q")?.trim();
  if (query) navigate(`/?q=${encodeURIComponent(query)}`);
});

window.addEventListener("popstate", renderRoute);
renderRoute();
