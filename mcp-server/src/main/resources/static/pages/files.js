import { api } from "../api.js";
import {
  banner,
  emptyState,
  escapeAttr,
  escapeHtml,
  formatBytes,
  formatDate,
  icon,
  message,
  on,
  skeletonRows,
} from "../ui.js";

export async function mount(outlet) {
  const state = {
    root: null,
    current: null,
    children: [],
    path: [],
    tree: new Map(),
    expanded: new Set(),
    loading: true,
    error: "",
    notice: "",
    creatingFolder: false,
    uploading: false,
    progress: null,
    dragDepth: 0,
  };
  const abort = new AbortController();
  let pollTimer = 0;

  outlet.innerHTML = '<div class="boot"><span class="boot-mark">mcp</span><span class="boot-loading">connecting…</span></div>';

  function renderSidebarNode(node, depth = 0) {
    const expanded = state.expanded.has(node.id);
    const children = state.tree.get(node.id) || [];
    return `<div class="tree-node-wrap">
      <button type="button" class="tree-node ${node.id === state.current?.id ? "is-active" : ""}"
        style="--depth:${depth}" data-action="select-folder" data-id="${escapeAttr(node.id)}"
        aria-current="${node.id === state.current?.id ? "page" : "false"}">
        <span class="tree-chevron ${expanded ? "is-open" : ""}" data-action="toggle-folder" data-id="${escapeAttr(node.id)}">${icon("chevron", 13)}</span>
        ${icon("folder", 14, "tree-icon")}
        <span class="tree-label">${escapeHtml(node.name)}</span>
      </button>
      ${expanded ? `<div class="tree-children">${children.map((child) => renderSidebarNode(child, depth + 1)).join("")}</div>` : ""}
    </div>`;
  }

  function renderProgress() {
    if (!state.uploading && !state.progress?.active) return "";
    const progress = state.progress;
    const counting = ["embedding", "indexing"].includes(progress?.phase) && progress?.chunksTotal > 0;
    const percent = counting ? Math.round((progress.chunksDone / progress.chunksTotal) * 100) : 0;
    let label = state.uploading ? "Uploading…" : "Processing…";
    if (progress?.active && progress.fileName) {
      const file = progress.totalFiles > 1
        ? `${progress.fileName} (file ${progress.fileIndex} of ${progress.totalFiles})`
        : progress.fileName;
      label = `${progress.phase[0].toUpperCase()}${progress.phase.slice(1)} — ${file}`;
    }
    return `<div class="ingest-banner" role="status">
      <div class="ingest-info">
        <span class="ingest-label">${escapeHtml(label)}</span>
        ${counting ? `<span class="ingest-count mono">${progress.chunksDone}/${progress.chunksTotal} chunks · ${percent}%</span>` : ""}
      </div>
      <div class="ingest-track"><div class="ingest-fill ${counting ? "" : "ingest-indeterminate"}" ${counting ? `style="width:${percent}%"` : ""}></div></div>
    </div>`;
  }

  function renderTable() {
    if (state.loading) return skeletonRows();
    const createRow = state.creatingFolder ? `<tr class="file-row new-folder-row">
      <td class="file-name-cell">
        <span class="file-type-icon folder-icon">${icon("folder", 16)}</span>
        <form class="new-folder-form" id="new-folder-form">
          <input class="new-folder-input" name="name" aria-label="New folder name" placeholder="Folder name" autocomplete="off" autofocus>
          <button class="btn btn-primary btn-sm" type="submit">Create</button>
          <button class="btn btn-ghost btn-sm" type="button" data-action="cancel-folder">Cancel</button>
        </form>
      </td><td></td><td></td><td></td><td></td>
    </tr>` : "";

    if (!state.children.length && !state.creatingFolder) {
      return emptyState(
        "This folder is empty",
        "Upload documents or create a folder. Search indexing begins automatically.",
        '<button class="btn btn-primary" type="button" data-action="choose-files">' + icon("upload", 14) + " Upload files</button>",
      );
    }

    return `<div class="file-table-wrap"><table class="file-table">
      <thead><tr><th>Name</th><th>Owner</th><th>Size</th><th>Modified</th><th><span class="sr-only">Actions</span></th></tr></thead>
      <tbody>${createRow}${state.children.map((node) => `<tr class="file-row" tabindex="0" data-id="${escapeAttr(node.id)}" data-type="${node.type}">
        <td class="file-name-cell">
          <button type="button" class="file-name-button" data-action="${node.type === "FOLDER" ? "open-folder" : "noop"}" data-id="${escapeAttr(node.id)}">
            <span class="file-type-icon ${node.type === "FOLDER" ? "folder-icon" : ""}">${icon(node.type === "FOLDER" ? "folder" : "file", 16)}</span>
            <span class="file-name">${escapeHtml(node.name)}</span>
          </button>
        </td>
        <td class="file-meta">${escapeHtml(node.owner || "—")}</td>
        <td class="file-meta mono">${node.type === "FOLDER" ? "—" : formatBytes(node.size)}</td>
        <td class="file-meta">${escapeHtml(formatDate(node.updatedAt))}</td>
        <td class="file-actions-cell">
          <button type="button" class="icon-btn danger-hover" data-action="delete-node" data-id="${escapeAttr(node.id)}" aria-label="Delete ${escapeAttr(node.name)}">${icon("trash", 14)}</button>
        </td>
      </tr>`).join("")}</tbody>
    </table></div>`;
  }

  function render() {
    if (!state.root) return;
    outlet.innerHTML = `<div class="files-page">
      <aside class="sidebar" aria-label="Folder tree">
        <div class="sidebar-header"><span class="sidebar-title">Workspace</span></div>
        <div class="tree">${renderSidebarNode(state.root)}</div>
      </aside>
      <section class="main" aria-label="File browser" id="file-drop-zone">
        <nav class="breadcrumbs" aria-label="Breadcrumb">
          ${state.path.map((node, index) => `<span class="breadcrumb-item">
            ${index ? `<span class="breadcrumb-separator">${icon("chevron", 12)}</span>` : ""}
            <button type="button" class="breadcrumb-link ${index === state.path.length - 1 ? "is-current" : ""}" data-action="open-folder" data-id="${escapeAttr(node.id)}">${escapeHtml(index === 0 ? "My files" : node.name)}</button>
          </span>`).join("")}
        </nav>
        <div class="toolbar">
          <div class="toolbar-title">
            <h1 class="folder-title">${escapeHtml(state.current?.name || "My files")}</h1>
            <span class="folder-count mono">${state.children.length} ${state.children.length === 1 ? "item" : "items"}</span>
          </div>
          <div class="toolbar-actions">
            <button class="btn btn-ghost" type="button" data-action="new-folder">${icon("plus", 14)} New folder</button>
            <button class="btn btn-ghost" type="button" data-action="choose-folder" ${state.uploading ? "disabled" : ""}>${icon("folder", 14)} ${state.uploading ? "Uploading…" : "Upload folder"}</button>
            <button class="btn btn-primary" type="button" data-action="choose-files" ${state.uploading ? "disabled" : ""}>${icon("upload", 14)} ${state.uploading ? "Uploading…" : "Upload"}</button>
            <input id="file-input" type="file" multiple class="file-input-hidden" aria-label="Choose files to upload">
            <input id="folder-input" type="file" multiple webkitdirectory directory class="file-input-hidden" aria-label="Choose a folder to upload">
          </div>
        </div>
        ${renderProgress()}
        ${state.notice ? banner(state.notice, "status") : ""}
        ${state.error ? banner(state.error) : ""}
        <div id="file-content">${renderTable()}</div>
        <div class="drop-overlay" id="drop-overlay" hidden>
          <div class="drop-content">${icon("upload", 28)}<span>Drop to upload into “${escapeHtml(state.current?.name)}”</span></div>
        </div>
      </section>
    </div>`;
    outlet.querySelector("[autofocus]")?.focus();
  }

  async function expandFolder(id) {
    if (state.expanded.has(id)) {
      state.expanded.delete(id);
      render();
      return;
    }
    if (!state.tree.has(id)) {
      const children = (await api.fetchChildren(id)).filter((node) => node.type === "FOLDER");
      state.tree.set(id, children);
    }
    state.expanded.add(id);
    render();
  }

  async function loadFolder(nodeOrId) {
    const node = typeof nodeOrId === "string"
      ? [state.root, ...state.children, ...[...state.tree.values()].flat()].find((item) => item?.id === nodeOrId)
      : nodeOrId;
    if (!node) return;
    state.current = node;
    state.loading = true;
    state.error = "";
    state.notice = "";
    render();
    try {
      const [children, path] = await Promise.all([
        api.fetchChildren(node.id),
        api.fetchPath(node.id),
      ]);
      state.children = children;
      state.path = path;
      state.tree.set(node.id, children.filter((child) => child.type === "FOLDER"));
      state.expanded.add(node.id);
    } catch (error) {
      state.error = message(error, "Failed to load folder");
    } finally {
      state.loading = false;
      render();
    }
  }

  async function upload(files, folder = false) {
    if (!files.length || !state.current) return;
    state.uploading = true;
    state.error = "";
    state.notice = "";
    render();
    startPolling();
    try {
      if (folder) {
        const result = await api.uploadFolder(state.current.id, [...files]);
        const details = [];
        if (result.filesUploaded) details.push(`${result.filesUploaded} uploaded`);
        if (result.foldersCreated) details.push(`${result.foldersCreated} folders`);
        if (result.filesSkipped) details.push(`${result.filesSkipped} skipped`);
        state.notice = details.length ? `Folder upload — ${details.join(", ")}` : "Folder upload — nothing to do";
      } else {
        for (const file of files) await api.uploadFile(state.current.id, file);
      }
      await loadFolder(state.current);
    } catch (error) {
      state.error = message(error, "Upload failed");
    } finally {
      state.uploading = false;
      render();
    }
  }

  async function pollProgress() {
    try {
      state.progress = await api.fetchIngestionProgress();
      if (!state.progress.active && !state.uploading) {
        clearInterval(pollTimer);
        pollTimer = 0;
        state.progress = null;
      }
      render();
    } catch {
      // Queue progress is informational; transient failures do not interrupt work.
    }
  }

  function startPolling() {
    pollProgress();
    if (!pollTimer) pollTimer = window.setInterval(pollProgress, 500);
  }

  on(outlet, "click", "[data-action]", async (event, target) => {
    const { action, id } = target.dataset;
    if (action === "dismiss-banner") {
      state.error = "";
      state.notice = "";
      render();
    } else if (action === "new-folder") {
      state.creatingFolder = true;
      state.notice = "";
      render();
    } else if (action === "cancel-folder") {
      state.creatingFolder = false;
      render();
    } else if (action === "choose-files") {
      outlet.querySelector("#file-input")?.click();
    } else if (action === "choose-folder") {
      outlet.querySelector("#folder-input")?.click();
    } else if (action === "open-folder" || action === "select-folder") {
      await loadFolder(id);
    } else if (action === "toggle-folder") {
      event.stopPropagation();
      await expandFolder(id);
    } else if (action === "delete-node") {
      event.stopPropagation();
      const node = state.children.find((item) => item.id === id);
      if (!node || !confirm(`Delete "${node.name}"? This cannot be undone.`)) return;
      try {
        await api.deleteNode(id);
        await loadFolder(state.current);
      } catch (error) {
        state.error = message(error, "Delete failed");
        render();
      }
    }
  });

  outlet.addEventListener("submit", async (event) => {
    if (event.target.id !== "new-folder-form") return;
    event.preventDefault();
    const name = new FormData(event.target).get("name")?.trim();
    if (!name || !state.current) return;
    try {
      await api.createFolder(state.current.id, name);
      state.creatingFolder = false;
      await loadFolder(state.current);
    } catch (error) {
      state.error = message(error, "Failed to create folder");
      render();
    }
  }, { signal: abort.signal });

  outlet.addEventListener("change", (event) => {
    if (event.target.id === "file-input") upload(event.target.files, false);
    if (event.target.id === "folder-input") upload(event.target.files, true);
  }, { signal: abort.signal });

  outlet.addEventListener("dragenter", (event) => {
    if (!event.dataTransfer?.types.includes("Files")) return;
    event.preventDefault();
    state.dragDepth += 1;
    outlet.querySelector("#drop-overlay")?.removeAttribute("hidden");
  }, { signal: abort.signal });
  outlet.addEventListener("dragover", (event) => event.preventDefault(), { signal: abort.signal });
  outlet.addEventListener("dragleave", (event) => {
    event.preventDefault();
    state.dragDepth -= 1;
    if (state.dragDepth <= 0) {
      state.dragDepth = 0;
      outlet.querySelector("#drop-overlay")?.setAttribute("hidden", "");
    }
  }, { signal: abort.signal });
  outlet.addEventListener("drop", (event) => {
    event.preventDefault();
    state.dragDepth = 0;
    outlet.querySelector("#drop-overlay")?.setAttribute("hidden", "");
    if (event.dataTransfer?.files) upload(event.dataTransfer.files, false);
  }, { signal: abort.signal });
  outlet.addEventListener("keydown", (event) => {
    const row = event.target.closest(".file-row[data-id]");
    if (row && event.key === "Enter" && row.dataset.type === "FOLDER") loadFolder(row.dataset.id);
  }, { signal: abort.signal });

  try {
    state.root = await api.fetchRoot();
    state.tree.set(state.root.id, []);
    await loadFolder(state.root);
    startPolling();
  } catch (error) {
    outlet.innerHTML = `<div class="boot"><span class="boot-mark">mcp</span><span class="boot-error">${escapeHtml(message(error, "Failed to connect"))}</span></div>`;
  }

  return () => {
    abort.abort();
    clearInterval(pollTimer);
  };
}
