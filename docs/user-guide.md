# Application Guide

Every process in the application, in the order you would meet them: set up the search
infrastructure, add knowledge, ask questions, connect systems, invoke API requests safely, build
insights, export, and audit.

The in-app **Guide** page is the short version of this document. Two companions cover the query
system in depth: [`query-language-reference.md`](query-language-reference.md) and
[`reports-and-insights-tutorial.md`](reports-and-insights-tutorial.md).

---

## 1. Running the application

~~~sh
cd mcp-server
mvn package && java -jar target/mcp-server.jar     # http://127.0.0.1:8080
~~~

One JAR serves the API, MCP endpoint, and browser-native Web UI. In development, run Spring Boot
and use http://127.0.0.1:8080 (see [`developer-guide.md`](developer-guide.md)).

The server binds to `127.0.0.1` on purpose. There is no authentication yet — it lands in Phase 6
together with ACL enforcement — so treat the app as a single-trust-boundary local tool and do not
expose it beyond a trusted internal network.

**Where things live:**

| Page | Route | What it does |
| --- | --- | --- |
| Chat | `/` | Knowledge search and deterministic tool invocation. |
| Files | `/files` | Folders, uploads, ingestion into the knowledge base. |
| Plugins | `/plugins` | Install and run the local search infrastructure. |
| Connections | `/connections` | Confluence/Jira ingestion and API collection imports. |
| Apps | `/apps` | Every imported request: enable, test, group, override auth. |
| Insights | `/insights` | The insight workspace: saved-insight library and the last run's view, with the RQL/RQD editor behind **Edit** (`/reports` and `/dashboards` redirect here). |
| Help | `/help` | Reference topics, shared with MCP clients, plus links to the walkthroughs. Reached from the **?** button in the top bar rather than the main nav; `/guide` redirects here. |
| Tutorial | `/tutorial` | Step-by-step walkthroughs with a route link and a check for every step. Progress is ticked off locally. |

---

## 2. Plugins — preparing search

Search runs entirely in-process. Three plugins provide it, and the Plugins page installs them without
any OS-level setup:

| Plugin | Role | Ships in the JAR |
| --- | --- | --- |
| SQLite Vec Store | Vector index (`vec0`) beside the FTS5 lexical index | Yes |
| Nomic Embedding | `nomic-embed-text-v1.5` ONNX embeddings, in-process | Yes |
| SearXNG | Optional local web search, runs as a native Python child process | No — installed on demand |

Each plugin supports **install → enable → start**; install runs as a background job that the page
polls. `GET /api/plugins` reports status and health for all of them.

The application boots in **degraded mode** when the search plugins are not ready: files, folders,
connections, and API tools all keep working, and search returns a structured "not ready" response
rather than an error. Content indexed earlier remains lexically searchable even without the embedding
model — only semantic retrieval stops.

## 3. Files — adding knowledge

The Files page is a familiar folders-and-files manager: a folder tree, breadcrumbs, upload of single
files or whole folder trees, and delete.

Uploading starts ingestion in the background. The same pipeline handles every source in the system:

~~~text
bytes + mimeType + source metadata
  → text extraction (Tika for binaries; direct for text, HTML, JSON, Markdown)
  → heading-aware chunking
  → embedding (ONNX, in-process)
  → chunk store (SQLite row + FTS5 + vec0 in one write)
~~~

Notes worth knowing:

- **Wait for ingestion before relying on new content.** The page shows ingestion progress; a file is
  searchable only after its chunks are written.
- **Names and paths are evidence.** They are returned with every search hit, so meaningful file names
  make answers easier to verify.
- **ACL tags are captured on every chunk** from the moment it is ingested, even though nothing filters
  on them until Phase 6. Any new ingestion path must tag chunks before it merges.

## 4. Chat — the universal query bar

One input does two different things, and the distinction is syntactic, not a guess:

