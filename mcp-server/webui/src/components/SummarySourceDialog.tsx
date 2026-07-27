import { useEffect, useMemo, useState } from "react";
import {
  ConnectionInfo,
  FileNode,
  createSummaryExport,
  fetchFileTree,
  listConnections,
} from "../api";
import {
  CheckIcon,
  DownloadIcon,
  FileIcon,
  FolderIcon,
  LinkIcon,
  SearchIcon,
  XIcon,
} from "../icons";

interface ExportNotice {
  filename: string;
  sourceCount: number;
  chunkCount: number;
}

interface SummarySourceDialogProps {
  open: boolean;
  onClose: () => void;
  onExported: (notice: ExportNotice) => void;
}

function connectionTypeLabel(connection: ConnectionInfo): string {
  switch (connection.type) {
    case "CONFLUENCE":
      return "Confluence";
    case "JIRA":
      return "Jira";
    case "API_COLLECTION":
      return "API collection";
    case "GITHUB":
      return "GitHub";
    default:
      return "SharePoint";
  }
}

function filePath(node: FileNode, byId: Map<string, FileNode>): string {
  const parts = [node.name];
  let parentId = node.parentId;
  while (parentId) {
    const parent = byId.get(parentId);
    if (!parent) break;
    if (parent.id !== "root") parts.unshift(parent.name);
    parentId = parent.parentId;
  }
  return parts.join(" / ");
}

