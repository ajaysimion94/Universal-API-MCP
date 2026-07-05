# Web toggle validation — search page

## Problem

- Enabling web search toggle when SearXNG not installed/running produces no feedback
- `getNotReadyPlugins()` only checks required plugins (vec store + embedding), not searxng
- The only hint is a passive line buried at the bottom of results
- URL param `?web=1` bypasses any check

## Plan

### 1. Backend — SearchController.java

- Inject `PluginRegistry` into `SearchController`
- When `web=true`, check `pluginRegistry.isReady("searxng")`
- If not ready, add `webReady: false` and `webMessage: "Web augmentation requires the SearXNG plugin — install on the Plugins page"` to the response
- Still return RAG results — don't block the search

### 2. Frontend — api.ts

```typescript
// Add to SearchResponse:
webReady?: boolean;
webMessage?: string;
```

### 3. Frontend — SearchPage.tsx

- On mount, call `listPlugins()` once, store in state
- Derive `searxngReady` — check if searxng plugin has `status === "ACTIVE"`
- **Toggle disabled**: When `!searxngReady`, the web toggle `<input>` gets `disabled` attribute, label gets a `cursor-not-allowed` + tooltip (`title` attribute explaining the requirement, or a styled tooltip)
- **Initial URL check**: If `?web=1` is in URL but `!searxngReady`, show a warning banner above the search results area: amber background, text like "Web search requires the SearXNG plugin → [Go to Plugins](/plugins)"
- **Response warning**: When `res.webReady === false`, show an amber banner in results area

### 4. Backend — verify no regression

- `mvn compile` passes
- `mvn test` (4/4 pass)

### 5. Frontend — verify no regression

- `npm run typecheck` passes
