# Query System Reference (RQL and RQD)

This is the reference for the query system that powers reports and insights: **RQL**, the report
query language, and **RQD**, the insight document that embeds it. It documents what the shipped
implementation actually does — `reports/RqlParser.java`, `reports/ReportQueryService.java`, and
`insights/InsightDocumentParser.java` are the authority, and where behaviour is narrower than
`report-query-design.md` describes, this file says so.

For a step-by-step first build, read [`reports-and-insights-tutorial.md`](reports-and-insights-tutorial.md).
For everything else in the application, read [`user-guide.md`](user-guide.md).

---

## 1. What the query system queries

RQL does **not** query the SQLite chunk store, and it is not the RAG search path. It queries
**imported API requests** — the tools created when you import a Postman collection or OpenAPI
document on the Connections page. Each request returns JSON; RQL turns that JSON into rows and
transforms them.

Three rules hold for every query, and they are enforced in the executor, not by convention:

| Rule | Effect |
| --- | --- |
| Read requests only | A write tool referenced in a pipeline is an error (`RQL104`) and returns no rows. |
| Enabled tools only | A disabled tool is a warning (`RQL102`) and yields an empty dataset. |
| No second credential path | HTTP is always delegated to `ApiToolExecutor` with the connection's stored credentials. A query can never supply its own. |

Failures degrade instead of aborting: an unavailable request becomes an empty dataset plus a
diagnostic, and every other `let` in the document still runs.

---

## 2. Program shape

An RQL program is a sequence of statements, each terminated with `;`. `--` starts a line comment.

~~~rql
-- Parameters first, then datasets.
set minUser = 1;

let posts = request "List all posts"
  |> where userId >= $minUser;

let by_user = posts
  |> group by userId agg count(*) as posts
  |> order by posts desc;
~~~

### 2.1 Statements

| Statement | Purpose |
| --- | --- |
| `use collection "name";` | Scopes the statements that follow to one connected app, matched by id, name, or slug. Unqualified request names resolve there first (§3.4). |
| `set name = value;` | Defines a variable, referenced later as `$name`. The value is a literal: quoted string, number, `true`/`false`, `null`, or another `$variable`. |
| `let name = pipeline;` | Runs a pipeline and binds the result as a named dataset. Later statements and insight components reference it by this name. |
| `emit pipeline as "Label";` | Runs a pipeline and publishes it as an output dataset. `as "Label"` is optional; without it the dataset is named `result`. If a dataset with that name already exists, the existing one wins. |

Every statement needs its `;`. A missing one is `RQL001`; an unrecognized statement is `RQL002`. The
parser never throws — it records a malformed statement and keeps analyzing the rest of the document,
so one typo does not blank out the editor's diagnostics for everything below it.

### 2.2 Pipelines

A pipeline is a source followed by zero or more stages, joined with `|>`:

~~~rql
let recent = request "List orders" with { status: "open", limit: 200 }
  |> where total > 100
  |> select id, customer.name as customer, total
  |> order by total desc
  |> limit 20;
~~~

---

## 3. Sources

### 3.1 A request

~~~rql
request "List all posts"
request "Search issues" with { jql: "project = ENG", maxResults: 100 }
request "List orders" with { status: $status }
~~~

The name matches the tool's display name, tool name, or request slug, case-insensitively. A name may
be qualified with its app — `request "CRM: List customers"` — which is how one document reads from
several collections at once (§3.4). Arguments inside `with { … }` accept `key: value` or
`key = value`; values are literals or `$variables`, and they fill the request's path, query, and
header parameters as the imported spec declares them.

Responses are cached per tool id + argument set, so two datasets built from the same request with the
same arguments cost one HTTP call.

### 3.2 A prior dataset

Any name bound by an earlier `let` can start a pipeline. Referencing an undefined name is `RQL103`.

~~~rql
let open_only = posts |> where state = "open";
~~~

### 3.3 Combinators

Each element is itself a full pipeline, optionally labelled with `as "Name"`. The label names the
source in provenance columns; without one it defaults to the request name or dataset name.

