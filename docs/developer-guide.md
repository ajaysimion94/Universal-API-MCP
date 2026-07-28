# Developer Guide

This guide is for people extending or operating the Enterprise MCP Server. For the architectural
source of truth, read product-idea.md; for the active delivery sequence, read plan.md. The in-app
Guide page is the short operational version of this document.

## Run the application

Use two terminals while developing the UI:

~~~sh
# terminal 1 — API, MCP endpoint, SQLite, and background workers
cd mcp-server
mvn spring-boot:run -Dskip.frontend=true

# terminal 2 — Vite UI with hot reload
cd mcp-server/webui
npm install
npm run dev
~~~

Open http://localhost:5173. The Vite server proxies /api to the Spring Boot application at
127.0.0.1:8080; the MCP Streamable HTTP endpoint is at http://127.0.0.1:8080/mcp.

For a production-like artifact:

~~~sh
cd mcp-server
mvn package
java -jar target/mcp-server.jar
~~~

The server intentionally binds only to 127.0.0.1 until Phase 6 authentication and ACL enforcement
are delivered. Do not change that guardrail for convenience.

## Daily checks

~~~sh
cd mcp-server && mvn test
cd mcp-server && mvn -Dskip.frontend=true -q compile
cd mcp-server/webui && npm run typecheck
~~~

Run mvn package whenever frontend packaging or bundled resources change. Backend integration tests
write to mcp-server/data/mcpserver.db; remove that file only when you deliberately need a clean
local database.

## Where changes belong

| Concern | Location | Rule |
| --- | --- | --- |
| REST adapter | mcp-server/src/main/java/com/mcpserver/controllers/ | Adapt requests and responses only; retain reusable logic in services. |
| Reusable application logic | services/, repositories/, feature packages | Preserve the separation from protocol code. |
| MCP SDK code | mcp-server/src/main/java/com/mcpserver/mcp/ | Keep SDK coupling contained to this package. |
| Web API calls | mcp-server/webui/src/api.ts | Components must not call fetch directly. |
| UI routes | webui/src/App.tsx and config/WebMvcConfig.java | Add both the SPA route and its production forward. |
| UI appearance | styles.css, components.css, icons.tsx | Use design tokens; add inline SVGs only in icons.tsx. |
| Design intent | .impeccable.md | Read before changing UI; refined utilitarian, dark, amber used sparingly. |

TypeScript is strict: unused imports or variables fail the build. Keep Java controllers thin and map
IllegalArgumentException to 400 and IllegalStateException to 409 where an endpoint needs local error
handling.

## Knowledge, tools, and query grammar

Plain text in Chat runs the RAG context path. It returns evidence from indexed files and, when
enabled, temporary contextual SearXNG web results. The server expands user intent into focused
queries, fetches and extracts the strongest pages, and reranks them with semantic, authority,
freshness, corroboration, and domain-diversity signals. It does not generate an answer through an
external provider.

Imported API requests become deterministic tools. Use the following grammar in Chat:

~~~text
#tool_name
@app_slug #tool_name
@group_slug #tool_name
~~~

For example, #inventory_list_products finds a tool by keyword, while
@inventory #inventory_list_products scopes the search to one connection. Read tools execute after
their input is valid. Write tools return a preview and a single-use confirmation token; the UI must
show the preview and receive explicit person-level approval before confirmation.

The insight slice executes enabled GET tools only. It parses .rqd insight documents with fenced
RQL blocks and renders the supported Stat, BarChart, and DataTable components. It deliberately does
not introduce arbitrary code execution or a second API credential path.

The user-facing documentation for this is split in three: query-language-reference.md is the RQL/RQD
reference including every diagnostic code and endpoint contract, reports-and-insights-tutorial.md
is the hands-on build, and user-guide.md covers the rest of the application. When you change grammar,
stage behaviour, a diagnostic code, or a component prop, update the reference in the same change —
its "Current limits" section is what keeps the design docs from being read as shipped behaviour.

## Guide system

The Guide is intentionally shared across people and MCP clients:

- guides/GuideCatalog.java is the runtime catalog for the Guide API and in-app page.
- mcp/McpGuideBridge.java publishes the catalog's operating guide and JSON playbook as MCP resources
  plus two reusable prompts.
- mcp-client-guide.md is the durable client-integration reference.

When a runtime workflow or safety rule changes, update the catalog and its matching Markdown guide in
the same change. Verify GET /api/guides, resources/list, resources/read, and prompts/list afterward.
Add a DECISIONS.md entry when the change is architectural or changes the safety contract.

## Common local failures

- **Vite shows “connecting” forever:** Spring Boot is not available at 127.0.0.1:8080.
- **A deep UI route 404s from the JAR:** add the route to WebMvcConfig, then build the frontend once.
- **Search has no semantic results:** enable the SQLite vector store and embedding model in Plugins.
- **Web search is unavailable:** install and start the local SearXNG plugin.
- **A client sees a stale tool:** call tools/list again; imports and enablement change the live tool
  surface at runtime.
