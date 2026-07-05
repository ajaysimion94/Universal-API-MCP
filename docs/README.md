# Enterprise MCP Server

An enterprise MCP (Model Context Protocol) server that turns fragmented enterprise knowledge and
application APIs into a single, governed context layer for any AI client — GitHub Copilot CLI, VS Code
Copilot, Microsoft Copilot Studio, ChatGPT, Claude Desktop, or any MCP-compatible client. A Java 21 /
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

The repository is currently **docs-only** (pre-implementation). The `mcp-server/` module (structure in
`product-idea.md` §4) lands in Phase 1 per the plan.

## Roadmap at a glance

| Phase | Outcome |
| --- | --- |
| **1 · Foundation** | An AI client connects over Streamable HTTP and calls a tool — fully traced, with CI and a golden-set evaluation baseline (no auth yet — trusted internal network only) |
| **2 · Knowledge & Search** | Grounded, cited context from ingested enterprise content (RRF hybrid search: lexical + pgvector, cross-encoder rerank); ACL tags captured on every chunk; Web UI slice 1 — universal search page + files & folders manager |
| **3 · Enterprise Actions** | Deterministic workflow engine with approval gates (preview → confirmation token), audit logs, rate limiting, Caffeine caching; Web UI slice 2 — `#keyword` actions with preview/approve cards |
| **4 · Integrations** | Jira, Confluence, SharePoint, GitHub, Kubernetes live; zero-code Postman/OpenAPI onboarding (`{app}_{request-name}` tools); Web UI slice 3 — connectors console; Microsoft Teams protected-API track filed |
| **5 · Production** | High availability, horizontal scaling, full monitoring, disaster recovery, backups |
| **6 · Auth & Access Control** (final) | OAuth 2.1 / MCP authorization spec, Keycloak OIDC, RBAC/ABAC, ACL enforcement switched on, confused-deputy guard — cleared for exposure beyond trusted networks |

Every phase completes a runnable end-to-end flow and exits only when its E2E test checklist in
[`plan.md`](plan.md) passes. Getting started = working the Phase 1 build checklist there.

## Stack (open-source-first)

Java 21 · Spring Boot 3 · Java MCP SDK (Streamable HTTP, stateless) · PostgreSQL + pgvector (HNSW) +
lexical full-text leg, merged via RRF · in-process ONNX embeddings (nomic-embed-text-v1.5) +
cross-encoder reranker (ONNX Runtime — no sidecars) · Caffeine cache (Valkey at scale) · Postgres-outbox
event queue (Kafka at scale) · Keycloak OIDC (Phase 6) · OpenTelemetry + Prometheus + Grafana · Loki/ELK ·
Maven.

**Runtime constraint:** no Docker or any virtualization for now — the server ships as a single runnable
JAR and all backing services run as natively installed processes; Kubernetes is revisited only if that
changes. **Security sequencing:** auth & access control land as the final phase (Phase 6) — until it
exits, the server runs on trusted internal networks only. Proprietary services (Microsoft Graph/Entra for
Teams content) are deferred to Phase 4.
