const SVG_PATHS = {
  search: '<circle cx="11" cy="11" r="7"></circle><path d="m20 20-4-4"></path>',
  plus: '<path d="M12 5v14M5 12h14"></path>',
  upload: '<path d="m12 16V4m0 0-5 5m5-5 5 5"></path><path d="M5 15v4h14v-4"></path>',
  folder: '<path d="M3 6h6l2 2h10v11H3z"></path>',
  file: '<path d="M6 2h8l4 4v16H6z"></path><path d="M14 2v5h5"></path>',
  trash: '<path d="M4 7h16M9 7V4h6v3m3 0-1 14H7L6 7M10 11v6m4-6v6"></path>',
  chevron: '<path d="m9 18 6-6-6-6"></path>',
  puzzle: '<path d="M19 13h-2.5a2.5 2.5 0 1 0 0 5H19v3H5v-5.5a2.5 2.5 0 1 0 0-5V5h5.5a2.5 2.5 0 1 1 5 0H19z"></path>',
  power: '<path d="M12 2v10"></path><path d="M6.3 5.7a8 8 0 1 0 11.4 0"></path>',
  check: '<circle cx="12" cy="12" r="9"></circle><path d="m8 12 2.5 2.5L16 9"></path>',
  alert: '<path d="M12 3 2.5 20h19z"></path><path d="M12 9v4m0 3h.01"></path>',
  globe: '<circle cx="12" cy="12" r="9"></circle><path d="M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18"></path>',
  book: '<path d="M4 4h6a3 3 0 0 1 3 3v13a3 3 0 0 0-3-3H4z"></path><path d="M20 4h-6a3 3 0 0 0-3 3v13a3 3 0 0 1 3-3h6z"></path>',
  download: '<path d="M12 3v12m0 0-5-5m5 5 5-5"></path><path d="M5 20h14"></path>',
  external: '<path d="M14 4h6v6M20 4l-9 9"></path><path d="M18 13v7H4V6h7"></path>',
  hash: '<path d="M10 3 8 21m8-18-2 18M4 9h16M3 15h16"></path>',
  at: '<circle cx="12" cy="12" r="4"></circle><path d="M16 8v5a3 3 0 0 0 6 0 10 10 0 1 0-3.5 7.6"></path>',
  close: '<path d="m6 6 12 12M18 6 6 18"></path>',
  play: '<path d="m8 5 11 7-11 7z"></path>',
  help: '<circle cx="12" cy="12" r="9"></circle><path d="M9.2 9.2a2.9 2.9 0 0 1 5.6 1c0 1.9-2.8 2.4-2.8 4"></path><path d="M12 17.5h.01"></path>',
  route: '<circle cx="6" cy="19" r="2.5"></circle><circle cx="18" cy="5" r="2.5"></circle><path d="M8.5 19h6a3.5 3.5 0 0 0 0-7h-5a3.5 3.5 0 0 1 0-7h6"></path>',
  thumbUp: '<path d="M7 21V10l4.5-7a2.2 2.2 0 0 1 2.1 2.9L12.5 10H18a2.2 2.2 0 0 1 2.1 2.8l-1.5 5.5A2.2 2.2 0 0 1 16.5 21z"></path><path d="M3 21h4V10H3z"></path>',
  thumbDown: '<path d="M7 3v11l4.5 7a2.2 2.2 0 0 0 2.1-2.9L12.5 14H18a2.2 2.2 0 0 0 2.1-2.8l-1.5-5.5A2.2 2.2 0 0 0 16.5 3z"></path><path d="M3 3h4v11H3z"></path>',
  chartBar: '<path d="M4 20V10m5 10V5m5 15v-7m5 7V8"></path>',
  chartLine: '<path d="M4 19V4"></path><path d="M4 19h16"></path><path d="m7 15 4-5 3 3 5-7"></path>',
  chartPie: '<path d="M12 3a9 9 0 1 0 9 9h-9z"></path><path d="M14.5 3.5A9 9 0 0 1 20.5 9.5h-6z"></path>',
  table: '<path d="M3 5h18v14H3z"></path><path d="M3 10h18M9 10v9"></path>',
  kpi: '<path d="M3 6h7v5H3z"></path><path d="M14 6h7v5h-7z"></path><path d="M3 15h18v3H3z"></path>',
  text: '<path d="M5 6h14M5 12h14M5 18h9"></path>',
  wand: '<path d="m4 20 9-9"></path><path d="M14 4.5 15 3l1 1.5L17.5 5l-1.5 1 .5 1.5L15 7l-1.5.5.5-1.5-1.5-1z"></path><path d="M13 11l3-3"></path>',
  arrowUp: '<path d="M12 20V5m0 0-6 6m6-6 6 6"></path>',
  arrowDown: '<path d="M12 4v15m0 0 6-6m-6 6-6-6"></path>',
  settings: '<circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.6v.2h-4V21a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9A1.7 1.7 0 0 0 3 14H2.8v-4H3a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1a1.7 1.7 0 0 0 1.9.3A1.7 1.7 0 0 0 10 3V2.8h4V3a1.7 1.7 0 0 0 1 1.6 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.6 1h.2v4H21a1.7 1.7 0 0 0-1.6 1z"></path>',
};

