import { NavLink, useNavigate, useSearchParams } from "react-router-dom";
import { SearchIcon, HashIcon } from "../icons";

export function Topbar() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const q = params.get("q") ?? "";

  return (
    <header className="topbar">
      <div className="topbar-left">
        <div className="wordmark">
          <span className="wordmark-mark">mcp</span>
          <span className="wordmark-divider">/</span>
        </div>
        <nav className="topbar-nav">
          <NavLink to="/" end className={({ isActive }) => "nav-link" + (isActive ? " is-active" : "")}>
            Search
          </NavLink>
          <NavLink to="/files" className={({ isActive }) => "nav-link" + (isActive ? " is-active" : "")}>
            Files
          </NavLink>
        </nav>
      </div>

      <form
        className="search-field"
        role="search"
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
