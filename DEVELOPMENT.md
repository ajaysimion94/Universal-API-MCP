# Development

How to develop the MCP Server. For deployment and configuration, see
[`DEPLOYMENT.md`](DEPLOYMENT.md).

The project has one build toolchain: Java and Maven. The Web UI is browser-native HTML, CSS, and
JavaScript under Spring Boot's static resources; Node.js, npm, a transpiler, and a frontend dev
server are not required.

## Frequently used commands

```sh
# run API, MCP endpoint, background workers, and Web UI on :8080
cd mcp-server
mvn spring-boot:run

# verification
mvn test
mvn -q compile
mvn package -Dskip.bundle=true
mvn clean package -Dskip.models=true  # bundles native libs/SearXNG; upload ONNX files from /plugins

# one backend test
mvn test -Dtest=FileServiceTests#canUploadFileAndItAppearsAsAChild
```

Open **http://127.0.0.1:8080**. Static source edits are visible after a browser refresh. Java
changes are picked up when Spring Boot is restarted.

## Project layout

```text
mcp-server/
├── pom.xml
├── models/                         # extracted ONNX models (gitignored)
├── data/                           # embedded SQLite database (gitignored)
├── lib/                            # extracted sqlite-vec extension (gitignored)
└── src/
    ├── main/
    │   ├── java/com/mcpserver/
    │   │   ├── config/             # Spring configuration and SPA route forwards
    │   │   ├── controllers/        # REST adapters
    │   │   ├── services/           # reusable application logic
    │   │   ├── repositories/       # SQLite/in-memory persistence
    │   │   ├── plugins/            # infrastructure plugins
    │   │   ├── rag/                # retrieval and web research
    │   │   ├── connectors/         # Confluence/Jira/API collections
    │   │   ├── tools/              # imported API tools
    │   │   └── mcp/                # MCP SDK boundary
    │   └── resources/
    │       ├── application.yml
    │       ├── schema.sql
    │       └── static/
    │           ├── index.html      # semantic application shell
    │           ├── app.js          # History API router and top bar
    │           ├── api.js          # all browser API calls
    │           ├── ui.js           # escaping, formatting, SVG icons
    │           ├── styles.css      # design tokens and reset
    │           ├── components.css  # component/page styles
    │           └── pages/          # one ES module per route
    └── test/java/com/mcpserver/
```

## Conventions

- Keep business logic in services/repositories and protocol handling in controllers or `mcp/`.
- Browser API calls belong in `static/api.js`; page modules should not call `fetch` directly.
- Shared DOM safety, formatting, event delegation, and icons belong in `static/ui.js`.
- Each route exports `mount(outlet, context)` and returns an optional cleanup function.
- Dynamic text must pass through `escapeHtml`/`escapeAttr` before entering an HTML template.
- Use semantic HTML and delegated events. Do not add a framework or a browser-side dependency
  without an explicit architecture decision.
- Use the OKLCH tokens in `styles.css`; do not hardcode component colors.
- Read [`.impeccable.md`](.impeccable.md) before UI work.

## Frontend validation

The browser executes ES modules directly, so there is no generated bundle or typecheck phase.
Use the following proportional checks:

```sh
# optional local syntax check when Node happens to be installed; Node is not a project prerequisite
find src/main/resources/static -name '*.js' -print0 | xargs -0 -n1 node --check

# required artifact check; validates resource copying without downloading the large model bundle
mvn package -Dskip.bundle=true

# then smoke the served UI
java -jar target/mcp-server.jar
curl -I http://127.0.0.1:8080/
curl -I http://127.0.0.1:8080/files
curl -I http://127.0.0.1:8080/app.js
```

Browser smoke checks should cover every route, the absence of console errors, API error states, and
at least one mutation flow (for example create/delete a folder). The normal Maven test suite remains
the backend gate.

## How the UI ships

Spring Boot includes `src/main/resources/static/**` in `BOOT-INF/classes/static/` through Maven's
normal resource phase. `index.html` is the welcome page. `WebMvcConfig` forwards known browser
routes such as `/files` and `/connections` to that file, while JavaScript modules and API paths are
served normally.

There is no frontend Maven plugin, resource-copy execution, `webui/dist`, or
`-Dskip.frontend` profile. A clean checkout can build the complete application with only:

```sh
cd mcp-server
mvn package
```

## Testing

```sh
cd mcp-server && mvn test
cd mcp-server && ./scripts/run-eval.sh
# Windows PowerShell
cd mcp-server; .\scripts\run-eval.ps1
```

- `@SpringBootTest` classes boot SQLite and the in-process ML seams and can take several seconds.
- Tests use isolated SQLite configuration and do not intentionally modify
  `mcp-server/data/mcpserver.db`.
- `scripts/run-eval.sh` and `scripts/run-eval.ps1` are the macOS/Linux and Windows runners for the
  judged retrieval regression gate inside the standalone `mcp-server` folder. The test accepts
  either bundled model files or the pinned files manually installed under `models` from the Plugins
  page; fixtures and generated `eval-runs` also stay inside the module.
- `scripts/run-replay.sh` evaluates logged traffic from a safe database snapshot and also keeps its
  snapshot and report under the module's ignored `eval-runs` directory.

## Troubleshooting

- **UI stays on “connecting…”** — Spring Boot is not available at `127.0.0.1:8080`, or the relevant
  API endpoint failed. Check the server log and browser console.
- **A deep route returns 404** — add an explicit forward in `WebMvcConfig`.
- **A module returns 404** — ensure the import uses an absolute or correct relative path and the
  file exists under `src/main/resources/static`.
- **A browser reports a syntax error** — run `node --check` when available, then inspect the exact
  module in the browser console.
- **Local search contains stale chunks** — stop the app and delete
  `mcp-server/data/mcpserver.db` only when an intentional full reset is acceptable.
