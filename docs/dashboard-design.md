# Dashboards (RQD) — Design

**Status:** initial live workspace implemented. `.rqd` front matter, fenced RQL, `Stat`,
`BarChart`, and `DataTable` render in the SPA; filters, saved dashboards, the extended component
catalog, cross-filtering, and Excel export remain planned.
**Companion to:** [`report-query-design.md`](./report-query-design.md) — RQL, the query language this
consumes. Read that first; this document assumes its dataset model.
**Phase:** same caveat — ahead of the tracker. See RQL design §10.

---

## 1. What "HTML and SQL mixed" means here

The ask is a document where queries and presentation live together and produce an interactive
dashboard. This is an established genre — Evidence.dev (markdown + SQL + components), Observable
Framework (markdown + JS), MDX. The genre works because the alternative (a click-built dashboard
whose logic is buried in a UI) is unversionable and unreviewable.

Adopting it here means one new document type, `.rqd`:

- **Markdown** carries prose, headings, and layout — free, familiar, diffable.
- **Fenced ` ```rql ` blocks** define named datasets using the language already designed.
- **Component tags** (`<BarChart …/>`) place visualizations.
- **`{{ expression }}`** interpolates scalar values into prose.

The result renders in the SPA as a live dashboard and exports to Excel as a projection (§9).

### 1.1 Separation from RQL

RQL stays presentation-free. Its `report { }` block (RQL design §3.5) covers the *Excel-only* case
— a workbook with no interactivity. RQD is the richer target.

| | `.rql` | `.rqd` |
| --- | --- | --- |
| Contains | queries only, reusable | prose + queries + components |
| Output | dataset library / workbook | interactive dashboard (+ workbook export) |
| Imported by | other `.rql`, any `.rqd` | nothing — it is a leaf |

A `.rqd` may `import "shared/queries.rql"` to reuse `let` bindings rather than restating them.

---

## 2. Document format

````markdown
---
title: API Health
connection: jsonplaceholder
params:
  window:  { type: dateRange, default: last_30_days }
  minUser: { type: number,    default: 1 }
---

# API Health

```rql
let posts = request "List all posts"
  |> where userId >= $minUser
  |> parse date createdAt format "yyyy-MM-dd";

let by_user = posts
  |> group by userId agg count(*) as posts
  |> order by posts desc;

let done = request "List all todos" |> where completed is true;
```

<Filter param="window" />

<KpiRow>
  <Stat value={count(posts)}  label="Posts"          delta="7d" />
  <Stat value={count(done)}   label="Completed todos" />
  <Stat value={avg(by_user.posts)} label="Posts per user" format="0.0" />
</KpiRow>

Traffic is concentrated in {{ count(by_user |> where posts > 10) }} heavy users.

<BarChart data={by_user} x="userId" y="posts" title="Posts per user" />

<DataTable data={posts} columns={["id", "title", "createdAt"]} />
````

Front matter is YAML (SnakeYAML is already a dependency). `params` declares the interactive
surface; every param is addressable as `$name` inside every ` ```rql ` block in the document.

---

## 3. Component catalog

Chosen by the *job the data does*, not by chart-type familiarity. Anything not on this list is
deliberately absent (§4).

### 3.1 Not-a-chart forms

| Component | Job | Replaces the mistake of |
| --- | --- | --- |
| `<Hero>` | the one number a dashboard leads with | — |
| `<Stat>` | single value + optional delta + sparkline | a one-bar bar chart |
| `<KpiRow>` | a handful of headline numbers | a grouped bar chart of unrelated measures |
| `<Meter>` | one ratio against a limit | a 2-slice pie |
| `<DataTable>` | > ~7 classes that all carry meaning | more colors |

### 3.2 Charts

| Component | Job | Color job |
| --- | --- | --- |
| `<BarChart>` / `<ColumnChart>` | compare magnitude | sequential, or 1 categorical |
| `<LineChart>` | trend over time, ≥ 1 series | categorical |
| `<AreaChart>` | trend, single series | sequential |
| `<StackedBar>` | part-to-whole | categorical |
| `<DivergingBar>` | above/below a baseline | diverging |
| `<Heatmap>` | magnitude over a grid | sequential |
| `<Dumbbell>` | before → after per item | one hue, two shades |
| `<Scatter>` | correlation (**≤ 3 series**, see §4.9) | categorical |
| `<Emphasis>` | one series is the point, rest are context | accent + gray |

