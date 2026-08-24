import assert from "node:assert/strict";
import { afterEach, test } from "node:test";

import { api } from "../../src/main/resources/static/api.js";

const originalFetch = globalThis.fetch;
const covered = new Set();

afterEach(() => {
  globalThis.fetch = originalFetch;
});

function jsonResponse(body = { ok: true }, init = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
    ...init,
  });
}

function capture(responseFactory = () => jsonResponse()) {
  const requests = [];
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url: String(url), options });
    return responseFactory();
  };
  return requests;
}

function bodyOf(options) {
  return options.body === undefined ? undefined : JSON.parse(options.body);
}

const simpleCalls = [
  ["fetchRoot", [], "/api/files", "GET"],
  ["fetchFileTree", [], "/api/files/tree", "GET"],
  ["fetchChildren", ["folder 1"], "/api/files/folder 1/children", "GET"],
  ["fetchPath", ["folder 1"], "/api/files/folder 1/path", "GET"],
  ["fetchIngestionProgress", [], "/api/files/ingestion-progress", "GET"],
  ["createFolder", ["root", "Runbooks"], "/api/files/root/folders", "POST",
    { name: "Runbooks", owner: "you", visibility: "everyone" }],
  ["deleteNode", ["file-1"], "/api/files/file-1", "DELETE"],
  ["search", ["release plan", 5, true], "/api/search?q=release+plan&topK=5&web=true", "GET"],
  ["sendFeedback", ["imp-1", [{ signal: "OPEN" }]], "/api/search/feedback", "POST",
    { impressionId: "imp-1", events: [{ signal: "OPEN" }] }],
  ["fetchLearning", [], "/api/search/learning", "GET"],
  ["resetLearning", ["memory"], "/api/search/learning/reset", "POST", { scope: "memory" }],
  ["rebuildLearning", [], "/api/search/learning/rebuild", "POST", {}],
  ["listPlugins", [], "/api/plugins", "GET"],
  ["installPlugin", ["searxng"], "/api/plugins/searxng/install", "POST"],
  ["setupPlugin", ["searxng"], "/api/plugins/searxng/setup", "POST"],
  ["enablePlugin", ["searxng"], "/api/plugins/searxng/enable", "POST"],
  ["disablePlugin", ["searxng"], "/api/plugins/searxng/disable", "POST"],
  ["startPlugin", ["searxng"], "/api/plugins/searxng/start", "POST"],
  ["stopPlugin", ["searxng"], "/api/plugins/searxng/stop", "POST"],
  ["getPluginJob", ["job-1"], "/api/plugins/jobs/job-1", "GET"],
  ["listConnections", [], "/api/connections", "GET"],
  ["getConnection", ["conn-1"], "/api/connections/conn-1", "GET"],
  ["createConnection", [{ name: "Docs" }], "/api/connections", "POST", { name: "Docs" }],
  ["updateConnection", ["conn-1", { name: "Wiki" }], "/api/connections/conn-1", "PUT", { name: "Wiki" }],
  ["deleteConnection", ["conn-1"], "/api/connections/conn-1", "DELETE"],
  ["testConnection", ["conn-1"], "/api/connections/conn-1/test", "POST"],
  ["triggerBackfill", ["conn-1"], "/api/connections/conn-1/backfill", "POST"],
  ["enableConnection", ["conn-1"], "/api/connections/conn-1/enable", "POST"],
  ["disableConnection", ["conn-1"], "/api/connections/conn-1/disable", "POST"],
  ["getConnectionJob", ["job-2"], "/api/connections/jobs/job-2", "GET"],
  ["listTools", ["todo", "conn-1"], "/api/tools?query=todo&connectionId=conn-1", "GET"],
  ["enableTool", ["tool-1"], "/api/tools/tool-1/enable", "POST"],
  ["disableTool", ["tool-1"], "/api/tools/tool-1/disable", "POST"],
  ["setToolKnowledgeSource", ["tool-1", true], "/api/tools/tool-1/knowledge-source", "POST", { enabled: true }],
  ["previewTool", ["tool-1", { id: 7 }, { headers: { Trace: "1" } }], "/api/tools/tool-1/preview", "POST",
    { args: { id: 7 }, headers: { Trace: "1" } }],
  ["createManualTool", [{ displayName: "Ping" }], "/api/tools", "POST", { displayName: "Ping" }],
  ["updateManualTool", ["tool-1", { displayName: "Pong" }], "/api/tools/tool-1", "PUT", { displayName: "Pong" }],
  ["updateToolAuth", ["tool-1", { mode: "BEARER" }], "/api/tools/tool-1/auth", "PUT", { mode: "BEARER" }],
  ["deleteManualTool", ["tool-1"], "/api/tools/tool-1", "DELETE"],
  ["listGroups", [], "/api/groups", "GET"],
  ["getGroup", ["group-1"], "/api/groups/group-1", "GET"],
  ["createGroup", ["Operations", "Daily tools"], "/api/groups", "POST",
    { name: "Operations", description: "Daily tools" }],
  ["updateGroup", ["group-1", { name: "Ops" }], "/api/groups/group-1", "PUT", { name: "Ops" }],
  ["deleteGroup", ["group-1"], "/api/groups/group-1", "DELETE"],
  ["setGroupMembers", ["group-1", [{ type: "TOOL", id: "tool-1" }]], "/api/groups/group-1/members", "PUT",
    { members: [{ type: "TOOL", id: "tool-1" }] }],
  ["enableGroup", ["group-1"], "/api/groups/group-1/enable", "POST"],
  ["disableGroup", ["group-1"], "/api/groups/group-1/disable", "POST"],
  ["confirmTool", ["token/a"], "/api/tools/confirm/token%2Fa", "POST"],
  ["rejectTool", ["token/a"], "/api/tools/reject/token%2Fa", "POST"],
  ["fetchAuditLog", [{ status: "FAILED", limit: 10 }], "/api/audit?status=FAILED&limit=10", "GET"],
  ["fetchMetricsSummary", [], "/api/metrics/summary", "GET"],
  ["analyzeInsight", [{ source: "# Ops" }], "/api/insights/analyze", "POST", { source: "# Ops" }],
  ["loadInsightData", [{ source: "# Ops" }], "/api/insights/data", "POST", { source: "# Ops" }],
  ["listInsights", [], "/api/insights", "GET"],
  ["getInsight", ["insight-1"], "/api/insights/insight-1", "GET"],
  ["createInsight", [{ name: "Ops" }], "/api/insights", "POST", { name: "Ops" }],
  ["runInsight", ["insight-1", { params: { team: "core" } }], "/api/insights/insight-1/run", "POST",
    { params: { team: "core" } }],
  ["updateInsight", ["insight-1", { name: "Core ops" }], "/api/insights/insight-1", "PUT", { name: "Core ops" }],
  ["deleteInsight", ["insight-1"], "/api/insights/insight-1", "DELETE"],
  ["listHelpTopics", [], "/api/help", "GET"],
  ["getHelpTopic", ["search/tools"], "/api/help/search%2Ftools", "GET"],
  ["listTutorials", [], "/api/tutorials", "GET"],
  ["getTutorial", ["first/search"], "/api/tutorials/first%2Fsearch", "GET"],
];

