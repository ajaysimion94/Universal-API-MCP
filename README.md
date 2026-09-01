# AI Agent MCP GLM

AI Agent MCP GLM is a local MCP workspace for connecting API collections, running tools, searching local knowledge, and building reusable operational dashboards.

## Product model

The application is organized around connected API capability:

- **Connections** register API collections and MCP-ready tools.
- **Apps** expose enabled read and write operations.
- **Files/Search** provide local knowledge and retrieval workflows.
- **Insights** is a dashboard IDE for combining multiple read requests into meaningful views.

## Insights dashboard IDE

Insights is not intended to be a single-request chart preview. It is a no-code IDE for API product and operations users who need to combine data from multiple requests, collections, or connected apps.

The intended flow is:

1. Add one or more connected GET requests as dashboard inputs from any collection.
2. Run the dashboard to load named datasets.
3. Model relationships with visual join controls when requests share keys.
4. Shape datasets with IDE controls: fields, filters, grouping, sorting, and limits.
5. Bind visuals to datasets: tables, charts, KPI cards, labels, and status blocks.
6. Inspect generated source only when advanced customization is required.
7. Select **Excel** in the result canvas to execute the current RQL and download its summary,
   request-status, and materialized datasets as an `.xlsx` report.

RQL remains the generated source behind the IDE. Normal dashboard creation should not require typing RQL.

## Development

The Java service lives under `mcp-server/`. The React/Vite shell lives under `mcp-server/webui/`, while compatibility pages such as Insights are served from `mcp-server/src/main/resources/static/pages/`.

Useful checks:

```bash
cd mcp-server
mvn test -Dtest=InsightWorkspaceTests
node --check src/main/resources/static/pages/insights.js
```
