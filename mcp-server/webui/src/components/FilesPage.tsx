import { useCallback, useEffect, useRef, useState } from "react";
import {
  FileNode,
  fetchRoot,
  fetchChildren,
  fetchPath,
  createFolder,
  uploadFile,
  uploadFolder,
  deleteNode,
} from "../api";
import { Sidebar } from "./Sidebar";
import { Breadcrumbs } from "./Breadcrumbs";
import { FileTable } from "./FileTable";
import { PlusIcon, UploadIcon, FolderUploadIcon } from "../icons";

export function FilesPage() {
  const [root, setRoot] = useState<FileNode | null>(null);
  const [current, setCurrent] = useState<FileNode | null>(null);
  const [children, setChildren] = useState<FileNode[]>([]);
  const [path, setPath] = useState<FileNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [creatingFolder, setCreatingFolder] = useState(false);
  const [newFolderName, setNewFolderName] = useState("");

  const [dragOver, setDragOver] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadStatus, setUploadStatus] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const folderInputRef = useRef<HTMLInputElement>(null);
  const dragCounter = useRef(0);

  const loadFolder = useCallback(async (node: FileNode) => {
    setCurrent(node);
    setLoading(true);
    setError(null);
    setUploadStatus(null);
    try {
      const [kids, p] = await Promise.all([
        fetchChildren(node.id),
        fetchPath(node.id),
      ]);
      setChildren(kids);
      setPath(p);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load folder");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    (async () => {
      try {
        const r = await fetchRoot();
        setRoot(r);
        await loadFolder(r);
      } catch (e) {
        setError(e instanceof Error ? e.message : "Failed to connect");
        setLoading(false);
      }
    })();
  }, [loadFolder]);

  const refresh = useCallback(async () => {
    if (current) await loadFolder(current);
  }, [current, loadFolder]);

  const handleCreateFolder = async () => {
    const name = newFolderName.trim();
    if (!name || !current) return;
    try {
      await createFolder(current.id, name);
      setNewFolderName("");
      setCreatingFolder(false);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to create folder");
    }
  };

  const handleUpload = async (files: FileList | null) => {
    if (!files || files.length === 0 || !current) return;
    setUploading(true);
    setError(null);
    setUploadStatus(null);
    try {
      for (const file of Array.from(files)) {
        await uploadFile(current.id, file);
      }
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Upload failed");
    } finally {
      setUploading(false);
    }
  };

  const handleUploadFolder = async (files: FileList | null) => {
    if (!files || files.length === 0 || !current) return;
    setUploading(true);
    setError(null);
    setUploadStatus(null);
    try {
      const result = await uploadFolder(current.id, Array.from(files));
      await refresh();
      const parts: string[] = [];
      if (result.filesUploaded) parts.push(`${result.filesUploaded} uploaded`);
      if (result.foldersCreated) parts.push(`${result.foldersCreated} folders`);
      if (result.filesSkipped) parts.push(`${result.filesSkipped} skipped`);
      setUploadStatus(
        parts.length
          ? `Folder upload — ${parts.join(", ")}`
          : "Folder upload — nothing to do",
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : "Folder upload failed");
    } finally {
      setUploading(false);
    }
  };

  const handleDelete = async (node: FileNode) => {
    if (!confirm(`Delete "${node.name}"? This cannot be undone.`)) return;
    try {
      await deleteNode(node.id);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Delete failed");
    }
  };

  const onDragEnter = (e: React.DragEvent) => {
    if (!current) return;
    e.preventDefault();
    dragCounter.current++;
    if (e.dataTransfer.types.includes("Files")) setDragOver(true);
  };
  const onDragLeave = (e: React.DragEvent) => {
    e.preventDefault();
    dragCounter.current--;
    if (dragCounter.current === 0) setDragOver(false);
  };
  const onDragOver = (e: React.DragEvent) => {
    e.preventDefault();
  };
  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    dragCounter.current = 0;
    setDragOver(false);
    if (e.dataTransfer.files) handleUpload(e.dataTransfer.files);
  };

  if (!root) {
    return (
      <div className="boot">
        <span className="boot-mark">mcp</span>
        {error ? (
          <span className="boot-error">{error}</span>
        ) : (
          <span className="boot-loading">connecting…</span>
        )}
      </div>
    );
  }

  return (
    <div className="files-page">
      <Sidebar
        rootId={root.id}
        currentId={current?.id ?? root.id}
        onSelect={loadFolder}
      />
      <main
        className={`main ${dragOver ? "is-dragover" : ""}`}
        onDragEnter={onDragEnter}
        onDragLeave={onDragLeave}
        onDragOver={onDragOver}
        onDrop={onDrop}
      >
        <Breadcrumbs path={path} onNavigate={loadFolder} />

        <div className="toolbar">
          <div className="toolbar-title">
            <h1 className="folder-title">{current?.name ?? "My files"}</h1>
            <span className="folder-count mono">
              {children.length} {children.length === 1 ? "item" : "items"}
            </span>
          </div>
          <div className="toolbar-actions">
            <button
              className="btn btn-ghost"
              onClick={() => {
                setCreatingFolder(true);
                setNewFolderName("");
                setUploadStatus(null);
              }}
            >
              <PlusIcon size={14} />
              New folder
            </button>
            <button
              className="btn btn-ghost"
              onClick={() => folderInputRef.current?.click()}
              disabled={uploading}
              title="Upload a whole folder (with subfolders)"
            >
              <FolderUploadIcon size={14} />
              {uploading ? "Uploading…" : "Upload folder"}
            </button>
            <button
              className="btn btn-primary"
              onClick={() => fileInputRef.current?.click()}
              disabled={uploading}
            >
              <UploadIcon size={14} />
              {uploading ? "Uploading…" : "Upload"}
            </button>
            <input
              ref={fileInputRef}
              type="file"
              multiple
              className="file-input-hidden"
              onChange={(e) => {
                handleUpload(e.target.files);
                e.target.value = "";
              }}
            />
            <input
              ref={folderInputRef}
              type="file"
              multiple
              className="file-input-hidden"
              onChange={(e) => {
                handleUploadFolder(e.target.files);
                e.target.value = "";
              }}
              // @ts-expect-error — non-standard but widely supported attributes for folder upload
              webkitdirectory=""
              directory=""
            />
          </div>
        </div>

        {uploadStatus && !error && (
          <div className="status-banner" role="status">
            {uploadStatus}
            <button
              className="error-dismiss"
              onClick={() => setUploadStatus(null)}
              aria-label="Dismiss"
            >
              ×
            </button>
          </div>
        )}

        {error && (
          <div className="error-banner" role="alert">
            {error}
            <button
              className="error-dismiss"
              onClick={() => setError(null)}
              aria-label="Dismiss"
            >
              ×
            </button>
          </div>
        )}

        {loading ? (
          <div className="table-skeleton">
            {[0, 1, 2, 3, 4].map((i) => (
              <div key={i} className="skeleton-row" />
            ))}
          </div>
        ) : (
          <FileTable
            nodes={children}
            currentId={current?.id ?? root.id}
            onOpenFolder={loadFolder}
            onDelete={handleDelete}
            newFolderName={newFolderName}
            setNewFolderName={setNewFolderName}
            onCreateFolder={handleCreateFolder}
            onCancelFolder={() => {
              setCreatingFolder(false);
              setNewFolderName("");
            }}
            creatingFolder={creatingFolder}
          />
        )}

        {dragOver && (
          <div className="drop-overlay">
            <div className="drop-content">
              <UploadIcon size={28} />
              <span>Drop to upload into “{current?.name}”</span>
            </div>
          </div>
        )}
      </main>
    </div>
  );
}