| Form | Behaviour |
| --- | --- |
| `union [a, b, …]` | Concatenates, then removes duplicate rows. |
| `union all [a, b, …]` | Concatenates, keeping duplicates. |
| `intersect [a, b, …]` | Rows of the first input that appear in **every** other input. |
| `except [a, b, …]` | Rows of the first input that appear in **none** of the others. |
| `diff [a, b, …]` | Symmetric difference: rows unique to exactly one input, grouped by source. |
| `compare [a, b, …] on <field>` | One row per distinct value of `field`, with presence per source. |

`intersect`, `except`, and `diff` compare whole rows, not a key column. All three add provenance:

| Column | Content |
| --- | --- |
| `_source` | `ALL` for `intersect`; otherwise the source the row came from. |
| `_in_<label>` | One boolean column per input. |

`compare` produces a value matrix instead — the `field` itself, one `_in_<label>` per source, and
`_count` (how many sources hold that value). Filter it with an ordinary `where`:

~~~rql
let everything = union [
  request "List open incidents",
  request "List closed incidents"
];

let mismatches = diff [
  request "List items" as "live",
  request "List archived items" as "archived"
];

let id_coverage = compare [orders as "live", archived as "archived"] on id
  |> where _count > 1;
~~~

### 3.4 Multiple apps in one document

An insight is not tied to one collection. A request name resolves in this order:

1. **Qualified** — `request "CRM: List customers"` pins the app by id, name, or slug.
2. **Scoped** — the most recent `use collection "CRM";` above it.
3. **Preferred** — the app chosen in the page's *Default app* picker (or front-matter `connection:`).
4. **Any connected app** — every other API collection, in order.

If a bare name exists in two apps and nothing narrows it, the query stops with `RQL106` naming the
candidates rather than guessing which app you meant. Unknown app names raise `RQL107`.

~~~rql
let orders = request "Orders: List orders";
let customers = request "CRM: List customers" with { tier: "gold" };

-- enrich one app's rows with another's
let enriched = orders
  |> select id, total, customer.name as customer
  |> join customers on customer = code prefix "crm";

-- or reach into the other app per row
let owned = orders |> lookup request "CRM: Get customer" by customer as code;

use collection "CRM";
let all_customers = request "List customers";   -- unqualified: resolves in CRM
~~~

### 3.5 How JSON becomes rows

| Response body | Rows produced |
| --- | --- |
| JSON array | One row per element. |
| Object with an `items`, `data`, or `results` array | One row per element of that array (first match wins, in that order). |
| Any other object | A single row. |
| A scalar | A single row with one column, `value`. |

Nested objects are kept as nested values and are reachable with dotted paths (`author.name`). Arrays
inside a row stay arrays until you `expand` them. If your API wraps rows under a different key —
`content`, `issues`, `records` — the whole body becomes one row; use `expand` to unwrap it:

~~~rql
let issues = request "Search issues"
  |> expand issues as issue
  |> select issue.key as key, issue.fields.status.name as status;
~~~

---

## 4. Pipeline stages

### 4.1 `where`

~~~rql
|> where status = "open" and total between 100 and 500
|> where owner is not null and (priority = "high" or escalated is true)
|> where title contains "invoice" and not (state in ("closed", "void"))
~~~

Supported operators:

| Category | Operators |
| --- | --- |
| Logical | `and`, `or`, `not`, parentheses |
| Comparison | `=`, `==`, `!=`, `<>`, `>`, `>=`, `<`, `<=` |
| Range | `between <low> and <high>` (inclusive; date-aware — see §4.9) |
| Membership | `in (a, b, c)`, `not in (…)` |
| Null / boolean | `is null`, `is not null`, `is true`, `is false` |
| Text | `like`, `ilike` (SQL `%` and `_` wildcards), `contains`, `not_contains`, `starts with`, `ends with`, `regex` |
| Date | `<field> date_preset <preset>` — see §4.9 |
| Conditional | `if <condition> then <expr> [else <expr>]` |
| Bare field | A field holding `true` passes the filter on its own. |

**Text matching is case-insensitive**, and so is the string fallback for `=`, `in`, and the ordering
comparisons — `status = "open"` matches `Open`. `regex` uses find() semantics, so a pattern matches
anywhere in the value unless you anchor it with `^`/`$`.

Operand resolution, in order: `$name` is a variable; a quoted string (double or single), number,
`true`, `false`, or `null` is a literal; anything else is a field path. Comparisons are numeric when
both sides parse as numbers — so `"10" > "9"` behaves numerically.

