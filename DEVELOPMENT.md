# Development

How to develop the MCP Server — hot reload, project layout, testing, and build internals. For setup
and running the built JAR, see [`DEPLOYMENT.md`](DEPLOYMENT.md). For Windows/Linux-specific setup,
see [`SETUP-WINDOWS.md`](SETUP-WINDOWS.md) / [`SETUP-LINUX.md`](SETUP-LINUX.md).

> **Cross-platform note:** the commands below work identically on macOS, Linux, and Windows
> (PowerShell). Only the native-prerequisite setup differs by OS — see
> [`README.md`](README.md#cross-platform--read-this-before-setup).

---

## Frequently used dev commands

```sh
# dev mode — two terminals, open http://localhost:5173
cd mcp-server && mvn spring-boot:run -Dskip.frontend=true     # terminal 1: backend on :8080
cd mcp-server/webui && npm install && npm run dev             # terminal 2: Vite HMR on :5173

# tests
cd mcp-server && mvn test                                     # backend JUnit tests
cd mcp-server/webui && npm run typecheck                      # frontend tsc --noEmit

# fast backend loop (skip the SPA rebuild)
cd mcp-server && mvn package -Dskip.frontend=true             # build JAR only
cd mcp-server && mvn test -Dtest=FileServiceTests#canUploadFileAndItAppearsAsAChild   # single test
```

---

## Project layout

```
mcp-server/
├── pom.xml                  # Maven build — backend + frontend bundled into one JAR
├── models/                  # nomic-embed-text-v1.5 ONNX model + tokenizer (gitignored, ~131MB)
├── src/
│   ├── main/
│   │   ├── java/com/mcpserver/
│   │   │   ├── McpServerApplication.java    # Spring Boot entrypoint
│   │   │   ├── config/                      # WebMvcConfig (SPA fallback, CORS)
│   │   │   ├── controllers/                 # FileController, SearchController (REST)
│   │   │   ├── services/                    # FileService, IngestionService, SearchService
│   │   │   ├── repositories/                # InMemoryFileRepository, ChunkRepository (JDBC)
│   │   │   ├── models/                      # FileNode, Chunk, BulkUploadResult
│   │   │   └── rag/                         # RAG pipeline (swappable seams)
│   │   │       ├── chunking/                #   Chunker + StructureAwareChunker
│   │   │       ├── embedding/               #   EmbeddingClient + OnnxEmbeddingClient (nomic)
│   │   │       ├── retrieval/               #   SearchPipeline, HybridSearcher, RrfFusion
│   │   │       ├── reranker/                #   Reranker + PassThroughReranker (bge staged)
│   │   │       └── web/                     #   WebFetcher + SearXngWebFetcher
│   │   └── resources/
│   │       ├── application.yml              # config
│   │       ├── schema.sql                   # chunks table (vector(768) HNSW + tsvector)
│   │       └── static/                      # SPA build output lands here (populated by Maven)
│   └── test/java/com/mcpserver/             # JUnit tests
└── webui/                  # React + TypeScript SPA (Vite)
    ├── package.json
    ├── vite.config.ts      # dev proxy /api → 127.0.0.1:8080
    └── src/
        ├── main.tsx        # imports styles.css (tokens) + components.css
        ├── api.ts          # all fetch logic lives here
        ├── icons.tsx       # inline SVG icons (16px, currentColor)
        └── components/     # Topbar, Sidebar, SearchPage, FilesPage, FileTable, Breadcrumbs
```

Conventions (full detail in [`AGENTS.md`](AGENTS.md)):
- **Backend:** `services/`/`repositories/` hold reusable business logic; `controllers/` only adapt
  to REST. Errors: `IllegalArgumentException` → 400, `IllegalStateException` → 409.
- **Frontend:** TypeScript is strict — unused imports/vars **fail the build**. API calls go in
  `src/api.ts`, icons in `src/icons.tsx`, colors in `styles.css` (OKLCH tokens, don't hardcode).
- **Design:** refined utilitarian, dark, amber accent. See [`.impeccable.md`](.impeccable.md).

---

## Development mode (hot reload on both sides)

Run the Spring Boot backend and the Vite dev server side by side. The Vite dev server proxies
`/api/*` to the backend, so you get hot module reload on the UI and live API calls in one window.

```sh
# terminal 1 — backend (skips the frontend build so it starts fast)
cd mcp-server
mvn spring-boot:run -Dskip.frontend=true

# terminal 2 — frontend (Vite dev server with HMR)
cd mcp-server/webui
npm install
npm run dev
```

Open **http://localhost:5173** (the Vite URL, not 8080). If the UI hangs on "connecting…", the
backend isn't up on 8080.

> `-Dskip.frontend=true` skips Node/npm install and the Vite build, so the backend loop is seconds
> instead of tens of seconds. Use it whenever you're iterating on Java only.

---

## Frontend-only commands

```sh
cd mcp-server/webui
npm install        # install deps
npm run dev        # Vite dev server on :5173
npm run build      # tsc -b && vite build → webui/dist/
npm run typecheck  # tsc -b --noEmit (note: script is "typecheck", not "tsc")
```

No frontend test runner is configured yet — `npm run typecheck` is the only gate.

---

## Backend-only commands

```sh
cd mcp-server
mvn test                                     # run all JUnit tests
mvn test -Dtest=FileServiceTests             # run one test class
mvn test -Dtest=FileServiceTests#canUploadFileAndItAppearsAsAChild   # run one test method
mvn package -Dskip.frontend=true             # build JAR without rebuilding the SPA (fast)
mvn spring-boot:run -Dskip.frontend=true     # run backend without the frontend build
mvn -Dskip.frontend=true -q compile          # compile only, no tests/package — fastest check
```

---

## Testing

```sh
cd mcp-server && mvn test              # backend unit/integration tests
cd mcp-server/webui && npm run typecheck   # frontend type check
```

- Backend: JUnit 5 + Spring Boot Test + AssertJ. `@SpringBootTest` in `FileServiceTests` boots the
  full context (ONNX model load + Postgres) — tests take ~7s.
- **Tests write to the real `mcpserver` Postgres DB** (chunks from test uploads persist). Clean with
  `psql -d mcpserver -c "TRUNCATE chunks;"`.
- Frontend: no test runner yet; `npm run typecheck` is the only gate.

---

## How the SPA ships inside the JAR

`mvn package` runs `frontend-maven-plugin` (installs Node/npm into `target/`, runs `npm ci` +
`npm run build` in `webui/`), then `maven-resources-plugin` copies `webui/dist/` into
`src/main/resources/static/` so it lands at `BOOT-INF/classes/static/` in the JAR. Spring Boot serves
`index.html` as the welcome page; `WebMvcConfig` forwards unknown non-API paths to `index.html` for
client-side routing.

**If you skip the frontend build** (`-Dskip.frontend=true`), there is no `static/index.html` and the
UI 404s — either run `mvn package` once, or use the Vite dev server in dev mode.

---

## Dev troubleshooting

- **UI hangs on "connecting…" forever in dev mode** — the backend isn't running on `127.0.0.1:8080`,
  or the Vite proxy in `vite.config.ts` points at the wrong host.
- **404 on a deep UI route** — only happens if the SPA wasn't bundled (you ran with
  `-Dskip.frontend=true` and there's no `static/index.html`). Build the frontend once, or use the
  Vite dev server.
- **`mvn package` fails on the frontend step** — make sure `node -v` is 20+. The plugin downloads its
  own Node/npm into `target/` by default, so a system install is only needed for `npm run dev`.
- **TypeScript build fails on unused imports** — strict mode (`noUnusedLocals`,
  `noUnusedParameters`) is intentional. Remove the unused symbol; don't disable the rule.
- **Tests leave stale chunks in the DB** — `@SpringBootTest` writes to the real `mcpserver` DB.
  Clean with `psql -d mcpserver -c "TRUNCATE chunks;"`.
