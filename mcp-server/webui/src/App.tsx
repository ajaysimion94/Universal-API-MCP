import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import { Topbar } from "./components/Topbar";
import { SearchPage } from "./components/ChatPage";
import { FilesPage } from "./components/FilesPage";
import { PluginsPage } from "./components/PluginsPage";
import { ConnectionsPage } from "./components/ConnectionsPage";
import { AppsPage } from "./components/AppsPage";
import { InsightPage } from "./components/InsightPage";
import { GuidePage } from "./components/GuidePage";

export default function App() {
  return (
    <BrowserRouter>
      <div className="app">
        <a className="skip-link" href="#main-content">Skip to main content</a>
        <Topbar />
        <div id="main-content" className="app-body" role="main">
          <Routes>
            <Route path="/" element={<SearchPage />} />
            <Route path="/files" element={<FilesPage />} />
            <Route path="/files/*" element={<FilesPage />} />
            <Route path="/plugins" element={<PluginsPage />} />
            <Route path="/connections" element={<ConnectionsPage />} />
            <Route path="/apps" element={<AppsPage />} />
            <Route path="/guide" element={<GuidePage />} />
            <Route path="/insights" element={<InsightPage />} />
            <Route path="/reports" element={<Navigate to="/insights" replace />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </div>
      </div>
    </BrowserRouter>
  );
}