**Conditionals** apply a different requirement per branch:

~~~rql
|> where if priority = "high" then (severity > 7) else (severity > 3)
|> where if status = "active" then (score > 50)
|> where if type = "A" then (val > 10) else (val > 5) and category = "premium"
~~~

With no `else`, a false condition leaves the row in place — the conditional only ever adds a
requirement. Branches parenthesised as above keep a trailing `and`/`or` outside the conditional, and
a nested `if` claims the nearest `else`.

**Date windows** need no boilerplate beyond a field declaration (§4.9):

~~~rql
|> parse date createdAt format "yyyy-MM-dd'T'HH:mm:ss'Z'" timezone "UTC"
|> where createdAt date_preset THIS_MONTH
|> where createdAt between "2026-01-01" and "2026-01-31"
~~~

Presets: `TODAY`, `YESTERDAY`, `THIS_WEEK`, `LAST_WEEK`, `THIS_MONTH`, `LAST_MONTH`, `THIS_QUARTER`,
`LAST_QUARTER`, `THIS_YEAR`, `LAST_YEAR`. Windows are inclusive at day granularity and weeks start on
Monday. A date-only upper bound in `between` covers that whole day. A value that cannot be parsed
never matches a date rule.

Field paths are dotted (`fields.status.name`). For a key containing spaces or punctuation, bracket
and quote it: `["Due Date"]`.

### 4.2 `select`

~~~rql
|> select *
|> select id, customer.name as customer, total as "Order total"
~~~

An alias may be bare (`as customer`) or quoted (`as "Order total"`). Without an alias, the output
column is the last segment of the path — `customer.name` becomes `name`. `*` copies every column of
the input row and can be combined with explicit projections.

### 4.3 `order by`

~~~rql
|> order by total desc, created asc
|> order by status, total desc
~~~

Ascending is the default. Sorting uses the same numeric-then-string rule as comparisons; `null`
sorts first.

### 4.4 `limit` and `offset`

~~~rql
|> offset 20 |> limit 10     -- rows 21–30
~~~

`limit n` keeps the first *n* rows of its input; `offset n` drops the first *n*. Order matters:
`limit 10 |> offset 20` yields nothing, because the offset applies to the ten rows that survived the
limit. Put `offset` first when you page.

### 4.5 `distinct`

~~~rql
|> distinct                  -- whole-row deduplication
|> distinct by customer      -- first row per customer
|> distinct customer, region -- 'by' is optional
~~~

Deduplication keeps the first occurrence and preserves order.

### 4.6 `group by`

~~~rql
|> group by status agg count(*) as issues, avg(age_days) as avg_age having issues > 5
|> group by team, quarter agg sum(revenue) as revenue
~~~

Shape: `group by <fields> [agg <aggregates>] [having <expression>]`.

| Aggregate | Behaviour | Default alias |
| --- | --- | --- |
| `count(*)` | Row count in the group. | `count` |
| `count(field)` | Non-null values of `field`. | `count_field` |
| `sum(field)` | Numeric sum; non-numeric values ignored. | `sum_field` |
| `avg(field)` | Numeric mean (`DECIMAL64`). | `avg_field` |
| `min(field)` / `max(field)` | Numeric minimum / maximum. | `min_field` / `max_field` |

Group keys keep their short field name in the output row. `having` runs against the aggregated row,
so it can filter on an aggregate alias. An aggregate over no numeric values yields `null`.

### 4.7 `expand`

~~~rql
|> expand items
|> expand items as extras
|> expand labels
~~~

Turns one row with an array into one row per element, copying the other columns.

**Object elements are flattened with the field name as prefix** — `items` holding
`{"sku": "A1", "qty": 2}` produces columns `items.sku` and `items.qty`. A child present in only *some*
elements is sparse, and moves under the exception label (`exceptions.gift`), so a sparse field never
silently changes what a column means. `as` renames that exception label (`extras.gift`).

**Scalar elements** keep the simple shape: one column named after the field, or after `as`.

A row whose value is not an array passes through unchanged; a row holding an **empty** array
disappears, since zero elements produce zero rows.

### 4.8 `rename`

~~~rql
|> rename total as "Order total", created as "Created"
~~~

The new name must be quoted. Renaming is by output column name, so apply it after `select`.

