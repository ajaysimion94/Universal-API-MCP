# AGENTS.md

High-signal notes for OpenCode sessions. Read this before working in this repo.

## Source of truth

- `docs/product-idea.md` — architecture blueprint (**what** and **why**). All `§` references across the repo point into it.
- `docs/plan.md` — execution tracker (**in what order**, E2E checklists per phase). Check which phase is current before adding features; deliver per the phase's build + E2E checklist.
- `.impeccable.md` — **read this before any UI/design work**. It holds the design context (users, brand, aesthetic direction, anti-references) that the `impeccable` skill requires. Do not start frontend work without it.
- `DECISIONS.md` — append-only log of significant choices made (stack, scope, sequencing, conventions). **Read it for context, and add an entry whenever you make a non-trivial decision** — don't let decisions live only in chat.
- `DEVELOPMENT.md` — dev workflow, project layout, testing, build internals. `DEPLOYMENT.md` — setup, build, run, config, API reference. `SETUP-WINDOWS.md` / `SETUP-LINUX.md` — platform setup. `README.md` is the navigation hub.

## Layout

- `mcp-server/` — the only buildable module. Java 21 + Spring Boot 3.3.4 backend, React 18 + TypeScript + Vite SPA in `mcp-server/webui/`.
- `docs/` — pre-implementation docs; the repo was docs-first and `mcp-server/` landed in Phase 1.
- No monorepo tooling; it's a single Maven module that also builds the frontend.

## Commands (frequently used)

```sh
# build + run
cd mcp-server && mvn package && java -jar target/mcp-server.jar
# dev mode (two terminals, open http://localhost:5173)
cd mcp-server && mvn spring-boot:run -Dskip.frontend=true
cd mcp-server/webui && npm run dev
# tests + fast loops
cd mcp-server && mvn test
cd mcp-server && mvn test -Dtest=FileServiceTests#canUploadFileAndItAppearsAsAChild   # single test
cd mcp-server && mvn package -Dskip.frontend=true      # JAR only, skip SPA rebuild
cd mcp-server/webui && npm run typecheck                # tsc --noEmit (script is "typecheck", not "tsc")
```

Full command reference + project layout: [`DEVELOPMENT.md`](DEVELOPMENT.md). Setup + API + config: [`DEPLOYMENT.md`](DEPLOYMENT.md).

## Dev mode wiring (non-obvious)

Two-process dev mode is the norm, not a single process:

- Terminal 1: `mvn spring-boot:run -Dskip.frontend=true` → backend on `127.0.0.1:8080`
- Terminal 2: `cd webui && npm run dev` → Vite on `:5173`
- Open **http://localhost:5173** (not 8080). `vite.config.ts` proxies `/api/*` → `127.0.0.1:8080`. If the UI hangs on "connecting…", the backend isn't up on 8080.

## How the SPA ships inside the JAR

`mvn package` runs `frontend-maven-plugin` (installs Node/npm into `target/`, runs `npm ci` + `npm run build` in `webui/`), then `maven-resources-plugin` copies `webui/dist/` into `src/main/resources/static/` so it lands at `BOOT-INF/classes/static/` in the JAR. Spring Boot serves `index.html` as the welcome page; `WebMvcConfig` forwards unknown non-API paths to `index.html` for client-side routing. **If you skip the frontend build, there is no `static/index.html` and the UI 404s** — either run `mvn package` once, or use the Vite dev server.

## Hard constraints from the plan (don't violate)

- **`server.address=127.0.0.1` is a deliberate guardrail**, not a bug. No auth until Phase 6; the server runs on trusted internal networks only. Do not change the bind or add public exposure without implementing Phase 6.
- **No Docker / Kubernetes / virtualization.** Single runnable JAR, in-process ML inference, natively installed backing services. Revisited only in Phase 5+ if the constraint lifts.
- **Open-source-first.** Proprietary services (Microsoft Graph/Entra for Teams) are deferred to Phase 4.
- **ACL tags are captured on every chunk from Phase 2; enforcement lands in Phase 6.** Never defer capture. When adding an intake path, tag chunks before first merge.
- **Auth & access control land last (Phase 6).** Don't add Spring Security / OAuth2 / JWT / Keycloak before that phase.
- **E2E or it didn't ship.** A phase exits only when its E2E test checklist in `docs/plan.md` passes end to end.

## Backend conventions

