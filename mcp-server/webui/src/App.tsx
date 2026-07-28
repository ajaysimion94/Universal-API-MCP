import { lazy, Suspense } from "react";
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import { Topbar } from "./components/Topbar";
import { SearchPage } from "./components/ChatPage";

const FilesPage = lazy(() => import("./components/FilesPage")
  .then((module) => ({ default: module.FilesPage })));
const PluginsPage = lazy(() => import("./components/PluginsPage")
  .then((module) => ({ default: module.PluginsPage })));
const ConnectionsPage = lazy(() => import("./components/ConnectionsPage")
  .then((module) => ({ default: module.ConnectionsPage })));
const AppsPage = lazy(() => import("./components/AppsPage")
  .then((module) => ({ default: module.AppsPage })));
const InsightPage = lazy(() => import("./components/InsightPage")
  .then((module) => ({ default: module.InsightPage })));
const GuidePage = lazy(() => import("./components/GuidePage")
  .then((module) => ({ default: module.GuidePage })));

export default function App() {
  return (
    <BrowserRouter>
      <div className="app">
        <a className="skip-link" href="#main-content">Skip to main content</a>
        <Topbar />
        <div id="main-content" className="app-body" role="main">
          <Suspense fallback={<div className="boot-loading" role="status">Loading workspace…</div>}>
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
          </Suspense>
        </div>
      </div>
    </BrowserRouter>
  );
}