| You type | What happens |
| --- | --- |
| `how do we rotate database credentials` | Hybrid RAG search: lexical + vector, merged with RRF, reranked. Returns cited evidence. |
| `#create_todo "Call the vendor"` | Deterministic tool invocation, keyword-matched across enabled tools. |
| `@todo-app #create_todo "Call the vendor"` | Same, scoped to one connection. |
| `@group-name #tool_name` | Same, scoped to a tool group. |
| `@todo-app` | Browses that app's tools. |

Only a query **starting** with `@` or `#` is a tool query — a `#` mid-sentence is ordinary text and
falls through to search untouched. Autocomplete appears as you type either sigil.

### 4.1 Sessions and consecutive requests

A session is a working transcript, not a saved single query. Every search or tool call appends a
turn and leaves earlier requests and responses visible. This supports flows such as listing todos
with a GET, inspecting the result, then opening an update tool and sending a PUT without losing the
list that supplied the ID.

Tool responses have two views:

- **Preview** formats the body according to its content type:
  - **JSON** — objects become field lists, arrays of objects become tables.
  - **CSV** — parsed into a table, honouring quoted fields and embedded commas or newlines.
  - **XML** — re-indented and shown as source.
  - **HTML** — shown as **source, not rendered**, with the page title and an expandable text
    extract. An upstream API's markup is never executed inside this page.
  - **Anything else** — shown verbatim with its line structure intact.
- **Raw response** preserves the exact response body for copying or debugging.

A body whose content type claims JSON but does not parse falls back to the plain view rather than
failing. Response bodies are always escaped, whatever the format.

Note that **RQL/Insights reads JSON only** — an XML or CSV endpoint can be called and previewed from
a session, but cannot back an insight dataset. Ingestion is separate again: a GET marked as a
knowledge source extracts text from HTML and XML as well as JSON.

When a write tool needs arguments, **Form** uses its imported schema and **Raw body** sends a
verbatim payload with the selected content type. Path, query, and header arguments remain available
in raw-body mode. **Preview request** resolves the exact URL, headers, and body without sending it.
Sessions and their ordered turns persist in this browser until deleted.

### 4.2 What search returns

Cited context, not a generated answer. No prompt, file, or search result is sent to an external
answer provider; results keep source name, path, excerpt, and score so you can judge the evidence
yourself. If the excerpts do not answer the question, narrow it with exact terms from the documents.

### 4.3 Web augmentation

The **Web** toggle in the composer merges results from the local SearXNG plugin into the current
query only — web content is never added to the knowledge store. The toggle is disabled until SearXNG
is installed and running, and a query with web enabled degrades to local evidence if it stops.
Open the source URL before relying on any web claim.

### 4.4 Approval for write actions

Read requests (GET) execute immediately and return their result. Write requests do not:

1. The request is validated and guarded, and a **preview** is produced — resolved URL, headers, body.
2. The preview comes back with a short-lived, **single-use confirmation token**.
3. A person reads the preview and explicitly approves.
4. Only then does `POST /api/tools/confirm/{token}` execute it; `…/reject/{token}` discards it.

Never reuse a token, and never treat an earlier approval as approval for changed arguments. This rule
is identical for the web UI and MCP clients — it is the application's core safety contract.

### 4.5 Knowledge export

The **export** action in the chat header opens a source picker: choose indexed files and connected
apps, and the server returns a plain-text export of their chunks
(`POST /api/summary-exports`). The download carries `X-Export-Source-Count` and
`X-Export-Chunk-Count` headers, and the UI shows both after the download. Limits: 500 selected
sources, 25 MB of text. Sources with no indexed content yet return a 409 telling you to wait for
ingestion or run a backfill.

## 5. Connections — bringing in remote systems

Two different kinds of connection live on this page.

### 5.1 Confluence and Jira (content ingestion)

Create the connection with a base URL and credentials. Deployment type (Cloud vs Server/Data Center)
is auto-detected, since the two have diverged — notably Jira Cloud retired the classic search
endpoint, so the connector tries `POST /rest/api/3/search/jql` first and falls back to
`GET /rest/api/2/search`, caching whichever works per connection.

The lifecycle:

