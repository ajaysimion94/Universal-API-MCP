# MCP Server

An enterprise MCP (Model Context Protocol) server — Java 21 / Spring Boot 3 backend with a first-party
React + TypeScript Web UI. Files & folders manager, RAG search over uploaded documents (pgvector +
in-process ONNX embeddings), and an optional web-search toggle (SearXNG). Currently in **Phase 1**
per [`docs/plan.md`](docs/plan.md).

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
page; files & folders is at `/files`.

---

## Where to go next

| If you want to… | Read this |
| --- | --- |
| **Set up and run the app** (prerequisites, Postgres, pgvector, ONNX model, SearXNG) | [`DEPLOYMENT.md`](DEPLOYMENT.md) |
| **Develop** (hot reload, project layout, testing, build internals) | [`DEVELOPMENT.md`](DEVELOPMENT.md) |
| **Set up on Windows** (Visual Studio for pgvector, PowerShell, no bash scripts) | [`SETUP-WINDOWS.md`](SETUP-WINDOWS.md) |
| **Set up on Linux** (apt/dnf/pacman, build-essential, systemd, SELinux) | [`SETUP-LINUX.md`](SETUP-LINUX.md) |
| **Understand the architecture** (vision, modules, RAG pipeline, roadmap) | [`docs/product-idea.md`](docs/product-idea.md) |
| **See what phase we're in and what's next** | [`docs/plan.md`](docs/plan.md) |
| **Work as an AI agent in this repo** (conventions, gotchas, commands) | [`AGENTS.md`](AGENTS.md) |
| **See past decisions** (stack, scope, sequencing) | [`DECISIONS.md`](DECISIONS.md) |

---

## Cross-platform — read this before setup

The app itself (`mvn package` → `java -jar`) is **fully cross-platform**. Only the **three native
prerequisites** and **shell syntax** differ by OS. Pick your platform's setup guide above.

| Component | Cross-platform? | What varies by OS |
| --- | --- | --- |
| **Java 21 / Maven / Node 20+ / npm** | ✅ identical runtime | Only the **install method** differs (Homebrew / apt / dnf / winget / Adoptium / SDKMAN / nvm). Binaries and all `mvn`/`npm`/`java -jar` commands are identical. |
| **PostgreSQL 15+** | ⚠️ install + auth differ | macOS: `brew install postgresql@15` (trust auth). Linux: `apt install postgresql-15` / `dnf install postgresql15-server` (peer/scram auth). Windows: EDB installer (md5 auth). |
| **pgvector 0.8.4** | ⚠️ build tools differ | Homebrew bottle is pg17/18 only → **all platforms build from source**. macOS/Linux: `make PG_CONFIG=... install` (Xcode CLT / `build-essential`). Windows: MSVC `nmake` (Visual Studio Build Tools) **or** pre-built `vector.dll`. |
| **nomic-embed-text-v1.5 ONNX model** | ✅ files are platform-agnostic | Same `curl` download everywhere. ONNX Runtime auto-loads the right native lib (x86_64 shipped; linux-aarch64 may need a manual lib). |
| **SearXNG (web toggle)** | ⚠️ process + shell differ | Native Python process on all platforms, **not** bundled in the JAR. macOS/Linux: `source venv/bin/activate` + `searxng-run` (bash). Windows: `venv\Scripts\activate` + `python -m searx.webapp` (no bash script). Settings YAML is identical. |
| **Shell syntax** | ⚠️ differs | macOS/Linux: bash (`export VAR=val`, `source ...`, `&&`). Windows: PowerShell (`$env:VAR = "val"`, `venv\Scripts\activate`, `;`) or cmd. |
| **`curl`** | ✅ commands identical | macOS/Linux: `curl` works. Windows 10+: use `curl.exe` (PowerShell aliases `curl` → `Invoke-WebRequest`). |
| **`application.yml` / `schema.sql` / REST API / SPA** | ✅ identical | Same config, SQL, endpoints, and React build on every OS. |

**Rule of thumb:** install Java/Maven/Node, then install the three native prerequisites for your OS,
then `mvn package && java -jar target/mcp-server.jar` works the same everywhere.
