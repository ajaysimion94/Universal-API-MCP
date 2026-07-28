import { NavLink, useLocation, useNavigate, useSearchParams } from "react-router-dom";
import { SearchIcon, HashIcon } from "../icons";

export function Topbar() {
  const navigate = useNavigate();
  const location = useLocation();
  const [params] = useSearchParams();
  const q = params.get("q") ?? "";

  return (
    <header className="topbar">
      <div className="topbar-left">
        <NavLink to="/" className="wordmark" aria-label="MCP workspace home">
          <span className="wordmark-mark">mcp</span>
          <span className="wordmark-divider">/</span>
        </NavLink>
        <nav className="topbar-nav" aria-label="Primary navigation">
          <NavLink to="/" end className={({ isActive }) => "nav-link" + (isActive ? " is-active" : "")}>
            Search
          </NavLink>
          <NavLink to="/files" className={({ isActive }) => "nav-link" + (isActive ? " is-active" : "")}>
            Files
          </NavLink>
          <NavLink to="/plugins" className={({ isActive }) => "nav-link" + (isActive ? " is-active" : "")}>
            Plugins
          </NavLink>
          <NavLink to="/connections" className={({ isActive }) => "nav-link" + (isActive ? " is-active" : "")}>
            Connections
          </NavLink>
          <NavLink to="/apps" className={({ isActive }) => "nav-link" + (isActive ? " is-active" : "")}>
            Apps
          </NavLink>
          <NavLink to="/insights" className={({ isActive }) => "nav-link" + (isActive ? " is-active" : "")}>
            Insights
          </NavLink>
          <NavLink to="/guide" className={({ isActive }) => "nav-link" + (isActive ? " is-active" : "")}>
            Guide
          </NavLink>
        </nav>
      </div>

      <form
        className={`search-field ${location.pathname === "/" ? "search-field-on-home" : ""}`}
        role="search"
        aria-label="Quick knowledge search"
        onSubmit={(e) => {
          e.preventDefault();
          const input = (e.currentTarget.elements.namedItem("q") as HTMLInputElement);
          if (input.value.trim()) navigate(`/?q=${encodeURIComponent(input.value.trim())}`);
        }}
      >
        <SearchIcon size={15} className="search-icon" />
        <input
          type="text"
          name="q"
          className="search-input"
          placeholder="Search — or type # for a tool"
          aria-label="Universal search"
          defaultValue={q}
          key={q}
        />
        <span className="search-hint">
          <HashIcon size={12} /> keyword
        </span>
      </form>
    </header>
  );
}
