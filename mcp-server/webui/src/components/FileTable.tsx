import { FileNode } from "../api";
import { FolderIcon, FileIcon, TrashIcon } from "../icons";

interface FileTableProps {
  nodes: FileNode[];
  currentId: string;
  onOpenFolder: (node: FileNode) => void;
  onDelete: (node: FileNode) => void;
  newFolderName: string;
  setNewFolderName: (v: string) => void;
  onCreateFolder: () => void;
  onCancelFolder: () => void;
  creatingFolder: boolean;
}

function formatSize(bytes: number): string {
  if (bytes === 0) return "—";
  const units = ["B", "KB", "MB", "GB"];
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  const val = bytes / Math.pow(1024, i);
  return `${val >= 100 ? Math.round(val) : val.toFixed(1)} ${units[i]}`;
}

function relativeTime(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const sec = Math.floor((Date.now() - then) / 1000);
  if (sec < 60) return "just now";
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const day = Math.floor(hr / 24);
  if (day < 30) return `${day}d ago`;
  return new Date(iso).toLocaleDateString();
}

export function FileTable({
  nodes,
  currentId,
  onOpenFolder,
  onDelete,
  newFolderName,
  setNewFolderName,
  onCreateFolder,
  onCancelFolder,
  creatingFolder,
}: FileTableProps) {
  const folders = nodes.filter((n) => n.type === "FOLDER");
  const files = nodes.filter((n) => n.type === "FILE");
  const ordered = [...folders, ...files];

  return (
    <div className="table-wrap" role="table" aria-label="Files and folders">
      <div className="table-head" role="row">
        <span className="col-name" role="columnheader">Name</span>
        <span className="col-owner" role="columnheader">Owner</span>
        <span className="col-size" role="columnheader">Size</span>
        <span className="col-modified" role="columnheader">Modified</span>
        <span className="col-actions" />
      </div>

      <div className="table-body" role="rowgroup">
        {creatingFolder && (
          <div className="new-folder-row" role="row">
            <span className="col-name">
              <FolderIcon size={15} className="row-icon folder" />
              <input
                autoFocus
                className="new-folder-input"
                value={newFolderName}
                onChange={(e) => setNewFolderName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") onCreateFolder();
                  if (e.key === "Escape") onCancelFolder();
                }}
                onBlur={() => {
                  if (!newFolderName.trim()) onCancelFolder();
                }}
                placeholder="Folder name"
                aria-label="New folder name"
              />
            </span>
            <span className="col-owner" />
            <span className="col-size" />
            <span className="col-modified" />
            <span className="col-actions">
              <button
                className="row-action"
                onClick={onCreateFolder}
                aria-label="Create folder"
              >
                <span className="row-action-label">Add</span>
              </button>
            </span>
          </div>
        )}

        {ordered.length === 0 && !creatingFolder && (
          <div className="table-empty">
            <span className="empty-line">This folder is empty.</span>
            <span className="empty-hint">
              Drag files here, or use New folder / Upload above.
            </span>
          </div>
        )}

        {ordered.map((node) => {
          const isFolder = node.type === "FOLDER";
          const isRoot = node.id === currentId && node.parentId === null;
          return (
            <div
              key={node.id}
              className="table-row"
              role="row"
              onClick={() => isFolder && onOpenFolder(node)}
              tabIndex={isFolder ? 0 : -1}
              onKeyDown={(e) => {
                if (isFolder && (e.key === "Enter" || e.key === " ")) {
                  e.preventDefault();
                  onOpenFolder(node);
                }
              }}
            >
              <span className="col-name">
                {isFolder ? (
                  <FolderIcon size={15} className="row-icon folder" />
                ) : (
                  <FileIcon size={15} className="row-icon file" />
                )}
                <span className="row-name">{node.name}</span>
              </span>
              <span className="col-owner mono">{node.owner}</span>
              <span className="col-size mono">
                {isFolder ? "—" : formatSize(node.size)}
              </span>
              <span className="col-modified mono">
                {relativeTime(node.updatedAt)}
              </span>
              <span className="col-actions">
                {!isRoot && (
                  <button
                    className="row-action"
                    onClick={(e) => {
                      e.stopPropagation();
                      onDelete(node);
                    }}
                    aria-label={`Delete ${node.name}`}
                    title="Delete"
                  >
                    <TrashIcon size={14} />
                  </button>
                )}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