| Step | What it does |
| --- | --- |
| **Test connection** | Background job; verifies credentials and deployment. Poll `GET /api/connections/jobs/{jobId}`. |
| **Backfill** | Background job; walks the source and ingests everything through the shared ingestion pipeline. |
| **Webhook** | `POST /api/connections/{id}/webhook` durably queues the event and returns 202 immediately, so intake stays inside a 3-second ack regardless of processing time. |
| **Delta poll** | A scheduler polls every `CONNECTED` connection on `connectors.poll-interval-ms`. |
| **Disable** | Excludes the connection from polling — that is the entire pause mechanism. |

Queued webhook events are processed by a single background worker over a SQLite table. If the process
crashes mid-event, rows left `PROCESSING` are reset to `PENDING` at startup, so nothing is lost.

Credentials are encrypted with AES-256-GCM using a locally generated key file (`./data/connections.key`).
There is no Vault/KMS integration yet — a deliberate, documented scope limit.

### 5.2 API collections (Postman / OpenAPI)

Import a Postman collection or OpenAPI document — upload a `.json`/`.yaml`/`.yml` file or point at a
spec URL — and every request becomes a callable tool named `{app}_{request-name}`. The connection name
becomes the `@app` slug used in chat. **Detect auth** inspects the spec and proposes the scheme;
supported modes are Basic, Bearer, API key header, OAuth2, and none.

Nothing is callable straight after import: requests arrive disabled, and enabling one is the moment a
person decides it may run.

## 6. Apps — the tool surface

The Apps page is where imported requests become a governed tool surface.

- **Enable / disable** per request. Disabling a request that feeds knowledge also clears that role.
- **Test a request** in the request builder — fill parameters, inspect the resolved call, and see the
  response, including the preview/confirm path for writes.
- **Manual tools.** Build a request from scratch and save it. Manual tools can be edited and deleted;
  imported ones cannot (they are spec-managed, so those calls return 409).
- **Per-tool auth override.** Give one request different credentials from its connection. Clearing the
  mode reverts it to inheriting the connection's auth.
- **Knowledge source.** Flag an enabled GET request as a knowledge source and its responses are
  ingested into the knowledge base on backfill and on every scheduled poll — the bridge from "API
  data" to "searchable content".
- **Tool groups.** Group tools across connections under one `@group` handle, and enable or disable the
  whole group at once.

Tool availability is live: imports and enablement change the tool list while the server runs, so MCP
clients should call `tools/list` again rather than trusting a cached list.

In the Search composer, type `@` to open every available app and group, then keep typing to filter
the list. Selecting a scope advances the composer to `@scope #`; the request list is then limited to
that app or group's requests and filters with every character typed after `#`. Starting directly
with `#` searches requests across all apps. Use the arrow keys and Enter to select without leaving
the keyboard.

## 7. Insights and reports

Covered in full by the [tutorial](reports-and-insights-tutorial.md) and the
[reference](query-language-reference.md). The short version:

- The Insights page opens as a **library on the left and the last result on the right**. It reopens
  whichever insight you last had open, showing the result of its previous run — so the page starts
  on answers, not an empty panel. **Edit** reveals the document; **Run insight** fetches fresh data
  and saves the new result with the insight.
- A restored result is labelled `Saved result · ran <time>`, and says `Document edited since this
  run` once you change the source, so old numbers are never mistaken for current ones. Opening an
  insight never re-runs it on its own.
- **One insight can span several apps.** Qualify a request with its app (`request "CRM: List
  customers"`), scope a section with `use collection "CRM";`, or leave names bare and let them
  resolve across every connected collection. A name that exists in two apps is reported, not guessed.