### 4.9 `parse date` / `date config`

~~~rql
|> parse date createdAt format "yyyy-MM-dd'T'HH:mm:ss'Z'" timezone "UTC"
|> date config dueDate format "dd/MM/yyyy" timezone "Asia/Kolkata"
~~~

Declares how one field's dates are read. Both spellings do the same thing. The declaration applies to
the **whole run**, not just the pipeline that made it — a date format belongs to the field, not to the
query that first mentioned it — so one declaration serves every later `where`.

With a declaration in place, `date_preset` and `between` compare instants rather than text. Without
one, common ISO-8601 shapes are still detected automatically. Values are not rewritten; a malformed
directive raises `RQL203`.

### 4.10 `lookup` — per-row detail requests

~~~rql
|> lookup request "Get order detail" by id
|> lookup request "Get item detail" by data.id as itemid prefix "detail"
~~~

For each row, reads `by` from that row, runs the detail request with it, and merges the response.
Merged fields land as `prefix.field` (`detail.` unless `prefix` renames it) **and** unprefixed when
the name does not clash with a source field. `as` sets the argument name the detail request expects,
when it differs from the source field name.

Responses are cached per tool + arguments, so repeated key values cost one call.

### 4.11 `join` — dataset to dataset

~~~rql
|> join archived on id = id
|> join customers on customerId = id prefix "customer"
~~~

Left join: every left row survives, matched right-hand fields are merged with a prefix (the dataset
name unless `prefix` renames it) and unprefixed when there is no clash. The right-hand dataset must be
defined by an earlier `let`. Key matching is case-insensitive; the first match wins.

---

## 5. Diagnostics

Every diagnostic carries a source span (offset, line, column), a severity, a code, and a message. The
editor underlines them live; execution returns the same list alongside whatever data it produced.

### 5.1 RQL codes

| Code | Severity | Meaning |
| --- | --- | --- |
| `RQL001` | Error | Missing `;` at the end of a statement. |
| `RQL002` | Error | Not a `use` / `set` / `let` / `emit` statement. |
| `RQL003` | Error | A pipeline has no source before `|>`. |
| `RQL004` | Error | Nothing follows a `|>`. |
| `RQL005` | Error | The source is not a request, a known dataset, or a combinator. |
| `RQL006` | Error | `compare` is missing its `on <field>`. |
| `RQL014` | Error | Unknown pipeline stage. |
| `RQL099` | Error | The document could not be read at all (parser safety net). |
| `RQL101` | Error | Unknown request name in this collection. |
| `RQL102` | Warning | The request is disabled; the dataset will be empty. |
| `RQL103` | Error | Unknown dataset — define it with `let` first. |
| `RQL104` | Error | The request changes data and cannot be queried. |
| `RQL105` | Hint | The connected collections could not be read, so request names cannot be validated. |
| `RQL106` | Error | A bare request name exists in several apps — qualify it as `"App: Request"`. |
| `RQL107` | Error/Warning | Unknown app in a qualified name or in `use collection`. |
| `RQL201` | Warning | The request failed or returned HTTP ≥ 400; the dataset is empty. |
| `RQL202` | Warning | A stage could not be applied to this data. |
| `RQL203` | Warning | Malformed `parse date` / `date config` directive. |
| `RQL204` | Error | Malformed `lookup` stage. |
| `RQL205` | Error | Malformed `join` stage. |

### 5.2 RQD codes

| Code | Severity | Meaning |
| --- | --- | --- |
| `RQI001` | Error | Front matter opened with `---` but never closed. |
| `RQI002` | Error | Front matter is not valid YAML. |
| `RQI010` | Error | Unknown component. |
| `RQI011` | Error | `y2` — dual y-axes are not supported; use two charts. |
| `RQI012` | Error | `<Filter>` nested inside a chart. One filter row scopes the whole document. |
| `RQI013` | Error | `color` — series colours come from the dashboard palette. |
| `RQI014` | Warning | A prop the component does not read, so it has no effect (typo, or a design-doc prop such as `delta`/`format` that was never implemented). |
| `RQI020` | Error | `<BarChart>` needs `data`, `x`, and `y`. |
| `RQI021` | Error | `<DataTable>` needs `data`. |
| `RQI022` | Error | `<Stat>` needs `value`. |
| `RQI023` | Error | `<KeyValue>` / `<LabelValue>` needs `label` and `value`. |
| `RQI024` | Error | `<Text>` needs a `value` expression. |
| `RQI025` | Error | `<QuickTable>` / `<LabelTable>` needs `rows`. |
| `RQI101` | Error | A component references a dataset that no `let` defines. |
| `RQI310` | Info | A chart with one category — use `<Stat>` instead. |
| `RQI311` | Info | `<Filter>` renders nothing; parameter controls come from front-matter `params`. |