`<Emphasis>` is called out deliberately: it is the most underused form and usually the honest
answer to "make this chart clearer." It is also the only component that may use the brand amber
(§5.2).

---

## 4. Guardrails — anti-patterns made unrepresentable

This is the central design idea. A dashboard *builder* lets users make bad charts and hopes they
don't. A dashboard *language* can make the bad chart impossible to express.

Each rule below is enforced structurally (the syntax has no way to say it) or by an analyzer
diagnostic, not by documentation.

### 4.1 No dual-axis — structural

There is **no `y2` prop**. A chart has one value axis. Two measures of different scale must become
two charts, small multiples, or both indexed to a common base.

This is the single most misleading chart form: the alignment of two scales is arbitrary, so the
chart invents a correlation that isn't in the data. Making it unrepresentable is worth more than
any amount of guidance.

### 4.2 No author-supplied colors — structural

There is **no `color="#ff0000"` prop**. Color is requested by *role*:

```jsx
<BarChart data={by_user} x="userId" y="posts" colorBy="region" />   // categorical, auto-assigned
<Heatmap  data={grid} scale="sequential" />                          // one-hue ramp
<StackedBar data={survey} scale="diverging" center="neutral" />      // two poles + gray midpoint
```

The runtime resolves roles against the validated palette (§5). Eyeballed hex values cannot enter a
chart, so the palette cannot silently drift out of validation.

### 4.3 Color follows the entity, never the rank — runtime

Slot assignment hashes the **series key** (the domain value, e.g. `"eu-west"`), not its row index.
Filtering out a series therefore never repaints the survivors. A reader who learned "eu-west is
blue" stays right.

### 4.4 Never more than 8 categorical series — runtime

The 9th distinct series is **folded into "Other"** automatically, with an `RQD301` info diagnostic
naming what was folded. Hues are never generated or cycled; a generated 9th hue is
indistinguishable from an existing slot under CVD.

### 4.5 No per-chart filters — structural

`<Filter>` is only valid as a direct child of the document or a `<FilterRow>`, never inside a chart
component. One filter row scopes everything below it, and all charts re-render against the same
slice. A filter nested in a chart card is a parse error (`RQD012`).

> **As shipped (2026-07-31):** implemented as `RQI012`. This required giving the tag scanner a
> nesting model — it previously had none, so containment could not be expressed at all and
> `<BarChart>…<Filter/></BarChart>` parsed as two unrelated siblings. `<FilterRow>` is still not a
> component, and `<Filter>` itself renders nothing (`RQI311`); parameter controls come from
> front-matter `params`. See `docs/query-language-reference.md` for shipped behaviour.

### 4.6 Every chart has a table twin — runtime

The renderer always emits an accessible table alongside each chart, reachable from the card header.
It is not a prop and cannot be disabled. This makes "a tooltip is the only way to read a value"
impossible by construction, and satisfies the accessibility requirement without author effort.

### 4.7 Selective labels by default — runtime

`label` defaults to `"selective"` (endpoint, extreme, or the series that matters). `label="all"` is
accepted but raises `RQD302`, because a number on every point is chaos and goes unread.

### 4.8 Form lints — analyzer

| Condition | Code | Message |
| --- | --- | --- |
| `<BarChart>` over a 1-row dataset | `RQD310` | "One bar — use `<Stat>`; the number is the chart." |
| `<PieChart>` … | — | component does not exist; ≤ 6-segment part-to-whole is `<StackedBar>` |
| `colorBy` on a nominal field in a single-series bar | `RQD311` | "Value-ramp on nominal categories double-encodes bar length; use one hue." |
| `scale="sequential"` with a `colorBy` of unordered categories | `RQD312` | "Sequential encodes magnitude; this field has no order." |
| `<Heatmap>` with > 7 meaningful classes | `RQD313` | "Past ~7 bins adjacent classes blur; consider `<DataTable>`." |

### 4.9 Scatter caps at three series — analyzer, computed

