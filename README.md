# MCP Server

An enterprise MCP (Model Context Protocol) server — Java 21 / Spring Boot 3 backend with a first-party
React + TypeScript Web UI. Files & folders manager, RAG search over uploaded documents (embedded
SQLite + sqlite-vec + in-process ONNX embeddings), and an optional web-search toggle (SearXNG).
Currently in **Phase 1** per [`docs/plan.md`](docs/plan.md).

> Architecture blueprint: [`docs/product-idea.md`](docs/product-idea.md) · Execution tracker:
> [`docs/plan.md`](docs/plan.md) · Agent notes: [`AGENTS.md`](AGENTS.md) · Decision log:
> [`DECISIONS.md`](DECISIONS.md)

---

## Frequently used commands

```sh
# build + run everything (one JAR, serves SPA + API on :8080)
cd mcp-server && mvn package && java -jar target/mcp-server.jar

# dev mode — two terminals (open http://localhost:5173, not 8080)
cd mcp-server && mvn spring-boot:run -Dskip.frontend=true     # terminal 1: backend
cd mcp-server/webui && npm install && npm run dev             # terminal 2: frontend (HMR)

# fast loops
cd mcp-server && mvn test                                     # backend tests
cd mcp-server && mvn package -Dskip.frontend=true             # build JAR, skip SPA rebuild
cd mcp-server/webui && npm run typecheck                      # frontend type check
```

Open **http://127.0.0.1:8080** (JAR) or **http://localhost:5173** (dev mode). Search is the landing
page; files & folders is at `/files`; plugins is at `/plugins`.

---

## Where to go next

| If you want to… | Read this |
| --- | --- |
| **Set up and run the app** (prerequisites, build, run, config) | [`DEPLOYMENT.md`](DEPLOYMENT.md) |
| **Develop** (hot reload, project layout, testing, build internals) | [`DEVELOPMENT.md`](DEVELOPMENT.md) |
| **Understand the architecture** (vision, modules, RAG pipeline, roadmap) | [`docs/product-idea.md`](docs/product-idea.md) |
| **See what phase we're in and what's next** | [`docs/plan.md`](docs/plan.md) |
| **Work as an AI agent in this repo** (conventions, gotchas, commands) | [`AGENTS.md`](AGENTS.md) |
| **See past decisions** (stack, scope, sequencing) | [`DECISIONS.md`](DECISIONS.md) |

---

## Cross-platform

The app is **fully cross-platform** — macOS, Linux, and Windows. No OS-specific setup is needed.
All dependencies are embedded in the JAR or downloaded on demand via the **Plugins** page (`/plugins`):

| Component | How it works |
| --- | --- |
| **Java 21 / Maven / Node 20+ / npm** | Install via your preferred method (Homebrew / apt / dnf / winget / SDKMAN / nvm). Binaries and all commands are identical across platforms. |
| **SQLite + sqlite-vec** | Embedded in the JAR via `org.xerial:sqlite-jdbc`. The sqlite-vec native extension (~1-2MB) is downloaded by the Plugins page per OS/arch. |
| **nomic-embed-text-v1.5 ONNX model** | Downloaded (~131MB) by the Plugins page from HuggingFace. ONNX Runtime auto-loads the right native lib per platform. |
| **SearXNG (web toggle)** | Native Python process managed by the Plugins page. Requires Python 3.10+ installed on the system. |
| **Shell syntax** | macOS/Linux: bash. Windows: PowerShell or cmd. The app commands are identical. |
| **`application.yml` / `schema.sql` / REST API / SPA** | Identical on every OS. |

**Rule of thumb:** install Java/Maven/Node, then `mvn package && java -jar target/mcp-server.jar`
works the same everywhere. Go to `/plugins` to install what you need.