export function icon(name, size = 16, className = "") {
  const body = SVG_PATHS[name] || SVG_PATHS.file;
  return `<svg class="${escapeAttr(className)}" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${body}</svg>`;
}

export function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

export function escapeAttr(value) {
  return escapeHtml(value).replaceAll("`", "&#096;");
}

export function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return "—";
  const units = ["B", "KB", "MB", "GB"];
  const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** exponent).toFixed(exponent ? 1 : 0)} ${units[exponent]}`;
}

export function formatDate(value) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.valueOf()) ? "—" : date.toLocaleString();
}

export function message(error, fallback = "Something went wrong") {
  return error instanceof Error ? error.message : fallback;
}

export function statusClass(status) {
  if (["ACTIVE", "CONNECTED"].includes(status)) return "status-active";
  if (status === "ERROR" || status === "FAILED") return "status-error";
  if (status === "DISABLED") return "status-disabled";
  if (status === "INSTALLED") return "status-installed";
  return "status-default";
}

export function toggle(checked, label, action, id) {
  return `<label class="toggle">
    <input type="checkbox" ${checked ? "checked" : ""}
      data-action="${escapeAttr(action)}" data-id="${escapeAttr(id)}">
    <span class="toggle-track"><span class="toggle-thumb"></span></span>
    ${label ? `<span class="toggle-label">${escapeHtml(label)}</span>` : ""}
  </label>`;
}

export function banner(text, kind = "error") {
  return `<div class="${kind === "error" ? "error-banner" : "status-banner"}" role="${kind === "error" ? "alert" : "status"}">
    ${escapeHtml(text)}
    <button class="error-dismiss" type="button" data-action="dismiss-banner" aria-label="Dismiss">×</button>
  </div>`;
}

export function emptyState(title, body, action = "") {
  return `<div class="empty-state">
    <div class="empty-icon">${icon("folder", 28)}</div>
    <h2>${escapeHtml(title)}</h2>
    <p>${escapeHtml(body)}</p>
    ${action}
  </div>`;
}

export function skeletonRows(count = 5) {
  return `<div class="table-skeleton">${Array.from({ length: count }, () => '<div class="skeleton-row"></div>').join("")}</div>`;
}

export function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

export function on(root, event, selector, handler) {
  root.addEventListener(event, (nativeEvent) => {
    const target = nativeEvent.target.closest(selector);
    if (target && root.contains(target)) handler(nativeEvent, target);
  });
}

export function markdown(source) {
  const text = String(source ?? "");
  const inline = (value) => escapeHtml(value)
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/\*([^*]+)\*/g, "<em>$1</em>")
    .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a class="md-link" href="$2" target="_blank" rel="noopener noreferrer">$1</a>');

  const lines = text.split(/\r?\n/);
  const blocks = [];
  let paragraph = [];
  let list = [];
  let code = [];
  let inCode = false;
  const flushParagraph = () => {
    if (paragraph.length) blocks.push(`<p>${inline(paragraph.join(" "))}</p>`);
    paragraph = [];
  };
  const flushList = () => {
    if (list.length) blocks.push(`<ul>${list.map((item) => `<li>${inline(item)}</li>`).join("")}</ul>`);
    list = [];
  };
  lines.forEach((line) => {
    if (line.startsWith("```")) {
      flushParagraph();
      flushList();
      if (inCode) {
        blocks.push(`<pre><code>${escapeHtml(code.join("\n"))}</code></pre>`);
        code = [];
      }
      inCode = !inCode;
    } else if (inCode) {
      code.push(line);
    } else if (/^#{1,4}\s/.test(line)) {
      flushParagraph();
      flushList();
      const level = line.match(/^#+/)[0].length;
      blocks.push(`<h${level}>${inline(line.replace(/^#{1,4}\s+/, ""))}</h${level}>`);
    } else if (/^[-*]\s+/.test(line)) {
      flushParagraph();
      list.push(line.replace(/^[-*]\s+/, ""));
    } else if (!line.trim()) {
      flushParagraph();
      flushList();
    } else {
      paragraph.push(line.trim());
    }
  });
  flushParagraph();
  flushList();
  if (code.length) blocks.push(`<pre><code>${escapeHtml(code.join("\n"))}</code></pre>`);
  return blocks.join("");
}