- Documents are Markdown with fenced ` ```rql `
  blocks and a component set covering charts (`<Stat>`, `<BarChart>`, `<DataTable>`) and summary
  blocks (`<Text>`, `<KeyValue>`, `<LabelValue>`, `<QuickTable>`, `<LabelTable>`, `<Status>`,
  `<Metrics>`).
- The query language covers filtering with dates and conditionals, shaping and aggregation, array
  expansion, per-row lookups, dataset joins, set operations with provenance, and column-wise
  comparison — the full function set of the `.filter` report language it was ported from.
- Queries run **enabled read requests only**, through the same executor and credentials as every other
  tool call. There is no second credential path and no arbitrary code execution.
- Guardrails are structural: no dual y-axis, no author-chosen series colours, and every chart carries a
  data table twin.
- Documents are not persisted — copy the source out if you want to keep it.

## 8. Audit and metrics

Every consequential action is recorded: `TOOL_INVOKED`, `TOOL_EXECUTED`, `TOOL_APPROVED`,
`TOOL_REJECTED`, `TOOL_FAILED`, `TOOL_EXPIRED`, `SEARCH_PERFORMED`.

~~~sh
curl -s 'http://127.0.0.1:8080/api/audit?actor=web-user&eventType=TOOL_EXECUTED&page=0&size=50'
curl -s http://127.0.0.1:8080/api/metrics/summary
~~~

`GET /api/audit` filters by `actor`, `toolName`, `eventType`, `from`, `to`, and pages with
`page`/`size`. `GET /api/metrics/summary` reports MCP request count, tool execution count, error
count, search count, and cache statistics.

**These are API-only today.** An `AdminPage` component exists in the frontend with an audit log and a
metrics view, but it is not wired to a route, so there is no page in the UI that reaches it. Use the
endpoints, or route the component if you want the UI.

## 9. Connecting an MCP client

Point any MCP-compatible client (Claude Desktop, Claude Code, ChatGPT, or your own) at the Streamable HTTP
endpoint:

~~~text
http://127.0.0.1:8080/mcp
~~~

The recommended session start:

1. `initialize`, then `resources/list`.
2. Read `mcp://enterprise-mcp/guides/operating-guide` (human-readable) and
   `mcp://enterprise-mcp/guides/llm-playbook.json` (structured) — both are generated from the same
   catalog that powers the in-app Guide, so a client and a person see the same workflow and safety
   rules.
3. `tools/list` — the enabled tool surface changes at runtime.
4. Use `search-knowledge-base` for grounded context, and honour the preview/`confirm-action` contract
   for writes.

Two prompts are published for orientation and task execution: `orient-to-enterprise-mcp` and
`execute-grounded-task`. Details in [`mcp-client-guide.md`](mcp-client-guide.md).

## 10. Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Search says setup is required | Vector store or embedding model not ready | Install and enable both on Plugins. |
| Semantic results missing, keyword results fine | Embedding plugin down | Check plugin health; already-indexed chunks stay lexically searchable. |
| Web toggle disabled | SearXNG not installed or stopped | Install and start it on Plugins. |
| A new file is not searchable | Ingestion still running | Watch ingestion progress on Files. |
| A tool cannot be found in chat | It is disabled, or you are scoping to the wrong app | Enable it on Apps; check the `@app` slug. |
| A write executes nothing | It is waiting on confirmation | Approve the preview; tokens are single-use and expire. |
| An MCP client sees a stale tool | Tool list cached client-side | Call `tools/list` again. |
| A deep route 404s from the JAR | SPA route has no server-side forward | Add it to `WebMvcConfig` and rebuild the frontend. |
| UI shows "connecting" forever | Backend not running on 127.0.0.1:8080 | Start `mvn spring-boot:run`. |

---

## Related documents

| Document | Purpose |
| --- | --- |
| [`query-language-reference.md`](query-language-reference.md) | RQL/RQD reference — statements, stages, operators, diagnostics, endpoints. |
| [`reports-and-insights-tutorial.md`](reports-and-insights-tutorial.md) | Hands-on build of a report and an insight. |
| [`developer-guide.md`](developer-guide.md) | Maintainer workflow, code locations, conventions. |
| [`mcp-client-guide.md`](mcp-client-guide.md) | Protocol-level client setup. |
| [`product-idea.md`](product-idea.md) | Architecture blueprint — the source of truth for what and why. |
| [`plan.md`](plan.md) | Phase tracker and E2E checklists. |
