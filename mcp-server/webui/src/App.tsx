import {
  FormEvent,
  KeyboardEvent,
  useEffect,
  useMemo,
  useRef,
} from "react";
import {
  AppWindow,
  Cable,
  ChartNoAxesCombined,
  CircleHelp,
  Folder,
  Hash,
  Puzzle,
  Search,
  type LucideIcon,
} from "lucide-react";
import {
  Navigate,
  NavLink,
  Route,
  Routes,
  useLocation,
  useNavigate,
} from "react-router-dom";

type PageModule = {
  mount: (
    outlet: HTMLElement,
    context: {
      navigate: (url: string, replace?: boolean) => void;
      pathname: string;
      params: URLSearchParams;
    },
  ) => void | (() => void) | Promise<void | (() => void)>;
};

const navItems = [
  { path: "/", label: "Search", icon: Search },
  { path: "/files", label: "Files", icon: Folder },
  { path: "/plugins", label: "Settings", icon: Puzzle },
  { path: "/connections", label: "Connections", icon: Cable },
  { path: "/apps", label: "APIs", icon: AppWindow },
  { path: "/insights", label: "Insights", icon: ChartNoAxesCombined },
] satisfies ReadonlyArray<{ path: string; label: string; icon: LucideIcon }>;

const pageModules: Record<string, string> = {
  search: "/pages/search.js?v=ui-logic-3",
  files: "/pages/files.js?v=ui-logic-2",
  plugins: "/pages/plugins.js?v=ui-logic-4",
  connections: "/pages/connections.js?v=ui-logic-5",
  apps: "/pages/apps.js?v=ui-logic-5",
  insights: "/pages/insights.js?v=ui-logic-30",
  help: "/pages/help.js?v=ui-logic-2",
  tutorial: "/pages/tutorial.js?v=ui-logic-2",
};

function activeRoute(pathname: string) {
  if (pathname.startsWith("/files")) return "/files";
  if (pathname.startsWith("/plugins")) return "/plugins";
  if (pathname.startsWith("/connections")) return "/connections";
  if (pathname.startsWith("/apps")) return "/apps";
  if (
    pathname.startsWith("/insights") ||
    pathname.startsWith("/reports") ||
    pathname.startsWith("/dashboards")
  ) return "/insights";
  if (pathname.startsWith("/help") || pathname.startsWith("/tutorial") || pathname.startsWith("/guide")) {
    return "/help";
  }
  return "/";
}

function LegacyPage({ page }: { page: keyof typeof pageModules }) {
  const outletRef = useRef<HTMLElement>(null);
  const location = useLocation();
  const navigate = useNavigate();

  useEffect(() => {
    const outlet = outletRef.current;
    if (!outlet) return;

    let disposed = false;
    let cleanup: void | (() => void);
    outlet.innerHTML = '<div class="boot-loading" role="status">Loading workspace…</div>';
    // Route changes move keyboard/screen-reader focus to the new main region without scrolling.
    outlet.focus({ preventScroll: true });

    void import(/* @vite-ignore */ pageModules[page])
      .then((module: PageModule) => module.mount(outlet, {
        navigate: (url, replace = false) => navigate(url, { replace }),
        pathname: location.pathname,
        params: new URLSearchParams(location.search),
      }))
      .then((result) => {
        if (disposed) result?.();
        else cleanup = result;
      })
      .catch((error: unknown) => {
        if (disposed) return;
        const message = error instanceof Error ? error.message : "Page failed to load";
        outlet.replaceChildren();
        const failure = document.createElement("div");
        failure.className = "boot";
        failure.setAttribute("role", "alert");
        const mark = document.createElement("span");
        mark.className = "boot-mark";
        mark.textContent = "mcp";
        const detail = document.createElement("span");
        detail.className = "boot-error";
        detail.textContent = message;
        failure.append(mark, detail);
        outlet.append(failure);
      });

    return () => {
      disposed = true;
      cleanup?.();
      outlet.replaceChildren();
    };
  }, [location.pathname, location.search, navigate, page]);

  return <main id="main-content" className="app-body" ref={outletRef} tabIndex={-1} />;
}

