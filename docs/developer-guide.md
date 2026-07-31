# Developer Guide

This guide is for people extending or operating the Enterprise MCP Server. Read
`product-idea.md` for architecture and `plan.md` for delivery order.

## Run the application

The backend and browser-native Web UI run in one Spring Boot process:

```sh
cd mcp-server
mvn spring-boot:run
```

Open `http://127.0.0.1:8080`. The MCP Streamable HTTP endpoint is
`http://127.0.0.1:8080/mcp`.

For a production-like artifact:

```sh
cd mcp-server
mvn package
java -jar target/mcp-server.jar
```

The server intentionally binds only to `127.0.0.1` until Phase 6 authentication and ACL enforcement
are delivered.

## Daily checks

```sh
cd mcp-server && mvn test
cd mcp-server && mvn -q compile
cd mcp-server && mvn package -Dskip.bundle=true
```

The UI has no generated bundle or Node dependency. When Node happens to be installed, `node --check`
is a useful optional syntax check for each file under `src/main/resources/static`.

## Where changes belong

| Concern | Location | Rule |
| --- | --- | --- |
| REST adapter | `mcp-server/src/main/java/com/mcpserver/controllers/` | Adapt requests and responses only. |
| Reusable logic | `services/`, `repositories/`, feature packages | Preserve separation from protocol code. |
| MCP SDK code | `mcp-server/src/main/java/com/mcpserver/mcp/` | Keep SDK coupling contained here. |
| Browser API calls | `mcp-server/src/main/resources/static/api.js` | Page modules use this client instead of direct fetch calls. |
| UI routes | `static/app.js` and `config/WebMvcConfig.java` | Add both client routing and the deep-route forward. |
| UI pages | `static/pages/*.js` | Export `mount`; return cleanup for timers/listeners. |
| UI appearance | `static/styles.css`, `components.css`, `ui.js` | Use tokens and the shared inline-SVG map. |
| Design intent | `.impeccable.md` | Read it before UI changes. |

Escape dynamic content before inserting it into HTML templates. Keep Java controllers thin and map
`IllegalArgumentException` to 400 and `IllegalStateException` to 409.

## Knowledge, tools, and query grammar

Plain text on Search runs the RAG context path. It returns evidence from indexed files and, when
enabled, contextual SearXNG web results. Imported API requests become deterministic tools:

```text
#tool_name
@app_slug #tool_name
@group_slug #tool_name
```

Read tools execute after validation. Write tools return a preview and single-use confirmation token;
the UI must show the preview and obtain explicit approval before confirmation.

Search sessions are browser-persisted ordered turn transcripts. Submitting from the composer appends
to the active session; only the explicit **New** action starts another session. Tool results expose
a formatted Preview and exact Raw response. Inline tool forms support schema-driven and raw-body
invocation modes through the existing `/api/tools/{id}/preview` and `/invoke` endpoints.

Insights execute enabled GET tools only. `.rqd` documents contain fenced RQL blocks and supported
view components. Arbitrary HTML or code execution is not supported.

## Guide system

- `guides/GuideCatalog.java` is the runtime catalog for the Guide API and in-app page.
- `mcp/McpGuideBridge.java` publishes the catalog as MCP resources and prompts.
- `mcp-client-guide.md` is the durable client-integration reference.

Update the runtime catalog and matching Markdown whenever a workflow or safety rule changes.

## Common local failures

- **The UI cannot connect:** Spring Boot is not available at `127.0.0.1:8080`.
- **A deep UI route 404s:** add the route to `WebMvcConfig`.
- **A module 404s:** check its import path and presence under `resources/static`.
- **Search has no semantic results:** enable the vector and embedding plugins.
- **Web search is unavailable:** install and start SearXNG.
- **A client sees a stale tool:** call `tools/list` again.
