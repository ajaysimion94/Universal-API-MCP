export type NodeType = "FOLDER" | "FILE";

export interface FileNode {
  id: string;
  parentId: string | null;
  name: string;
  type: NodeType;
  size: number;
  mimeType: string | null;
  owner: string;
  visibility: string;
  createdAt: string;
  updatedAt: string;
  version: number;
}

const API = "/api/files";

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: res.statusText }));
    throw new Error(body.error ?? `Request failed (${res.status})`);
  }
  return res.json() as Promise<T>;
}

export async function fetchRoot(): Promise<FileNode> {
  return json<FileNode>(await fetch(API));
}

export async function fetchChildren(id: string): Promise<FileNode[]> {
  return json<FileNode[]>(await fetch(`${API}/${id}/children`));
}

export async function fetchPath(id: string): Promise<FileNode[]> {
  return json<FileNode[]>(await fetch(`${API}/${id}/path`));
}

export async function createFolder(
  parentId: string,
  name: string,
): Promise<FileNode> {
  return json<FileNode>(
    await fetch(`${API}/${parentId}/folders`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name, owner: "you", visibility: "everyone" }),
    }),
  );
}

export async function uploadFile(
  parentId: string,
  file: File,
): Promise<FileNode> {
  const form = new FormData();
  form.append("file", file);
  return json<FileNode>(
    await fetch(`${API}/${parentId}/upload`, {
      method: "POST",
      body: form,
    }),
  );
}

export interface BulkUploadResult {
  foldersCreated: number;
  filesUploaded: number;
  filesSkipped: number;
  totalFiles: number;
}

/**
 * Upload a whole folder hierarchy. Each file carries its `webkitRelativePath`
 * (e.g. "MyFolder/sub/a.txt"); the server recreates the tree under parentId.
 */
export async function uploadFolder(
  parentId: string,
  files: File[],
): Promise<BulkUploadResult> {
  const form = new FormData();
  for (const file of files) {
    const relative =
      (file as File & { webkitRelativePath?: string }).webkitRelativePath ||
      file.name;
    form.append("files", file);
    form.append("paths", relative);
  }
  return json<BulkUploadResult>(
    await fetch(`${API}/${parentId}/upload-folder`, {
      method: "POST",
      body: form,
    }),
  );
}

/**
 * Snapshot of the server-side ingestion queue (text extraction → chunking →
 * embedding, or lexical-only indexing). Ingestion runs in the background after
 * the upload request returns; polled while active to drive the progress bar.
 */
export interface IngestionProgress {
  active: boolean;
  phase: "idle" | "starting" | "extracting" | "chunking" | "embedding" | "indexing";
  fileName: string | null;
  fileIndex: number;
  totalFiles: number;
  chunksDone: number;
  chunksTotal: number;
  updatedAt: number;
}

export async function fetchIngestionProgress(): Promise<IngestionProgress> {
  return json<IngestionProgress>(await fetch(`${API}/ingestion-progress`));
}

export async function deleteNode(id: string): Promise<void> {
  const res = await fetch(`${API}/${id}`, { method: "DELETE" });
  if (!res.ok && res.status !== 204) {
    const body = await res.json().catch(() => ({ error: res.statusText }));
    throw new Error(body.error ?? `Delete failed (${res.status})`);
  }
}

/* ── Search (RAG context path, plan.md §5.6, §5.8) ── */

export interface SearchResult {
  id: string;
  sourceName: string;
  sourcePath: string;
  sourceUrl: string;
  sourceKind: string;
  excerpt: string;
  description: string;
  content: string;
  score: number;
  aclTags: string[];
  position: number;
}

export interface SearchResponse {
  query: string;
  mode: "rag" | "tool" | "empty" | "notReady";
  results: SearchResult[];
  total?: number;
  localCount?: number;
  webCount?: number;
  web?: boolean;
  webReady?: boolean;
  webMessage?: string;
  lexicalOnly?: boolean;
  lexicalMessage?: string;
  tool?: string;
  message?: string;
  requiresSetup?: string[];
  pluginStatus?: {
    id: string;
    name: string;
    status: PluginStatus;
    ready: boolean;
    health: string;
  }[];
}

export async function search(query: string, topK = 20, web = false): Promise<SearchResponse> {
  return json<SearchResponse>(
    await fetch(`/api/search?q=${encodeURIComponent(query)}&topK=${topK}&web=${web}`),
  );
}

/* ── Plugins ── */

export type PluginStatus =
  | "NOT_INSTALLED"
  | "INSTALLING"
  | "INSTALLED"
  | "ACTIVE"
  | "ERROR"
  | "DISABLED";