Scatter, bubble, and small-multiples are *all-pairs* forms — any two marks can sit adjacent, so
every pair must clear CVD separation, not just neighbors. Verified against this project's surface:

```
$ node validate_palette.js "#3987e5,#d95926,#199e70" --mode dark --surface "#211d19" --pairs all
  [PASS] CVD separation      worst all-pairs #199e70↔#d95926 ΔE 9.4 (deutan)
  [PASS] Normal-vision floor worst all-pairs #199e70↔#3987e5 ΔE 20.9
  → ALL CHECKS PASS
```

Three slots pass all-pairs; eight cannot, under any ordering. A 4th scatter series is therefore an
**error** (`RQD314`), not a warning — the fix is folding to "Other" or faceting, never a palette
change.

---

## 5. Visual system

### 5.1 The categorical palette — validated, not chosen

Chart surface is the existing `--bg-surface` token, `oklch(0.235 0.01 75)` = **`#211d19`**.

The palette was validated against that exact surface rather than assumed:

```
$ node validate_palette.js "#3987e5,#d95926,#199e70,#c98500,#d55181,#008300,#9085e9,#e66767" \
    --mode dark --surface "#211d19"
  [PASS] Lightness band       all 8 inside L 0.48–0.67
  [PASS] Chroma floor         all 8 >= 0.1
  [PASS] CVD separation       worst adjacent #c98500↔#199e70 ΔE 8.4 (protan) · tritan 8.7
  [PASS] Normal-vision floor  worst adjacent #d55181↔#c98500 ΔE 19.3
  [PASS] Contrast vs surface  all 8 >= 3:1
  → ALL CHECKS PASS
```

| Slot | Hue | Dark step |
| --- | --- | --- |
| 1 | blue | `#3987e5` |
| 2 | orange | `#d95926` |
| 3 | aqua | `#199e70` |
| 4 | yellow | `#c98500` |
| 5 | magenta | `#d55181` |
| 6 | green | `#008300` |
| 7 | violet | `#9085e9` |
| 8 | red | `#e66767` |

Order is fixed and is itself the CVD-safety mechanism — it must not be changed without re-running
the validator. These become `--series-1 … --series-8` in `styles.css`, alongside the existing
tokens.

**Sequential** (magnitude): single blue hue, light→dark, no darker than `#184f95` on this surface
(2.15:1 floor). **Diverging**: blue ↔ orange poles with a neutral gray midpoint — never two cool
hues, never a hue at the midpoint.

### 5.2 The amber accent is reserved — and here is why, measured

`.impeccable.md` principle 3: *"One accent, used like punctuation. If everything is amber, nothing
is."* That principle turns out to be enforced by the numbers, not just taste:

```
$ node validate_palette.js "#f5a33a,#c98500" --mode dark --surface "#211d19"
  [FAIL] Lightness band        outside band: [["#f5a33a", 0.78]]
  [FAIL] Normal-vision floor   #c98500↔#f5a33a ΔE 11.1 (normal) — below 15
```

The brand amber `#f5a33a` (a) sits above the dark band's L ≤ 0.67 ceiling, and (b) is only ΔE 11.1
from categorical slot 4 — **below the hard floor of 15**, meaning full-color readers would struggle
to tell an amber mark from a slot-4 mark.

**Rule:** amber is never a categorical series slot. It is reserved for `<Emphasis>` (where every
other series is gray, so no collision is possible), selection, hover, and focus. This is the design
principle and the measurement agreeing.

One residual: an amber UI chip (active filter) may sit near a chart using slot 4. That is chrome
beside a mark rather than two marks in one plot — acceptable, and the reason the rule is scoped to
*within a chart*.

### 5.3 Marks

Following the project's flat, dense, anti-decorative direction — which happens to coincide with
good chart practice:

