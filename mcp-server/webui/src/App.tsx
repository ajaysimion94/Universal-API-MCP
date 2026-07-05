import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import { Topbar } from "./components/Topbar";
import { SearchPage } from "./components/SearchPage";
import { FilesPage } from "./components/FilesPage";
import { PluginsPage } from "./components/PluginsPage";

export default function App() {
  return (
    <BrowserRouter>
      <div className="app">
        <Topbar />
        <div className="app-body">
          <Routes>
            <Route path="/" element={<SearchPage />} />
            <Route path="/files" element={<FilesPage />} />
            <Route path="/files/*" element={<FilesPage />} />
            <Route path="/plugins" element={<PluginsPage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </div>
      </div>
    </BrowserRouter>
  );
}