function formatBytes(size: number): string {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${Math.round(size / 1024)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

export function SummarySourceDialog({
  open,
  onClose,
  onExported,
}: SummarySourceDialogProps) {
  const [fileTree, setFileTree] = useState<FileNode[]>([]);
  const [connections, setConnections] = useState<ConnectionInfo[]>([]);
  const [selectedFiles, setSelectedFiles] = useState<Set<string>>(new Set());
  const [selectedConnections, setSelectedConnections] = useState<Set<string>>(new Set());
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setLoading(true);
    setError(null);
    Promise.all([fetchFileTree(), listConnections()])
      .then(([nextFiles, nextConnections]) => {
        setFileTree(nextFiles);
        setConnections(
          nextConnections.filter(
            (connection) =>
              connection.status === "CONNECTED" || connection.status === "DISABLED",
          ),
        );
      })
      .catch((reason) => {
        setError(reason instanceof Error ? reason.message : "Failed to load summary sources");
      })
      .finally(() => setLoading(false));
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !exporting) onClose();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [exporting, onClose, open]);

  const files = useMemo(
    () => fileTree.filter((node) => node.type === "FILE"),
    [fileTree],
  );
  const byId = useMemo(
    () => new Map(fileTree.map((node) => [node.id, node])),
    [fileTree],
  );
  const visibleFiles = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return files
      .map((node) => ({ node, path: filePath(node, byId) }))
      .filter(({ path }) => !needle || path.toLowerCase().includes(needle))
      .sort((a, b) => a.path.localeCompare(b.path));
  }, [byId, files, query]);

  if (!open) return null;

  const toggle = (
    id: string,
    selected: Set<string>,
    setSelected: (next: Set<string>) => void,
  ) => {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setSelected(next);
  };

  const selectedCount = selectedFiles.size + selectedConnections.size;
  const allVisibleFilesSelected =
    visibleFiles.length > 0 && visibleFiles.every(({ node }) => selectedFiles.has(node.id));

  const exportSelection = async () => {
    if (selectedCount === 0) return;
    setExporting(true);
    setError(null);
    try {
      const result = await createSummaryExport({
        fileIds: Array.from(selectedFiles),
        connectionIds: Array.from(selectedConnections),
      });
      const url = URL.createObjectURL(result.blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = result.filename;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
      onExported({
        filename: result.filename,
        sourceCount: result.sourceCount,
        chunkCount: result.chunkCount,
      });
      onClose();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Export failed");
    } finally {
      setExporting(false);
    }
  };

  return (
    <div
      className="summary-dialog-backdrop"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !exporting) onClose();
      }}
    >
      <section
        className="summary-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="summary-dialog-title"
        aria-describedby="summary-dialog-description"
      >
        <header className="summary-dialog-header">
          <div>
            <span className="summary-dialog-kicker mono">TXT EXPORT</span>
            <h2 id="summary-dialog-title">Choose summary sources</h2>
            <p id="summary-dialog-description">
              Select indexed files and connected apps. Only their RAG content is exported.
            </p>
          </div>
          <button
            type="button"
            className="summary-dialog-close"
            onClick={onClose}
            disabled={exporting}
            aria-label="Close source selection"
          >
            <XIcon size={16} />
          </button>
        </header>

        {error && (
          <div className="summary-dialog-error" role="alert">
            {error}
          </div>
        )}

        <div className="summary-dialog-grid">
          <section className="summary-source-section" aria-labelledby="summary-apps-title">
            <div className="summary-source-heading">
              <div>
                <h3 id="summary-apps-title">Connected apps</h3>
                <span className="mono">{connections.length} available</span>
              </div>
              {connections.length > 0 && (
                <button
                  type="button"
                  className="summary-select-action"
                  onClick={() => {
                    const allSelected = connections.every((item) =>
                      selectedConnections.has(item.id));
                    setSelectedConnections(
                      allSelected ? new Set() : new Set(connections.map((item) => item.id)),
                    );
                  }}
                >
                  {connections.every((item) => selectedConnections.has(item.id))
                    ? "Clear"
                    : "Select all"}
                </button>
              )}
            </div>

            <div className="summary-source-list">
              {loading ? (
                <div className="summary-source-empty">Loading apps…</div>
              ) : connections.length === 0 ? (
                <div className="summary-source-empty">
                  <LinkIcon size={16} />
                  No connected apps have been configured.
                </div>
              ) : (
                connections.map((connection) => {
                  const checked = selectedConnections.has(connection.id);
                  return (
                    <label
                      key={connection.id}
                      className={`summary-source-row ${checked ? "is-selected" : ""}`}
                    >
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={() =>
                          toggle(
                            connection.id,
                            selectedConnections,
                            setSelectedConnections,
                          )}
                      />
                      <span className="summary-source-check" aria-hidden="true">
                        {checked && <CheckIcon size={11} />}
                      </span>
                      <span className="summary-source-icon">
                        <LinkIcon size={15} />
                      </span>
                      <span className="summary-source-copy">
                        <strong>{connection.name}</strong>
                        <span>
                          {connectionTypeLabel(connection)} · {connection.status.toLowerCase()}
                        </span>
                      </span>
                    </label>
                  );
                })
              )}
            </div>
          </section>

          <section className="summary-source-section" aria-labelledby="summary-files-title">
            <div className="summary-source-heading">
              <div>
                <h3 id="summary-files-title">Files</h3>
                <span className="mono">{files.length} uploaded</span>
              </div>
              {visibleFiles.length > 0 && (
                <button
                  type="button"
                  className="summary-select-action"
                  onClick={() => {
                    const next = new Set(selectedFiles);
                    for (const { node } of visibleFiles) {
                      if (allVisibleFilesSelected) next.delete(node.id);
                      else next.add(node.id);
                    }
                    setSelectedFiles(next);
                  }}
                >
                  {allVisibleFilesSelected ? "Clear visible" : "Select visible"}
                </button>
              )}
            </div>

            <label className="summary-file-search">
              <SearchIcon size={14} />
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Filter by file or folder"
                aria-label="Filter files"
                autoFocus
              />
            </label>

            <div className="summary-source-list summary-file-list">
              {loading ? (
                <div className="summary-source-empty">Loading files…</div>
              ) : visibleFiles.length === 0 ? (
                <div className="summary-source-empty">
                  <FolderIcon size={16} />
                  {files.length === 0 ? "No uploaded files are available." : "No files match this filter."}
                </div>
              ) : (
                visibleFiles.map(({ node, path }) => {
                  const checked = selectedFiles.has(node.id);
                  return (
                    <label
                      key={node.id}
                      className={`summary-source-row ${checked ? "is-selected" : ""}`}
                    >
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={() => toggle(node.id, selectedFiles, setSelectedFiles)}
                      />
                      <span className="summary-source-check" aria-hidden="true">
                        {checked && <CheckIcon size={11} />}
                      </span>
                      <span className="summary-source-icon">
                        <FileIcon size={15} />
                      </span>
                      <span className="summary-source-copy">
                        <strong>{node.name}</strong>
                        <span className="mono">{path}</span>
                      </span>
                      <span className="summary-source-size mono">{formatBytes(node.size)}</span>
                    </label>
                  );
                })
              )}
            </div>
          </section>
        </div>

        <footer className="summary-dialog-footer">
          <div className="summary-selection-count">
            <strong className="mono">{selectedCount}</strong>
            <span>{selectedCount === 1 ? "source selected" : "sources selected"}</span>
          </div>
          <div className="summary-dialog-actions">
            <button type="button" className="btn btn-ghost" onClick={onClose} disabled={exporting}>
              Cancel
            </button>
            <button
              type="button"
              className="btn btn-primary"
              onClick={exportSelection}
              disabled={selectedCount === 0 || exporting}
            >
              <DownloadIcon size={14} />
              {exporting ? "Creating TXT…" : "Create TXT export"}
            </button>
          </div>
        </footer>
      </section>
    </div>
  );
}
