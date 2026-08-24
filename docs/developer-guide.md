# Developer guide

## Insights architecture

Insights is a consume-first dashboard page with an explicit authoring IDE. The default route shows saved insights on the left and the selected dashboard on the right; **Create** and **Edit** enter the IDE. The implementation must preserve one source of truth while making common workflows no-code.

Current source locations:

- Static page module: `mcp-server/src/main/resources/static/pages/insights.js`
- Shared styles: `mcp-server/src/main/resources/static/components.css`
- Structural tests: `mcp-server/src/test/java/com/mcpserver/config/InsightWorkspaceTests.java`

## Authoring model

The IDE should expose:

- dashboard inputs for connected GET requests from one or more collections
- named datasets produced by those inputs
- transform controls for generated request datasets and joined relationship datasets
- relationship blocks that generate joined datasets
- visual bindings to any loaded dataset
- persisted grid layout metadata for dashboard visuals
- Source mode for generated RQL and advanced edits

The first dataset remains named `rows` by default for compatibility with existing documents. The visual-query controls are dataset-aware: selecting a dashboard input, relationship block, or the **Shape dataset** selector reparses and rewrites that dataset's generated pipeline.

Relationship blocks are generated as RQL joins:

```rql
let joined = rows |> join customers on customer_id = id prefix "customers";
```

The first visual implementation deliberately uses the existing backend join stage rather than adding a second execution model. This keeps source as the single source of truth.

Grid layout is also source-backed. Dashboard-level grid settings live in front matter under `grid`, and individual visual placement is stored as component props: `gridX`, `gridY`, `gridW`, and `gridH`. These props are layout metadata only; they must not alter query execution.

Relationship edit/remove behavior:

- Edit loads the parsed relationship into the modeling form.
- Update replaces the generated join base in source and preserves supported shape stages already appended to the joined dataset.
- Renaming a relationship updates dataset references to the derived dataset.
- Remove deletes the generated relationship statement and direct tables bound to that dataset.
- Removing a dashboard input also removes relationship statements that depend on that input.

## Implementation constraints

- Do not create a parallel hidden model that can drift from source.
- Every IDE action must update the generated source.
- Grid controls must update front matter or component props, not hidden local layout state.
- Source edits must invalidate stale selection offsets.
- Custom source must fall back to Source mode or an explicit custom-logic state.
- Dashboard input cards must be keyboard-accessible and must expose status after runs.

## Test expectations

Keep structural tests for:

- dashboard input rendering
- relationship block rendering
- add/remove/rename actions
- edit/remove relationship actions
- generated join source
- generated source behavior
- custom-source fallback
- dataset binding
- custom dashboard grid settings and placement controls
- responsive layout

Run:

```bash
cd mcp-server
mvn test -Dtest=InsightWorkspaceTests
```