export function App() {
  const location = useLocation();
  const navigate = useNavigate();
  const activePath = activeRoute(location.pathname);
  const query = useMemo(
    () => new URLSearchParams(location.search).get("q") || "",
    [location.search],
  );
  const helpActive = activePath === "/help";

  function runQuickSearch(value: FormDataEntryValue | null) {
    const nextQuery = String(value || "").trim();
    if (nextQuery) navigate(`/?q=${encodeURIComponent(nextQuery)}`);
  }

  function submitQuickSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    runQuickSearch(new FormData(event.currentTarget).get("q"));
  }

  function quickSearchKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key !== "Enter" || event.nativeEvent.isComposing) return;
    event.preventDefault();
    runQuickSearch(event.currentTarget.value);
  }

  return (
    <div className="app">
      <a className="skip-link" href="#main-content">Skip to main content</a>
      <header className="topbar d-flex align-items-center justify-content-between">
        <div className="topbar-left d-flex align-items-center">
          <NavLink to="/" className="wordmark" aria-label="MCP workspace home">
            <span className="wordmark-mark">mcp</span><span className="wordmark-divider">/</span>
          </NavLink>
          <nav className="topbar-nav d-flex gap-1" aria-label="Primary navigation">
            {navItems.map(({ path, label, icon: NavIcon }) => (
              <NavLink
                key={path}
                to={path}
                end={path === "/"}
                className={() => `nav-link ${activePath === path ? "is-active" : ""}`}
                aria-current={activePath === path ? "page" : undefined}
              >
                <NavIcon size={14} strokeWidth={1.75} aria-hidden="true" />
                {label}
              </NavLink>
            ))}
          </nav>
        </div>
        <div className="topbar-right d-flex align-items-center">
          <form
            className={`search-field d-flex align-items-center ${activePath === "/" ? "search-field-on-home" : ""}`}
            role="search"
            aria-label="Quick knowledge search"
            onSubmit={submitQuickSearch}
          >
            <span className="search-icon"><Search size={15} strokeWidth={1.75} aria-hidden="true" /></span>
            <input
              key={`${location.pathname}:${query}`}
              type="search"
              name="q"
              className="search-input form-control"
              placeholder="Search — or type # for a tool"
              aria-label="Universal search"
              defaultValue={query}
              onKeyDown={quickSearchKeyDown}
            />
            <span className="search-hint"><Hash size={12} strokeWidth={1.75} aria-hidden="true" /> keyword</span>
          </form>
          <NavLink
            to="/help"
            className={`topbar-help btn btn-sm btn-link p-0 ${helpActive ? "is-active" : ""}`}
            aria-label="Help and tutorials"
            title="Help and tutorials"
            aria-current={helpActive ? "page" : undefined}
          >
            <CircleHelp size={18} strokeWidth={1.75} aria-hidden="true" />
          </NavLink>
        </div>
      </header>

      <Routes>
        <Route path="/" element={<LegacyPage page="search" />} />
        <Route path="/files/*" element={<LegacyPage page="files" />} />
        <Route path="/plugins" element={<LegacyPage page="plugins" />} />
        <Route path="/connections" element={<LegacyPage page="connections" />} />
        <Route path="/apps" element={<LegacyPage page="apps" />} />
        <Route path="/insights/*" element={<LegacyPage page="insights" />} />
        <Route path="/help" element={<LegacyPage page="help" />} />
        <Route path="/tutorial" element={<LegacyPage page="tutorial" />} />
        <Route path="/guide" element={<Navigate to="/help" replace />} />
        <Route path="/reports/*" element={<Navigate to="/insights" replace />} />
        <Route path="/dashboards/*" element={<Navigate to="/insights" replace />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </div>
  );
}