`RQI500` is produced by the web UI itself when the analyze request cannot reach the server.

---

## 6. RQD: the insight document

An `.rqd` document is Markdown with three additions: YAML front matter, fenced ` ```rql ` blocks, and
a small set of components.

~~~markdown
---
title: API activity
connection: todo-app
params:
  minUser: { type: number, default: 1 }
  status: { type: string, default: "open" }
---

# API activity

Prose renders as Markdown, in document order with the components. `{{ expression }}` interpolates a
value into a sentence using the same expression language the components use.

```rql
let posts = request "List all posts"
  |> where userId >= $minUser;

let by_user = posts
  |> group by userId agg count(*) as posts
  |> order by posts desc;
```

<KpiRow>
  <Stat value={count(posts)} label="Posts" />
  <Stat value={count(by_user)} label="Active users" />
</KpiRow>

<BarChart data={by_user} x="userId" y="posts" title="Posts per user" />

<DataTable data={posts} columns={["id", "userId", "title"]} />
~~~

### 6.1 Front matter

| Key | Meaning |
| --- | --- |
| `title` | Document title. Defaults to `Untitled dashboard`. |
| `connection` | A connection id, name, or slug. The picker on the Insights page overrides it; if neither is set, running the document is a 400. |
| `params` | Map of parameter name → `{ type, default }`, or name → default value as shorthand. `type: number` renders a numeric input; anything else renders text. |

Parameters are readable in RQL as `$name`. At run time, defaults are applied first and then
overwritten by values from the parameter controls or the request body.

### 6.2 RQL blocks

Every ` ```rql ` block in the document is concatenated, in order, into one program — so datasets
defined in the first block are visible in the last. Diagnostics are mapped back to positions in the
`.rqd` file, not the extracted program.

### 6.3 Components

Props are written `name={expression}` or `name="text"`.

| Component | Required props | Notes |
| --- | --- | --- |
| `<Stat value={…} label="…" />` | `value` | One number. Consecutive stats collapse into a KPI row. |
| `<BarChart data={ds} x="field" y="field" title="…" />` | `data`, `x`, `y` | SVG bars with a collapsible "Show chart data table" twin. Non-numeric `y` values are dropped. The axis charts the first 24 categories and says so when there are more; the twin carries up to 100 rows. |
| `<LineChart data={ds} x="field" y="field" title="…" />` | `data`, `x`, `y` | Same props and same twin, drawn as a line with a marker per point. Charts the first 60 points; only every nth axis label is drawn once they would collide. |
| `<PieChart data={ds} x="field" y="field" title="…" />` | `data`, `x`, `y` | Donut plus a legend with each slice's value and share. `x` is the slice label. Rows with a non-positive `y` are dropped — they have no arc. Slices beyond the palette's eight are summed into one `Other (n)` wedge; the twin still lists every row. |
| `<DataTable data={ds} title="…" columns={["id AS \"Order\"", "total"]} />` | `data` | First 100 rows. `columns` is optional; `AS` renames the header without changing the field read. |
| `<Text value={…} />` | `value` | A line of prose or a computed sentence. |
| `<KeyValue label="…" value={…} />` | `label`, `value` | Label/value row with a bold label. |
| `<LabelValue label="…" value={…} />` | `label`, `value` | Same, with a plain label. |
| `<QuickTable title="…" headers={["Metric","Value"]} rows={[["Total", count(orders)]]} />` | `rows` | Inline table written in the document. Headers default to `Label`, `Value`. |
| `<LabelTable title="…" rows={[["Open", count(open)]]} />` | `rows` | Same, with **no** header row unless `headers` is given. |
| `<Status />` | — | One row per request this run issued: name, method, status code, success, duration, and a bar scaled to the slowest request so the expensive call is visible rather than deduced. A cache hit is labelled `cached` and not plotted — it issued no HTTP at all. |
| `<Metrics />` | — | Aggregate execution stats: requests, succeeded, failed, rows returned, total duration. |
| `<KpiRow>` | — | Accepted; grouping is automatic, so wrapping is cosmetic. |
| `<Filter>` | — | Accepted but **not rendered**, and reported as inert (`RQI311`). Parameter inputs come from front-matter `params`. Nesting one inside a chart is an error (`RQI012`). |

