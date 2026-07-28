# Enterprise MCP Server

An enterprise MCP (Model Context Protocol) server that turns fragmented enterprise knowledge and
application APIs into a single, governed context layer for any AI client — GitHub Copilot CLI, VS Code
Copilot, Microsoft Copilot Studio, ChatGPT, Claude Desktop, or any MCP-compatible client. A Java 17+ /
Spring Boot 3 service exposes **tools** (deterministic, approval-gated actions in connected systems),
**resources** (ACL-gated enterprise content), **prompts** (versioned enterprise templates), and an
**ACL-filtered RAG pipeline** that returns grounded, cited context — the AI client synthesizes; the
server keeps it anchored in enterprise reality the user is entitled to see.

A first-party **Web UI** rides the same services: a SharePoint-like **files & folders manager**, a
**connectors console** (upload a Postman collection and the app is wired — every request becomes a tool
named `{app}_{request-name}`, e.g. `confluence_get_space_list`), and a **universal search page** where
plain keywords run RAG search and `#keyword` invocations run tools deterministically
(`#todo_app_create_todo "Need to meet chairman"`) — no LLM in the loop.

Blueprint: [`product-idea.md`](product-idea.md) (source of truth for **what** and **why**).
Execution tracker: [`plan.md`](plan.md) (**in what order**, and how we know each step is done).

## Repository layout

| Path | What |
| --- | --- |
| `docs/product-idea.md` | Architecture blueprint — vision, stack, core modules, security, roadmap (source of truth; architecture and sequence diagrams embedded as Mermaid) |
| `docs/plan.md` | Execution tracker — per-phase build and E2E test checklists (phase-state diagrams embedded as Mermaid) |
| `docs/plugins-plan.md` | Implementation plan for the Plugins page (embedded SQLite/sqlite-vec, Nomic embedding, SearXNG) |
| `docs/connectors-manual-verification.md` | How to stand up disposable Confluence/Jira Cloud test sites and verify the connectors against a real instance, closing the gaps automated WireMock-based tests can't |
| `docs/user-guide.md` | Every application process end to end — plugins, files, chat and tool invocation, connections, apps, export, audit |
| `docs/query-language-reference.md` | The query system: RQL statements, pipeline stages, operators, RQD insight documents, diagnostic codes, and endpoints |
| `docs/reports-and-insights-tutorial.md` | Hands-on walkthrough — connect an API, write a query, build an insight with KPIs, a chart, a table, and parameters |
| `docs/developer-guide.md` | Maintainer workflow, code locations, query grammar, insight slice, and guide-system upkeep |
| `docs/mcp-client-guide.md` | Streamable HTTP client setup plus resource, prompt, grounding, and confirmation workflow |
| `docs/report-query-design.md` / `docs/dashboard-design.md` | Design intent behind RQL and RQD, including what is planned but not yet built |

The `mcp-server/` module (structure in `product-idea.md` §4) is live — Phase 1 (foundation) and Phase 2
(knowledge & search, including the Files & Folders and Plugins UI) are implemented; Confluence/Jira
ingestion connectors are in progress. See `docs/plan.md` for current phase status.

## Roadmap at a glance

| Phase | Outcome |
| --- | --- |
| **1 · Foundation** | An AI client connects over Streamable HTTP and calls a tool — fully traced, with CI and a golden-set evaluation baseline (no auth yet — trusted internal network only) |
| **2 · Knowledge & Search** | Grounded, cited context from ingested enterprise content (RRF hybrid search: lexical + sqlite-vec, cross-encoder rerank); ACL tags captured on every chunk; Web UI slice 1 — universal search page + files & folders manager |
| **3 · Enterprise Actions** | Deterministic workflow engine with approval gates (preview → confirmation token), audit logs, rate limiting, Caffeine caching; Web UI slice 2 — `#keyword` actions with preview/approve cards |
| **4 · Integrations** | Jira, Confluence, SharePoint, GitHub, Kubernetes live; zero-code Postman/OpenAPI onboarding (`{app}_{request-name}` tools); Web UI slice 3 — connectors console; Microsoft Teams protected-API track filed |
| **5 · Production** | High availability, horizontal scaling, full monitoring, disaster recovery, backups |
| **6 · Auth & Access Control** (final) | OAuth 2.1 / MCP authorization spec, Keycloak OIDC, RBAC/ABAC, ACL enforcement switched on, confused-deputy guard — cleared for exposure beyond trusted networks |

Every phase completes a runnable end-to-end flow and exits only when its E2E test checklist in
[`plan.md`](plan.md) passes. Getting started = working the Phase 1 build checklist there.

## Stack (open-source-first)

Java 17+ · Spring Boot 3 · Java MCP SDK (Streamable HTTP, stateless) · embedded SQLite + sqlite-vec (HNSW)
+ FTS5 lexical leg, merged via RRF · in-process ONNX embeddings (nomic-embed-text-v1.5) +
cross-encoder reranker (ONNX Runtime — no sidecars) · Caffeine cache (Valkey at scale) · SQLite-based
durable event queue (Kafka at scale) · Keycloak OIDC (Phase 6) · OpenTelemetry + Prometheus + Grafana ·
Loki/ELK · Maven. Setup is zero-install — SQLite, the embedding model, and SearXNG install/enable from
the in-app **Plugins** page rather than manual OS-specific steps (see `docs/plugins-plan.md`).

**Runtime constraint:** no Docker or any virtualization for now — the server ships as a single runnable
JAR and all backing services run as natively installed processes; Kubernetes is revisited only if that
changes. **Security sequencing:** auth & access control land as the final phase (Phase 6) — until it
exits, the server runs on trusted internal networks only. Proprietary services (Microsoft Graph/Entra for
Teams content) are deferred to Phase 4.