for (const [name] of simpleCalls) covered.add(name);

test("every JSON and no-content API operation uses the documented route and payload", async () => {
  for (const [name, args, url, method, expectedBody] of simpleCalls) {
    const requests = capture();
    await api[name](...args);
    assert.equal(requests.length, 1, `${name} request count`);
    assert.equal(requests[0].url, url, `${name} URL`);
    assert.equal(requests[0].options.method || "GET", method, `${name} method`);
    assert.deepEqual(bodyOf(requests[0].options), expectedBody, `${name} body`);
    if (expectedBody !== undefined) {
      assert.equal(requests[0].options.headers["Content-Type"], "application/json", `${name} content type`);
    }
  }
});

for (const name of ["uploadFile", "uploadFolder", "createSummaryExport",
  "importSpecFile", "detectImportAuth", "invokeTool"]) covered.add(name);

test("file, folder, and specification uploads keep binary data in FormData", async () => {
  const requests = capture();
  const documentFile = new File(["runbook"], "runbook.txt", { type: "text/plain" });
  const nestedFile = new File(["nested"], "nested.txt", { type: "text/plain" });
  Object.defineProperty(nestedFile, "webkitRelativePath", { value: "ops/nested.txt" });
  await api.uploadFile("root", documentFile);
  await api.uploadFolder("root", [nestedFile]);
  await api.importSpecFile({
    file: documentFile,
    name: "Tasks",
    authMode: "NONE",
    baseUrl: "https://staging-api.example.test/v1",
    empty: "",
  });
  await api.detectImportAuth({ file: documentFile, specUrl: "https://example.test/openapi.json" });

  assert.deepEqual(requests.map((request) => [request.url, request.options.method]), [
    ["/api/files/root/upload", "POST"],
    ["/api/files/root/upload-folder", "POST"],
    ["/api/connections/import-spec", "POST"],
    ["/api/connections/detect-auth", "POST"],
  ]);
  assert.equal(requests[0].options.body.get("file"), documentFile);
  assert.equal(requests[1].options.body.get("paths"), "ops/nested.txt");
  assert.equal(requests[2].options.body.get("name"), "Tasks");
  assert.equal(requests[2].options.body.get("baseUrl"), "https://staging-api.example.test/v1");
  assert.equal(requests[2].options.body.has("empty"), false);
  assert.equal(requests[3].options.body.get("specUrl"), "https://example.test/openapi.json");
});

test("summary export preserves response metadata and binary output", async () => {
  globalThis.fetch = async () => new Response("evidence", {
    headers: {
      "Content-Disposition": "attachment; filename=knowledge.txt",
      "X-Export-Source-Count": "3",
      "X-Export-Chunk-Count": "12",
    },
  });

  const result = await api.createSummaryExport({ fileIds: ["file-1"] });

  assert.equal(await result.blob.text(), "evidence");
  assert.equal(result.filename, "knowledge.txt");
  assert.equal(result.sourceCount, 3);
  assert.equal(result.chunkCount, 12);
});

test("tool invocation merges overrides and surfaces schema violations", async () => {
  const requests = capture(() => jsonResponse({ status: 200 }));
  await api.invokeTool("tool-1", { id: 7 }, { bodyMode: "RAW" });
  assert.equal(requests[0].url, "/api/tools/tool-1/invoke");
  assert.deepEqual(bodyOf(requests[0].options), { args: { id: 7 }, bodyMode: "RAW" });

  globalThis.fetch = async () => jsonResponse({ violations: [{ message: "id is required" }] }, { status: 422 });
  await assert.rejects(() => api.invokeTool("tool-1", {}), /id is required/);
});

test("backend errors use the server message", async () => {
  globalThis.fetch = async () => jsonResponse({ error: "Plugin is unavailable" }, { status: 409 });
  await assert.rejects(() => api.setupPlugin("searxng"), /Plugin is unavailable/);
});

test("the wiring suite accounts for every exported browser API function", () => {
  assert.deepEqual([...covered].sort(), Object.keys(api).sort());
});