- Package root: `com.mcpserver`. Module subpackages per `docs/product-idea.md` §4: `config`, `controllers`, `services`, `repositories`, `models`, plus `rag/{chunking,embedding,retrieval,reranker}` and others as phases land.
- **Separate business logic from MCP protocol handling** — `services/`/`repositories/` reusable outside MCP; `mcp/` only adapts to the protocol; the Web UI is a REST channel over the same services, never a second implementation.
- The chunk store uses **PostgreSQL + pgvector** (direct JDBC via `JdbcTemplate`, not JPA — pgvector's `vector` type has no JPA binding). `schema.sql` runs on startup (`spring.sql.init.mode=always`); the `vector` extension must be installed externally first.
- The RAG pipeline lives in `rag/{chunking,embedding,retrieval,reranker,web}` with swappable seams (`EmbeddingClient`, `SearchPipeline`, `Reranker`, `WebFetcher`). The default embedding impl runs **nomic-embed-text-v1.5 ONNX in-process** via ONNX Runtime + DJL tokenizer — the model files live in `mcp-server/models/` (gitignored, ~131MB; download separately). Web augmentation uses a local **SearXNG** instance (native Python process on `127.0.0.1:8888`) — must be started separately.
- File nodes are still **in-memory** (`InMemoryFileRepository`) — resets on restart. Chunks persist in Postgres. On restart, orphaned chunks from deleted/re-uploaded files may remain because the file IDs reset; truncate `chunks` if needed.
- Errors: `IllegalArgumentException` → 400, `IllegalStateException` → 409, handled in controller `@ExceptionHandler` methods. Follow that pattern for new controllers.

## Frontend conventions

- **TypeScript is strict** (`noUnusedLocals`, `noUnusedParameters`, `noFallthroughCasesInSwitch`). Unused imports/vars **fail the build**. The `tsc -b` in `npm run build` is a real gate.
- CSS is split into two files imported in `src/main.tsx`: `styles.css` (design tokens — OKLCH, dark theme, amber accent) and `components.css` (component styles). Use the tokens; don't hardcode colors.
- Design direction is **refined utilitarian, dark, amber accent, anti-AI-slop** (no side-stripe borders, no gradient text, no glassmorphism, no glow). See `.impeccable.md`.
- Fonts: Hanken Grotesk (UI sans) + JetBrains Mono (paths, IDs, metadata). Already loaded in `index.html`.
- Icons live in `src/icons.tsx` — inline SVG, 16px default, `currentColor`. Add new ones there rather than pulling an icon library.
- API calls go through `src/api.ts` — keep fetch logic there, not in components.

## Testing

- Backend: JUnit 5 + Spring Boot Test + AssertJ. `@SpringBootTest` in `FileServiceTests` boots the full context (including ONNX model load + Postgres) — tests take ~7s. Run a single test: `mvn test -Dtest=FileServiceTests#canUploadFileAndItAppearsAsAChild`.
- **Tests write to the real `mcpserver` Postgres DB** (chunks from test uploads persist). Truncate `chunks` before a clean run: `psql -d mcpserver -c "TRUNCATE chunks;"`.
- Frontend: no test runner configured yet. `npm run typecheck` is the only gate.
- Phase 1 adds a golden-set eval harness (`eval-harness/golden-set/`) + CI regression gate — not yet present.

## Setup prerequisites

Java 21+, Maven 3.9+, Node 20+ (built on 24), npm 10+. Postgres 15 with **pgvector 0.8.4** installed (built from source against pg15 — the Homebrew bottle only ships for pg17/18). The `mcpserver` DB must exist with `CREATE EXTENSION vector` run once. The nomic-embed-text-v1.5 ONNX model + tokenizer must be downloaded to `mcp-server/models/nomic-embed-text-v1.5/` (gitignored, ~131MB).

```sh
# pgvector (built against pg15 — Homebrew bottle is pg17/18 only)
git clone --depth 1 --branch v0.8.4 https://github.com/pgvector/pgvector.git /tmp/pgvector
cd /tmp/pgvector && make PG_CONFIG=$(pg_config --bindir)/pg_config install
createdb mcpserver && psql -d mcpserver -c "CREATE EXTENSION vector;"

# nomic embedding model (~131MB quantized ONNX)
mkdir -p mcp-server/models/nomic-embed-text-v1.5
cd mcp-server/models/nomic-embed-text-v1.5
curl -L -o model_quantized.onnx "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/resolve/main/onnx/model_quantized.onnx"
curl -L -o tokenizer.json "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/resolve/main/tokenizer.json"

# SearXNG (web search toggle — native Python process, open-source)
git clone --depth 1 https://github.com/searxng/searxng.git /tmp/searxng
cd /tmp/searxng && python3 -m venv venv && source venv/bin/activate
pip install -r requirements.txt && pip install --no-build-isolation -e .
# Enable JSON API in settings.yml: search.formats: [html, json], server.port: 8888, server.limiter: false
SEARXNG_SETTINGS_PATH=/path/to/settings.yml ./venv/bin/searxng-run
```
