# MCP Server

An enterprise MCP (Model Context Protocol) server — Java 17+ / Spring Boot 3 backend with a first-party
browser-native HTML/CSS/JavaScript Web UI. Files & folders manager, RAG search over uploaded documents (embedded
SQLite + sqlite-vec + in-process ONNX embeddings and cross-encoder reranking), and contextual
web research through an optional SearXNG toggle.
Currently in **Phase 1** per [`docs/plan.md`](docs/plan.md).

> Architecture blueprint: [`docs/product-idea.md`](docs/product-idea.md) · Execution tracker:
> [`docs/plan.md`](docs/plan.md) · Agent notes: [`AGENTS.md`](AGENTS.md) · Decision log:
> [`DECISIONS.md`](DECISIONS.md)

---

## Frequently used commands

```sh
# build + run everything (one JAR, serves SPA + API on :8080)
cd mcp-server && mvn package && java -jar target/mcp-server.jar

# Windows/offline alternative — install the pinned ONNX files later from /plugins
cd mcp-server && mvn clean package -Dskip.models=true && java -jar target/mcp-server.jar

# dev mode — one process; static UI changes are available after a browser refresh
cd mcp-server && mvn spring-boot:run

# verification
cd mcp-server && mvn test                                     # backend tests
cd mcp-server && ./scripts/run-eval.sh                        # macOS/Linux P@1/MRR/nDCG gate
cd mcp-server; .\scripts\run-eval.ps1                         # Windows PowerShell equivalent
cd mcp-server && mvn -q compile                               # fast compile + static resource copy
```

Open **http://127.0.0.1:8080**. Search is the landing
page; files & folders is at `/files`; plugins is at `/plugins`.

---

## Guides

The app includes a **Guide** page for setup, knowledge search, API tools, dashboards, MCP clients,
and development. The durable references are [Developer Guide](docs/developer-guide.md) and
[MCP Client Guide](docs/mcp-client-guide.md).

---

## Where to go next

| If you want to… | Read this |
| --- | --- |
| **Set up and run the app** (prerequisites, build, run, config) | [`DEPLOYMENT.md`](DEPLOYMENT.md) |
| **Develop** (hot reload, project layout, testing, build internals) | [`DEVELOPMENT.md`](DEVELOPMENT.md) |
| **Build or operate the server** (code locations, query grammar, dashboards, guide system) | [`docs/developer-guide.md`](docs/developer-guide.md) |
| **Connect an MCP client** (resources, prompts, grounding, approvals) | [`docs/mcp-client-guide.md`](docs/mcp-client-guide.md) |
| **Understand the architecture** (vision, modules, RAG pipeline, roadmap) | [`docs/product-idea.md`](docs/product-idea.md) |
| **See what phase we're in and what's next** | [`docs/plan.md`](docs/plan.md) |
| **Work as an AI agent in this repo** (conventions, gotchas, commands) | [`AGENTS.md`](AGENTS.md) |
| **See past decisions** (stack, scope, sequencing) | [`DECISIONS.md`](DECISIONS.md) |

---

## Cross-platform

The app is cross-platform across macOS, Linux, and Windows x64. Windows semantic search additionally
requires the Microsoft Visual C++ 2015–2022 Redistributable (x64). Application dependencies are
embedded in the JAR or downloaded on demand via the **Plugins** page (`/plugins`):

| Component | How it works |
| --- | --- |
| **Java 17+ / Maven 3.9+** | Java 17 is the compiled baseline; JDK 17, 20, 21, and newer can build and run the same JAR. No Node.js or npm installation is required. |
| **SQLite + sqlite-vec** | Embedded in the JAR via `org.xerial:sqlite-jdbc`. The sqlite-vec native extension (~1-2MB) is downloaded by the Plugins page per OS/arch. |
| **Nomic embedding + MiniLM reranker ONNX models** | Bundled into the JAR by default. `-Dskip.models=true` leaves them out; the Plugins page provides the pinned downloads and checksum-verified manual upload. ONNX Runtime auto-loads the right native library per platform; Windows requires an x64 JDK and the [Microsoft Visual C++ 2015–2022 Redistributable (x64)](https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist?view=msvc-170). |
| **SearXNG (web toggle)** | Native Python process managed by the Plugins page. Requires Python 3.10+ installed on the system. |
| **Shell syntax** | macOS/Linux: bash. Windows: PowerShell or cmd. The app commands are identical. |
| **`application.yml` / `schema.sql` / REST API / SPA** | Identical on every OS. |

**Rule of thumb:** install Java and Maven, then `mvn package && java -jar target/mcp-server.jar`
works the same everywhere. If Maven cannot reach Hugging Face, run
`mvn clean package -Dskip.models=true`, start the app, and install both pinned model/tokenizer pairs
under **Plugins → ONNX model files**.
