---
name: verify
description: Build, launch, and drive mcp-server to verify changes end-to-end (upload → ingestion → search).
---

# Verify mcp-server

## Build

```bash
cd mcp-server
mvn package -DskipTests -q          # full jar incl. webui (frontend-maven-plugin)
mvn test -Dskip.frontend=true -q    # backend-only fast loop
```

## Launch (isolated — don't dirty the repo's ./data)

Everything resolves relative to the working directory: `./data/mcpserver.db`,
`./plugins-state.json`, `./models/nomic-embed-text-v1.5`, `./lib/sqlite-vec`.

```bash
mkdir -p /tmp/run && cd /tmp/run
# A fresh empty dir is the full-plugin scenario: the jar self-extracts the bundled
# model + sqlite-vec lib on first boot (log lines "Extracted bundled …").
# Lexical-only scenario: disable the embedding plugin
#   curl -X POST http://127.0.0.1:18080/api/plugins/nomic-embedding/disable
# (or run a -Dskip.bundle=true jar from an empty dir).
java -jar <repo>/mcp-server/target/mcp-server.jar --server.port=18080
```

Server is up when `GET /api/files` returns the root node (~2s).

## Drive

```bash
curl -s http://127.0.0.1:18080/api/plugins                       # plugin readiness
curl -s -X POST -F "file=@doc.md;type=text/markdown" \
  http://127.0.0.1:18080/api/files/root/upload                   # returns immediately; ingestion is async
curl -s http://127.0.0.1:18080/api/files/ingestion-progress      # poll: phase extracting/chunking/embedding/indexing
curl -s "http://127.0.0.1:18080/api/search?q=term&topK=5"        # mode rag|notReady; lexicalOnly flag when degraded
```

UI: open `http://127.0.0.1:18080/files` in a browser — file rows appear
immediately after upload; the ingest banner tracks background progress
(also on a fresh page load while the queue is active).

## Gotchas

- Ingestion is a background queue (single `ingestion-worker` thread); upload
  responses do NOT wait for it. Wait for `"active":false` before asserting search.
- Vector search returns top-K nearest even for irrelevant queries — don't use
  "0 results for nonsense" as an assertion on the vector leg (lexical leg does return 0).
- A ~200KB markdown doc ≈ 200 chunks ≈ 30s embedding on an M-series Mac,
  ~2s lexical-only — use the bare scenario for fast loops.
