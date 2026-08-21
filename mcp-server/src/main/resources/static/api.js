const FILES = "/api/files";
const PLUGINS = "/api/plugins";
const CONNECTIONS = "/api/connections";
const TOOLS = "/api/tools";
const GROUPS = "/api/groups";

async function parseError(response, fallback = "Request failed") {
  const body = await response.json().catch(() => ({}));
  return new Error(body.error || body.message || `${fallback} (${response.status})`);
}

async function json(response) {
  if (!response.ok) throw await parseError(response);
  return response.json();
}

async function noContent(response, fallback) {
  if (!response.ok && response.status !== 204) throw await parseError(response, fallback);
}

async function send(url, method = "GET", body) {
  const options = { method };
  if (body !== undefined) {
    options.headers = { "Content-Type": "application/json" };
    options.body = JSON.stringify(body);
  }
  return json(await fetch(url, options));
}

function queryUrl(path, values) {
  const params = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") params.set(key, String(value));
  });
  const query = params.toString();
  return query ? `${path}?${query}` : path;
}

export const api = {
  fetchRoot: () => send(FILES),
  fetchFileTree: () => send(`${FILES}/tree`),
  fetchChildren: (id) => send(`${FILES}/${id}/children`),
  fetchPath: (id) => send(`${FILES}/${id}/path`),
  fetchIngestionProgress: () => send(`${FILES}/ingestion-progress`),
  createFolder: (parentId, name) =>
    send(`${FILES}/${parentId}/folders`, "POST", {
      name,
      owner: "you",
      visibility: "everyone",
    }),
  async uploadFile(parentId, file) {
    const form = new FormData();
    form.append("file", file);
    return json(await fetch(`${FILES}/${parentId}/upload`, { method: "POST", body: form }));
  },
  async uploadFolder(parentId, files) {
    const form = new FormData();
    files.forEach((file) => {
      form.append("files", file);
      form.append("paths", file.webkitRelativePath || file.name);
    });
    return json(await fetch(`${FILES}/${parentId}/upload-folder`, { method: "POST", body: form }));
  },
  deleteNode: async (id) =>
    noContent(await fetch(`${FILES}/${id}`, { method: "DELETE" }), "Delete failed"),

  search: (query, topK = 20, web = false) =>
    send(queryUrl("/api/search", { q: query, topK, web })),

  // Adaptive ranking. sendFeedback is fire-and-forget from the caller's point of view — the server
  // answers 200 even for an unknown impression, so a stale localStorage turn can never surface an
  // error banner in a page the user is reading.
  sendFeedback: (impressionId, events) =>
    send("/api/search/feedback", "POST", { impressionId, events }),
  fetchLearning: () => send("/api/search/learning"),
  resetLearning: (scope) => send("/api/search/learning/reset", "POST", { scope }),
  rebuildLearning: () => send("/api/search/learning/rebuild", "POST", {}),
  async createSummaryExport(selection) {
    const response = await fetch("/api/summary-exports", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(selection),
    });
    if (!response.ok) throw await parseError(response, "Export failed");
    const disposition = response.headers.get("Content-Disposition") || "";
    return {
      blob: await response.blob(),
      filename: /filename="?([^";]+)"?/i.exec(disposition)?.[1] || "mcp-knowledge-export.txt",
      sourceCount: Number(response.headers.get("X-Export-Source-Count") || 0),
      chunkCount: Number(response.headers.get("X-Export-Chunk-Count") || 0),
    };
  },

  listPlugins: () => send(PLUGINS),
  listOnnxModels: () => send(`${PLUGINS}/models`),
  async uploadOnnxModel(kind, model, tokenizer) {
    const form = new FormData();
    form.append("model", model);
    form.append("tokenizer", tokenizer);
    return json(await fetch(`${PLUGINS}/models/${encodeURIComponent(kind)}`, {
      method: "POST",
      body: form,
    }));
  },
  installPlugin: (id) => send(`${PLUGINS}/${id}/install`, "POST"),
  enablePlugin: (id) => send(`${PLUGINS}/${id}/enable`, "POST"),
  disablePlugin: (id) => send(`${PLUGINS}/${id}/disable`, "POST"),
  startPlugin: (id) => send(`${PLUGINS}/${id}/start`, "POST"),
  stopPlugin: (id) => send(`${PLUGINS}/${id}/stop`, "POST"),
  getPluginJob: (id) => send(`${PLUGINS}/jobs/${id}`),

  listConnections: () => send(CONNECTIONS),
  getConnection: (id) => send(`${CONNECTIONS}/${id}`),
  createConnection: (input) => send(CONNECTIONS, "POST", input),
  updateConnection: (id, input) => send(`${CONNECTIONS}/${id}`, "PUT", input),
  deleteConnection: async (id) =>
    noContent(await fetch(`${CONNECTIONS}/${id}`, { method: "DELETE" }), "Delete failed"),
  triggerBackfill: (id) => send(`${CONNECTIONS}/${id}/backfill`, "POST"),
  enableConnection: (id) => send(`${CONNECTIONS}/${id}/enable`, "POST"),
  disableConnection: (id) => send(`${CONNECTIONS}/${id}/disable`, "POST"),
  getConnectionJob: (id) => send(`${CONNECTIONS}/jobs/${id}`),
  async importSpecFile(input) {
    const form = new FormData();
    Object.entries(input).forEach(([key, value]) => {
      if (value !== undefined && value !== "") form.append(key, value);
    });
    return json(await fetch(`${CONNECTIONS}/import-spec`, { method: "POST", body: form }));
  },
  async detectImportAuth(input) {
    const form = new FormData();
    if (input.file) form.append("file", input.file);
    if (input.specUrl) form.append("specUrl", input.specUrl);
    return json(await fetch(`${CONNECTIONS}/detect-auth`, { method: "POST", body: form }));
  },

  listTools: (query, connectionId) =>
    send(queryUrl(TOOLS, { query, connectionId })),
  enableTool: (id) => send(`${TOOLS}/${id}/enable`, "POST"),
  disableTool: (id) => send(`${TOOLS}/${id}/disable`, "POST"),
  setToolKnowledgeSource: (id, enabled) =>
    send(`${TOOLS}/${id}/knowledge-source`, "POST", { enabled }),
  async invokeTool(id, args, overrides = {}) {
    const response = await fetch(`${TOOLS}/${id}/invoke`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ args, ...overrides }),
    });
    if (response.status === 422) {
      const body = await response.json().catch(() => ({}));
      const message = body.violations?.map((violation) => violation.message).join("; ");
      throw new Error(message || "Arguments violate the tool schema");
    }
    return json(response);
  },
  previewTool: (id, args, overrides = {}) =>
    send(`${TOOLS}/${id}/preview`, "POST", { args, ...overrides }),
  createManualTool: (input) => send(TOOLS, "POST", input),
  updateManualTool: (id, input) => send(`${TOOLS}/${id}`, "PUT", input),
  updateToolAuth: (id, input) => send(`${TOOLS}/${id}/auth`, "PUT", input),
  deleteManualTool: async (id) =>
    noContent(await fetch(`${TOOLS}/${id}`, { method: "DELETE" }), "Delete failed"),

  listGroups: () => send(GROUPS),
  getGroup: (id) => send(`${GROUPS}/${id}`),
  createGroup: (name, description) => send(GROUPS, "POST", { name, description }),
  updateGroup: (id, input) => send(`${GROUPS}/${id}`, "PUT", input),
  deleteGroup: async (id) =>
    noContent(await fetch(`${GROUPS}/${id}`, { method: "DELETE" }), "Delete failed"),
  setGroupMembers: async (id, members) =>
    noContent(await fetch(`${GROUPS}/${id}/members`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ members }),
    })),
  enableGroup: (id) => send(`${GROUPS}/${id}/enable`, "POST"),
  disableGroup: (id) => send(`${GROUPS}/${id}/disable`, "POST"),

  confirmTool: (token) => send(`${TOOLS}/confirm/${encodeURIComponent(token)}`, "POST"),
  rejectTool: (token) => send(`${TOOLS}/reject/${encodeURIComponent(token)}`, "POST"),
  fetchAuditLog: (options = {}) => send(queryUrl("/api/audit", options)),
  fetchMetricsSummary: () => send("/api/metrics/summary"),

  analyzeInsight: (input) => send("/api/insights/analyze", "POST", input),
  loadInsightData: (input) => send("/api/insights/data", "POST", input),
  listInsights: () => send("/api/insights"),
  getInsight: (id) => send(`/api/insights/${id}`),
  createInsight: (input) => send("/api/insights", "POST", input),
  // Runs a saved insight and keeps the result on it; /api/insights/data stays the draft path.
  runInsight: (id, input) => send(`/api/insights/${id}/run`, "POST", input),
  updateInsight: (id, input) => send(`/api/insights/${id}`, "PUT", input),
  deleteInsight: async (id) =>
    noContent(await fetch(`/api/insights/${id}`, { method: "DELETE" }), "Delete failed"),

  listHelpTopics: () => send("/api/help"),
  getHelpTopic: (id) => send(`/api/help/${encodeURIComponent(id)}`),
  listTutorials: () => send("/api/tutorials"),
  getTutorial: (id) => send(`/api/tutorials/${encodeURIComponent(id)}`),
};
