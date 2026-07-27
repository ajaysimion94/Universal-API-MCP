import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import { Topbar } from "./components/Topbar";
import { ChatPage } from "./components/ChatPage";
import { FilesPage } from "./components/FilesPage";
import { PluginsPage } from "./components/PluginsPage";
import { ConnectionsPage } from "./components/ConnectionsPage";
import { AppsPage } from "./components/AppsPage";
import { DashboardPage } from "./components/DashboardPage";

export default function App() {
  return (
    <BrowserRouter>
      <div className="app">
        <Topbar />
        <div className="app-body">
          <Routes>
            <Route path="/" element={<ChatPage />} />
            <Route path="/files" element={<FilesPage />} />
            <Route path="/files/*" element={<FilesPage />} />
            <Route path="/plugins" element={<PluginsPage />} />
            <Route path="/connections" element={<ConnectionsPage />} />
            <Route path="/apps" element={<AppsPage />} />
            <Route path="/dashboards" element={<DashboardPage />} />
            <Route path="/reports" element={<Navigate to="/dashboards" replace />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </div>
      </div>
    </BrowserRouter>
  );
}