- Thin marks; hairline grid and axes, **solid**, one shade off the surface (never dashed — dashing
  reads as "threshold" when it's just a grid).
- 2px surface-colored gap between adjacent bars and stacked segments; a 2px surface ring on
  overlapping markers. A gap, never a border drawn around marks.
- 4px rounded data-ends anchored to the baseline; 2px line strokes; ≥ 8px markers with a ≥ 24px hit
  area.
- Labels render inside a mark only when they fit with padding; otherwise outside the end, otherwise
  in the tooltip and the table twin. Never clipped.
- Card height includes the x-axis band — no nested scrollbar because the axis didn't fit.
- Hero and stat values use the existing sans (`--font-sans`) with **proportional** figures.
  `tabular-nums` only in table rows and axis ticks, where digits align vertically.
- No glow, no gradient fills, no drop shadows, no decorative sparklines — already banned by
  `.impeccable.md`, and equally banned here.

### 5.4 Status vs series

Status colors (good / warning / serious / critical) are reserved and always ship with an icon and
label, never color alone. The existing `--danger` token supplies critical. A series that *means*
good/bad (error rate, pass/fail) wears status tokens; a series that is merely "series 4" wears
categorical. Never both in one chart.

---

## 6. Interactivity

### 6.1 Params

Front-matter `params` become typed inputs. `<Filter param="window" />` renders the control
appropriate to the declared type (`dateRange`, `number`, `string`, `enum`, `multi`).

Changing a param:
1. Updates the param map.
2. Re-runs only the queries whose dependency graph touches that param (the analyzer already
   computes `$var` references per `let`).
3. Holds the previous render at reduced opacity while refetching — **no skeleton flash, no layout
   jump.**

### 6.2 Cross-filtering

`<BarChart … onSelect="userId" />` writes the clicked category into the named param. This composes
with §6.1 without a new mechanism: selection is just another way to set a param. Deliberately
minimal for v1 — no implicit cross-filtering between charts, because implicit linkage is the thing
that makes click-built dashboards unpredictable.

### 6.3 Live editing

Split pane: editor left, rendered dashboard right, same page. The document re-analyzes on the
existing debounce and re-renders. Query execution is *not* on the keystroke path — only analysis
is; data refetches on explicit run or param change.

---

## 7. Rendering architecture

**Client-side React, hand-rolled SVG, no charting library.**

Rationale:

- The SPA has exactly three runtime dependencies today. Recharts/visx/Plotly would be the largest
  thing in the bundle and would fight every mark spec in §5.3 — 2px surface gaps, selective labels,
  and rounded data-ends are all easier to write directly than to override.
- The mark set is small (rect, line, path, circle, text). Charts here are ~150 lines of SVG each,
  not a framework.
- Exact control is the point: the guardrails in §4 only hold if the renderer owns the marks.

So: `webui/src/viz/` holds one component per §3 entry plus shared `Axis`, `Legend`, `Tooltip`,
`TableTwin`, and a `scales.ts` (linear/band/time, ~80 lines). CodeMirror remains the only
substantial new dependency, and it belongs to the editor, not the charts.

**Data flow:** `POST /api/dashboards/data` returns JSON datasets for the document's queries at the
current params; components render from that JSON. Results are cached per `(query, params)` through
the existing `cache/CacheService`.

---

## 8. Endpoints

Extending the RQL surface (RQL design §7), same conventions.

```
POST /api/dashboards/analyze
{ "source": "<.rqd text>", "connectionId": "…", "cursorOffset": 142 }
→ { "diagnostics": [...],        // RQL diagnostics + RQD0xx/3xx component diagnostics
    "completions": [...],        // component names, props, param names, field names
    "params":      [...],        // declared params, for rendering controls
    "outline":     [...] }       // components in document order

POST /api/dashboards/data
{ "source": "…", "connectionId": "…", "params": { "window": "last_30_days" } }
→ { "datasets": { "posts": { "columns": [...], "rows": [...], "schema": {...} } },
    "warnings": [...],           // per-query degradation (RQL design §5.3)
    "partial":  false }

GET  /api/dashboards                 // saved documents
POST /api/dashboards
GET  /api/dashboards/{id}
PUT  /api/dashboards/{id}
DELETE /api/dashboards/{id}

POST /api/dashboards/{id}/export     // → 202 job; produces .xlsx (§9)
```

`/analyze` stays a pure function — no execution, no writes — so it remains safe on a keystroke.

Route `/dashboards` (and `/dashboards/:id`) **must** be added to `WebMvcConfig` as view controllers,
or direct navigation 404s. This has already bitten `/connections` once.

---

## 9. Excel export as a projection

Authors should not write a dashboard twice. `POST /api/dashboards/{id}/export` renders the same
document to a workbook:

| RQD element | Excel projection |
| --- | --- |
| `<DataTable>`, any chart's table twin | a sheet |
| `<Stat>`, `<KpiRow>`, `<Hero>`, `<Meter>` | summary sheet rows |
| Charts | native Excel charts where POI supports the form (bar/line/area/scatter); otherwise the table twin plus a note |
| Markdown prose | summary sheet text |
| Params | a "Parameters" sheet recording the values used |

Forms POI cannot draw natively (heatmap, dumbbell, diverging stacked) degrade to their table twin
rather than to a rasterized image — consistent with §5's "every value is reachable another way,"
and it avoids adding a headless renderer, which the no-Docker constraint forbids anyway.

---

## 10. Package layout (additions)

```
com.mcpserver.reports
├── doc/
│   ├── RqdParser.java        front matter + markdown + components + rql blocks
│   ├── ComponentSpec.java    the §3 catalog as data (name, props, arity, lints)
│   └── DocAnalyzer.java      RQD0xx/3xx diagnostics; delegates queries to lang/Analyzer
├── DashboardController.java
└── DashboardService.java

webui/src/
├── viz/                      one component per §3 entry, hand-rolled SVG
│   ├── scales.ts  Axis.tsx  Legend.tsx  Tooltip.tsx  TableTwin.tsx
│   └── palette.ts            §5.1 slots, read from CSS custom properties
└── components/DashboardPage.tsx
```

`viz/palette.ts` reads `--series-N` from CSS rather than hardcoding hexes, so the tokens stay
single-source in `styles.css`.

---

## 11. Build order

Sequenced so each step is verifiable alone. Assumes RQL design §12 steps 1–4 are done — RQD needs
a working query layer under it.

1. **`--series-1…8` tokens** into `styles.css`. Verifiable: render eight swatches, re-run the
   validator against the built CSS.
2. **`viz/` primitives** — `scales.ts`, `Axis`, `Legend`, `Tooltip`, `TableTwin`. Verifiable in
   isolation with static fixture data, no backend.
3. **Three components end to end** — `<Stat>`, `<BarChart>`, `<DataTable>`. Proves the mark specs
   and the table-twin rule before the catalog is wide.
4. **`doc/RqdParser`** — front matter, fenced blocks, component tags. Reuses the RQL recovery
   model; the parser must not throw.
5. **`/api/dashboards/analyze`** — curl-verifiable before any dashboard UI exists.
6. **`/api/dashboards/data`** + `DashboardPage` — first live dashboard.
7. **Params and `<Filter>`** — interactivity.
8. **Remaining components**, each with its §4 lints.
9. **Excel projection** — last; it is a projection of a thing that must already work.

---

## 12. Open questions

1. **Does a dashboard become an MCP tool?** "Give me the API health numbers" returning rendered
   datasets is attractive — and unlike the Excel case it returns JSON, so it fits the existing
   read-only tool shape without the Phase 3 confirmation flow. Probably the most valuable follow-on.
2. **Sharing and export.** `server.address=127.0.0.1` means dashboards are local-only. A shareable
   static HTML export is plausible but is effectively publishing — needs the Phase 6 auth story
   first, and would need its own sanitization pass.
3. **HTML subset in the document.** The design above allows markdown + known component tags only.
   Permitting raw HTML would match "HTML and SQL mixed" more literally but introduces an XSS
   surface and a sanitizer to maintain. **Recommendation: don't** — markdown plus components covers
   the layout need, and the moment a dashboard is shared (item 2) raw HTML becomes a real
   vulnerability rather than a theoretical one.
4. **Scheduled refresh.** A dashboard that re-runs nightly and alerts on a threshold is a natural
   extension, and `ConnectionPollingScheduler` is a working precedent. Out of scope here.
5. **Small multiples / faceting.** Referenced by §4.4 and §4.9 as the escape hatch for too many
   series, but not specified as a component (`<Facet by="region">`). Should land with the component
   catalog, not after — the guardrails point at it.