export interface PluginInfo {
  id: string;
  name: string;
  description: string;
  category: "REQUIRED" | "OPTIONAL";
  builtin: boolean;
  status: PluginStatus;
  enabled: boolean;
  running: boolean;
  ready: boolean;
  health: string;
}

export interface PluginJob {
  jobId: string;
  pluginId: string;
  status: "running" | "completed" | "failed";
  error?: string;
}

const PLUGINS_API = "/api/plugins";

export async function listPlugins(): Promise<PluginInfo[]> {
  return json<PluginInfo[]>(await fetch(PLUGINS_API));
}

export async function installPlugin(id: string): Promise<{ jobId: string; status: string }> {
  return json(await fetch(`${PLUGINS_API}/${id}/install`, { method: "POST" }));
}

export async function enablePlugin(id: string): Promise<PluginInfo> {
  return json<PluginInfo>(await fetch(`${PLUGINS_API}/${id}/enable`, { method: "POST" }));
}

export async function disablePlugin(id: string): Promise<PluginInfo> {
  return json<PluginInfo>(await fetch(`${PLUGINS_API}/${id}/disable`, { method: "POST" }));
}

export async function startPlugin(id: string): Promise<PluginInfo> {
  return json<PluginInfo>(await fetch(`${PLUGINS_API}/${id}/start`, { method: "POST" }));
}

export async function stopPlugin(id: string): Promise<PluginInfo> {
  return json<PluginInfo>(await fetch(`${PLUGINS_API}/${id}/stop`, { method: "POST" }));
}

export async function getPluginJob(jobId: string): Promise<PluginJob> {
  return json<PluginJob>(await fetch(`${PLUGINS_API}/jobs/${jobId}`));
}

/* ── Connections (Confluence/Jira/... ingestion connectors) ── */

export type ConnectionType = "CONFLUENCE" | "JIRA" | "SHAREPOINT";
export type DeploymentType = "CLOUD" | "SERVER_DC" | "UNKNOWN";
export type ConnectionStatus = "PENDING" | "CONNECTED" | "ERROR" | "DISABLED";

export interface ConnectionInfo {
  id: string;
  type: ConnectionType;
  name: string;
  baseUrl: string;
  deploymentType: DeploymentType;
  authMode: "BASIC" | "OAUTH2";
  authUsername: string | null;
  status: ConnectionStatus;
  lastError?: string;
  webhookRegistered: boolean;
  aclScope: string[];
  createdAt: string;
  updatedAt: string;
  lastSyncedAt?: string;
}

export interface ConnectionJob {
  jobId: string;
  connectionId: string;
  kind: "TEST_CONNECTION" | "BACKFILL";
  status: "running" | "completed" | "failed";
  itemsProcessed?: number;
  itemsTotal?: number;
  error?: string;
}

export interface CreateConnectionInput {
  type: ConnectionType;
  name: string;
  baseUrl: string;
  username: string;
  password: string;
  aclScope?: string[];
}

const CONNECTIONS_API = "/api/connections";

export async function listConnections(): Promise<ConnectionInfo[]> {
  return json<ConnectionInfo[]>(await fetch(CONNECTIONS_API));
}

export async function createConnection(
  input: CreateConnectionInput,
): Promise<{ id: string; jobId: string; status: string }> {
  return json(
    await fetch(CONNECTIONS_API, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(input),
    }),
  );
}

export async function getConnection(id: string): Promise<ConnectionInfo> {
  return json<ConnectionInfo>(await fetch(`${CONNECTIONS_API}/${id}`));
}

export async function deleteConnection(id: string): Promise<void> {
  const res = await fetch(`${CONNECTIONS_API}/${id}`, { method: "DELETE" });
  if (!res.ok && res.status !== 204) {
    const body = await res.json().catch(() => ({ error: res.statusText }));
    throw new Error(body.error ?? `Delete failed (${res.status})`);
  }
}

export async function triggerBackfill(id: string): Promise<{ jobId: string; status: string }> {
  return json(await fetch(`${CONNECTIONS_API}/${id}/backfill`, { method: "POST" }));
}

export async function enableConnection(id: string): Promise<ConnectionInfo> {
  return json<ConnectionInfo>(await fetch(`${CONNECTIONS_API}/${id}/enable`, { method: "POST" }));
}

export async function disableConnection(id: string): Promise<ConnectionInfo> {
  return json<ConnectionInfo>(await fetch(`${CONNECTIONS_API}/${id}/disable`, { method: "POST" }));
}

export async function getConnectionJob(jobId: string): Promise<ConnectionJob> {
  return json<ConnectionJob>(await fetch(`${CONNECTIONS_API}/jobs/${jobId}`));
}
