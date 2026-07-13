# Confluence/Jira Connectors — Manual Verification Guide

## Why this doc exists

The automated test suite (`ConfluenceConnectorTests`, `JiraConnectorTests`) runs entirely against
WireMock stubs that this project's own author wrote — they prove the connector code is internally
consistent with an *assumed* API shape, not that the assumption matches a real Confluence/Jira
instance. Two concrete gaps this doc addresses:

- **Jira**: `JiraConnector` calls `POST /rest/api/3/search/jql` first (falling back to the classic
  `GET /rest/api/2/search` on 404/410/400 — see `DECISIONS.md`, 2026-07-13 entries). That Atlassian
  retired the classic endpoint on Cloud is well corroborated by third-party docs and community
  reports. The *exact* request/response shape of the replacement was sourced from community forum
  paraphrasing, not Atlassian's own reference page, and has never been checked against a real
  instance.
- **Confluence**: `ConfluenceConnector` only uses REST API v1 (`/rest/api/content`). Atlassian's v1
  deprecation deadline has been pushed repeatedly (Feb 2024 → Apr 2025) with no confirmed final
  shutdown found — status is genuinely unresolved, not confirmed broken, but also not verified fine.

Running both connectors against real Cloud instances is the only way to close these gaps. This
guide sets that up with disposable, free, no-credit-card test sites so nothing sensitive is at risk.

## 1. Create disposable test instances

Use the **permanently-free plan** (not a time-limited trial) for both — no credit card, doesn't
expire, up to 10 users. Create throwaway sites specifically for this; don't point the connector at
a real production instance for first verification.

### Jira Cloud
1. Sign up at Atlassian's Jira Software Cloud signup page, choosing the **Free** plan.
2. This creates a site at `https://<yoursite>.atlassian.net`.
3. Create one project (any template) with 2–3 test issues, at least one with a comment, so backfill
   has something to ingest and render.

### Confluence Cloud
1. From the same Atlassian account, add Confluence (Free plan) — same site,
   `https://<yoursite>.atlassian.net/wiki`.
2. Create one space with 1–2 pages containing regular text and at least one non-trivial element
   (a code block or table) to see how the storage-format HTML survives the regex-based
   tag-stripping in `IngestionService.extractText()`.

### API token (used for both — same account)
1. Go to `id.atlassian.com` → account settings → **Security** → **API tokens**.
2. Create a token, copy it immediately (shown once).
3. In this app's Connections UI, **username = the Atlassian account email**, **password = the API
   token** (not the account password — Atlassian Cloud no longer accepts account passwords over
   the API; the token is Atlassian's own supported Basic Auth substitute, see `AtlassianAuth.java`).

## 2. Connect and backfill

Via the web UI (`/connections`) or directly against the API:

```bash
curl -X POST http://127.0.0.1:8080/api/connections \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "JIRA",
    "name": "Real Jira Cloud test",
    "baseUrl": "https://<yoursite>.atlassian.net",
    "username": "<your-email>",
    "password": "<api-token>"
  }'
```

Poll the returned `jobId` via `GET /api/connections/jobs/{jobId}` until `status: "completed"`, then
`GET /api/connections/{id}` — `deploymentType` should read `CLOUD` and `status` should read
`CONNECTED`. Trigger a backfill:

```bash
curl -X POST http://127.0.0.1:8080/api/connections/{id}/backfill
```

Repeat the same shape for `"type": "CONFLUENCE"` with `baseUrl` pointing at the same site (no
`/wiki` suffix needed — `ConfluenceConnector` adds it for the Cloud path itself).

## 3. What to check

- [ ] **Jira deployment detection**: confirm `GET /rest/api/2/serverInfo` on a real Cloud site
  returns `deploymentType: "Cloud"` exactly as assumed in `JiraConnector.detectDeployment()`.
- [ ] **Jira search endpoint**: turn on `logging.level.com.mcpserver: DEBUG` (or add a temporary
  log line) and confirm whether `POST /rest/api/3/search/jql` succeeds on the first try (HTTP 200)
  or falls back (404/410/400) — this tells us definitively which path current Cloud actually needs.
  If it succeeds, capture the raw response JSON and compare field names/nesting against what
  `JiraConnector.searchIssues`/`ingestIssue` expect (`issues[].fields.summary`, `.description`,
  `.status.name`, `.assignee.displayName`, `.priority.name`, `.labels`, `.project.key`,
  `.comment.comments[]`, `.updated`, and the top-level `nextPageToken`).
- [ ] **Jira `description` field shape**: confirm it's a plain string under `/rest/api/2/...` (not
  an Atlassian Document Format object) — `ingestIssue()` calls `.asText("")` which silently
  produces an empty string if it's actually a nested ADF object, rather than failing loudly.
- [ ] **Confluence content retrieval**: confirm `/wiki/rest/api/content` still responds on a real
  Cloud site (per the residual risk in `DECISIONS.md`) and that `body.storage.value` still contains
  the expected storage-format XHTML.
- [ ] **Search surfacing**: after backfill, run a query on `/` (universal search) for content only
  the test issue/page could answer and confirm it's cited with the right ACL tags
  (`jira:project:...`, `confluence:space:...`).
- [ ] **Update-in-place**: edit the test issue/page in the real UI, wait for the next poll cycle
  (`connectors.poll-interval-ms`, default 5 min — lower it temporarily for faster iteration), and
  confirm the chunk content updates rather than duplicating.
- [ ] **Delete**: delete the connection via `DELETE /api/connections/{id}` and confirm its chunks
  disappear from search (cascade purge).

## 4. If something doesn't match

If the real response shape differs from what the code assumes:
1. Capture the real JSON (redact anything sensitive) as a fixture.
2. Update the relevant WireMock stub in `ConfluenceConnectorTests`/`JiraConnectorTests` to match
   the real shape — this converts "assumed" into "verified" for future changes.
3. Fix the connector code to match reality.
4. Re-run `mvn test` (full suite) before considering the gap closed.
5. Update the relevant `DECISIONS.md` entry (2026-07-13 Jira/Confluence entries) to record what was
   actually confirmed, replacing the "unverified" language with the real finding.

## 5. Cleanup

These are free, non-expiring sites, so there's no forced cleanup — but since this is throwaway test
data, deleting the site afterward (Atlassian admin settings → site details → delete site) or simply
leaving it dormant are both fine. Rotate/revoke the API token from `id.atlassian.com` once done if
you don't intend to keep testing against it.