Components render in document order, so the page reads the way the source reads. Prose between them
keeps its place in that order.

A prop a component does not read is reported (`RQI014`) rather than ignored, so a typo like `titel`
is visible instead of silently doing nothing.

#### Value expressions

`value` props, `rows` cells, and `{{ … }}` interpolations in prose accept the same small expression
language, evaluated in the browser:

| Expression | Result |
| --- | --- |
| `count(dataset)` or `dataset` | Row count. |
| `sum(dataset.field)`, `avg(…)`, `min(…)`, `max(…)` | Aggregate over that column; `avg` shows one decimal. |
| `dataset.field` | That field from the **first** row. |
| `"text"`, `42`, `true` | Literals. |
| `"Total: " + count(orders)` | Concatenation. |
| `if <condition> then <expr> [else <expr>]` | Conditional, nestable, with `and`/`or`/parentheses. |
| Anything unresolvable | `—` |

Conditions compare numerically first, then case-insensitively — the same rule the query engine uses.

~~~markdown
<Text value={if count(open) > 0 then count(open) + " open orders" else "Nothing open"} />
<KeyValue label="Coverage" value={if count(orders) > 0 and count(detail) > 0 then "complete" else "partial"} />
<QuickTable title="Scorecard" headers={["Metric", "Value", "Level"]}
  rows={[["Orders", count(orders), if count(orders) > 100 then "High" else "Low"]]} />
~~~

#### Colour

Colour is **semantic, never authored**: `y2` and `color` props are rejected (`RQI011`, `RQI013`).
Boolean cells and request status colour themselves from their value — green for true/success, red for
false/failure — so a colour always means the same thing across every dashboard. Series colours come
from the palette. A `COLOR` clause from a `.filter` document has no equivalent here by design.

---

## 7. HTTP API

All four endpoints are POST with a JSON body. `analyze` is network-free apart from reading the tool
list, and is safe to call on every keystroke (the UI debounces at ~260 ms).

### 7.1 `POST /api/reports/analyze`

~~~json
{ "source": "let a = request \"List all posts\";", "connectionId": "…", "cursorOffset": 24 }
~~~

Returns `{ diagnostics, completions, symbols }`. Completions are context-sensitive: after `|>` they
are stage keywords, inside `request "` they are enabled read requests for that connection, otherwise
they are datasets already bound in the document.

### 7.2 `POST /api/reports/execute`

~~~json
{ "source": "…", "connectionId": "…", "parameters": { "minUser": 2 } }
~~~

Returns `{ datasets, diagnostics, requests }`, where `datasets` maps each `let` / `emit` name to its
rows and `requests` lists every request the run issued (`request`, `method`, `status`, `success`,
`durationMs`). `connectionId` is required — omitting it is a 400. This endpoint exists for API
clients; the web UI uses the dashboard endpoints.

### 7.3 Saved insights

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/insights` | List saved insights, most recently updated first. |
| `GET` | `/api/insights/{id}` | One saved insight. |
| `POST` | `/api/insights` | Create — body `{ name, description?, source, connectionId? }` → 201. |
| `PUT` | `/api/insights/{id}` | Update the same fields. |
| `DELETE` | `/api/insights/{id}` | Remove it → 204. |

`connectionId` records only the preferred app for unqualified request names; the document may still
read from any connected collection.

### 7.4 `POST /api/insights/analyze`

Body `{ source, connectionId, cursorOffset }` over the full `.rqd` source. Returns
`{ diagnostics, completions, params, outline }` — `outline` is the parsed component list.

### 7.5 `POST /api/insights/data`

Body `{ source, connectionId, parameters }`. Returns
`{ datasets, diagnostics, params, outline, requests }`, where each dataset carries `columns`, `rows`,
and an inferred `schema` (`number`, `boolean`, `string`, `object`, `array`, or `unknown` for an
all-null column). `requests` is what `<Status>` and `<Metrics>` render.

Errors follow the project convention: `IllegalArgumentException` → 400 with `{"error": "…"}`,
`IllegalStateException` → 409.

---

## 8. Mapping from the `.filter` language

The query engine covers every function of the report automation `.filter` language it was ported
from. Where the spelling differs:

| `.filter` | RQL / RQD |
| --- | --- |
| `COLLECTION` / `REQUESTS` | `use collection "App";` or an app-qualified `request "App: Name"` (§3.4) — one document may span several apps |
| `FILTER x WHERE …` | `let x = request "…" \|> where …` |
| `COLUMNS a, b AS "Header"` | `\|> select a, b as "Header"` (or `columns` on `<DataTable>`) |
| `SHAPE … DISTINCT/ORDER BY/LIMIT/OFFSET/GROUP BY/AGG/HAVING` | The matching pipeline stages |
| `DATE_CONFIG f FORMAT p TIMEZONE z` | `\|> parse date f format "p" timezone "z"` |
| `DATE_PRESET THIS_MONTH` | `where f date_preset THIS_MONTH` |
| `EXPAND r ON items AS extras` | `\|> expand items as extras` |
| `LOOKUP_TABLE … FROM a LOOKUP b BY id AS itemid` | `let t = a \|> lookup request "b" by id as itemid` |
| `UNION / INTERSECT / EXCEPT / DIFF / COMPARE ON f` | The matching combinators (§3.3) |
| `TITLE` / `DESCRIPTION` | Markdown headings and prose |
| `KV` / `LV` / `TEXT` | `<KeyValue>` / `<LabelValue>` / `<Text>` |
| `QT` / `QUICK_TABLE` / `LABEL_TABLE` | `<QuickTable>` / `<LabelTable>` |
| `METRICS` / `STATUS` | `<Metrics>` / `<Status>` |
| `TABLE $var TITLE … COLUMNS …` | `<DataTable data={var} title="…" columns={…} />` |
| `$var = FILTER …`, `$b = FILTER $a …` | `let var = …`, `let b = var \|> …` |
| `COLOR <name/hex>` | No equivalent — colour is semantic (§6.3) |
| `OUTPUT_PREFIX` | No equivalent until workbook export ships |

## 9. Current limits

Worth knowing before you plan around a capability:

- **No workbook export yet.** The `.xlsx` projection (Summary / Index / Results sheets) is the next
  step, not shipped.
- **No scheduled runs.** An insight runs when you run it; there is no timer or alerting.
- **A saved insight keeps its last result.** Reopening one shows the previous run, labelled
  `Saved result · ran <time>`, rather than an empty panel — the snapshot lives on the insight, so it
  follows the document to any browser. Opening never re-runs (that would fire upstream API calls on
  page load), so the numbers can be arbitrarily old; press **Run insight** to refresh. A result is
  not kept when it came from unsaved edits, or when it exceeds 512 KB — in both cases the run still
  displays in full and the preview says why it was not saved.
- **Three chart types.** `<BarChart>`, `<LineChart>`, and `<PieChart>`; the area and scatter forms in
  the design doc are not implemented.
- **Cross-filtering is not implemented.** Parameters are the only interactive control.
- **Design mode edits the document, not a layout.** The workspace's `Design` mode places visuals,
  binds fields, and formats them by rewriting the `.rqd` source, so `Code` always shows exactly what
  was built and the two never diverge. Components stay a vertical block flow — there is no free-form
  drag-and-resize canvas.
- **Field wells populate from the last run.** Column names come from executed datasets, so a document
  that has never run offers no fields to bind and its data components are disabled in the picker.
- **Run progress is indeterminate.** A run's request count is not knowable when it starts — a
  `lookup` stage issues one request per row — so the workspace reports elapsed time and the previous
  run's cost rather than a percentage. Per-request timings arrive with the result, in `<Status />`.
- **`/reports` redirects to `/insights`** in the SPA; there is no separate report workspace yet.
- **The library lists the 200 most recently updated insights.** Older ones stay saved and open by
  id, but do not appear in the panel.
- **A saved insight's default app must exist.** Saving with an unknown `connectionId` is rejected
  (400) rather than stored as a dangling reference.
