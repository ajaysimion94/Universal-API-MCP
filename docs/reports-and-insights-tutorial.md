# Tutorial: Build a Report and an Insight

A start-to-finish walkthrough: connect an API, write your first query, then grow it into an insight
with a KPI row, a chart, a table, and a parameter — and save it alongside as many others as you like. Roughly 20 minutes.

The reference for every keyword used here is [`query-language-reference.md`](query-language-reference.md).
For the rest of the application, see [`user-guide.md`](user-guide.md).

---

## Before you start

Start the server and open the UI:

~~~sh
cd mcp-server
mvn package && java -jar target/mcp-server.jar
# then open http://127.0.0.1:8080
~~~

During development use two terminals and open http://localhost:5173 instead:

~~~sh
cd mcp-server && mvn spring-boot:run -Dskip.frontend=true
cd mcp-server/webui && npm run dev
~~~

You do **not** need the vector store, embedding model, or SearXNG for insights — those are for
knowledge search. Insights need exactly one thing: a connected API collection with at least one
enabled read request.

---

## Step 1 — Connect an API collection

1. Open **Connections** → **Add connection**.
2. Choose the **API (Postman/OpenAPI)** type.
3. Give it a name. The name becomes the `@app` slug used elsewhere in the app — "Todo App" becomes
   `@todo-app`.
4. Provide the spec: upload a Postman collection or OpenAPI file (`.json`, `.yaml`, `.yml`), or point
   at a spec URL (for example a `swagger-ui/index.html` address).
5. If the API needs credentials, use **detect auth** and fill in the scheme it reports. Credentials
   are encrypted at rest with AES-256-GCM; queries can never supply their own.
6. Import runs as a background job. When it finishes, the connection shows **CONNECTED**.

Every request in the spec becomes a tool named `{app}_{request-name}` — `confluence_get_space_list`,
`todo_app_create_todo`.

## Step 2 — Enable the read requests you want to query

Open **Apps**. Requests arrive disabled, which is deliberate: enabling is the moment a person decides
this endpoint may be called.

For insights, enable the **GET** requests you plan to query. A disabled request still parses in a
query but returns no rows (`RQL102`), and a write request is rejected outright (`RQL104`).

While you are here, note each request's display name exactly — that string is what `request "…"`
matches.

## Step 3 — Your first query

Open **Insights**. Pick your collection in the **API collection** picker at the top right.

If the collection already has an enabled GET request, the editor pre-fills a starter document for it.
Replace the RQL block with the smallest thing that can work — one request, one limit:

~~~rql
let records = request "List all posts"
  |> limit 25;
~~~

Substitute your own request name. As you type, the editor calls the analyzer and underlines problems:
a wrong name gives `RQL101`, a missing `;` gives `RQL001`. The footer under the editor shows the first
few diagnostics; "Document checks clean" means the analyzer found nothing.

Now add one component so there is something to render, and press **Run insight**:

~~~markdown
<DataTable data={records} />
~~~

The right pane shows the rows the API returned. This is the loop for everything that follows: shape
data in the RQL block, bind it to a component, run.

**If the table is empty or has one strange row**, your API wraps its rows under a key the executor
does not unwrap automatically (it handles `items`, `data`, and `results`). Look at the single row's
columns and unwrap it explicitly:

~~~rql
let records = request "Search issues"
  |> expand issues as issue
  |> select issue.key as key, issue.fields.status.name as status;
~~~

## Step 4 — Shape the data

A table of raw API JSON is not a report. Filter, project, and sort until each row means one thing:

~~~rql
let posts = request "List all posts"
  |> where userId >= 1
  |> select id, userId, title;
~~~

Useful moves while shaping:

- `select id, customer.name as customer` — flatten nested fields and give them readable names.
- `where status in ("open", "blocked")` — membership beats a chain of `or`.
- `order by created desc |> limit 20` — most recent first.
- `offset 20 |> limit 10` — page two. Offset must come first, or the limit runs before the skip.

## Step 5 — Aggregate

Charts need one row per category. That is what `group by` produces:

~~~rql
let by_user = posts
  |> group by userId agg count(*) as posts
  |> order by posts desc;
~~~

`count(*)` counts rows in the group; `sum`, `avg`, `min`, and `max` take a field. `having` filters the
aggregated rows — `having posts > 5` drops the long tail before it reaches the chart.

## Step 6 — Add the KPI row

~~~markdown
<KpiRow>
  <Stat value={count(posts)} label="Posts" />
  <Stat value={count(by_user)} label="Active users" />
</KpiRow>
~~~

`<Stat>` takes `count(dataset)`, an aggregate over a column (`avg(by_user.posts)`), or
`dataset.field` for the first row's value. An expression it cannot evaluate renders `—` rather than
failing the run.

## Step 7 — Add the chart

~~~markdown
<BarChart data={by_user} x="userId" y="posts" title="Posts per user" />
~~~

`data`, `x`, and `y` are all required (`RQI020`). Every chart ships with a collapsible data table
underneath it, so the numbers behind a bar are always one click away.

Two things the document will refuse: `y2` (dual axes, `RQI011`) and `color` (`RQI013`). Series colours
come from the insight palette so that a colour always means the same entity. If you need a second
measure, add a second chart. If the chart has one bar, you will see `RQI310` — a single number is a
`<Stat>`, not a chart.

## Step 8 — Add a parameter

Parameters make the insight answerable rather than fixed. Declare them in front matter and read them
as `$name`:

~~~markdown
---
title: API activity
params:
  minUser: { type: number, default: 1 }
---
~~~

~~~rql
let posts = request "List all posts"
  |> where userId >= $minUser;
~~~

An input appears above the workspace. Change it, press **Run insight**, and the query re-runs with
the new value. `type: number` gives a numeric input; anything else gives text.

You can also pass a parameter to the request itself, when the API supports filtering server-side —
this is almost always better than fetching everything and filtering locally:

~~~rql
let posts = request "List all posts" with { userId: $minUser };
~~~

## Step 9 — The finished document

~~~markdown
---
title: API activity
params:
  minUser: { type: number, default: 1 }
---

# API activity

Posts by user, refreshed on each run.

```rql
let posts = request "List all posts"
  |> where userId >= $minUser
  |> select id, userId, title;

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

**Save it.** Give the insight a name in the header and press **Save**. It joins the library on the
left, which holds as many insights as you care to keep — click one to reopen it, or **New** to start
another. Saving stores the document source (and your default-app choice), so opening it later
re-runs it against live data.

---

## Step 10 — Beyond the basics

The engine covers the whole function set of the `.filter` report language. The moves you are most
likely to want next:

**Pull detail for every row** — one request per row, merged back in:

~~~rql
let detailed = orders
  |> lookup request "Get order detail" by id
  |> select id, detail.price as price, availability;
~~~

**Filter by date** — declare the field's shape once, then use windows:

~~~rql
let recent = orders
  |> parse date createdAt format "yyyy-MM-dd'T'HH:mm:ss'Z'" timezone "UTC"
  |> where createdAt date_preset THIS_MONTH;
~~~

**Compare two sources** — which ids exist where:

~~~rql
let coverage = compare [orders as "live", request "List archived" as "archived"] on id
  |> where _count = 1;
~~~

`intersect`, `except`, and `diff` do the same on whole rows and add `_source` / `_in_<label>` columns.

**Branch a filter** — different thresholds per case:

~~~rql
let flagged = orders |> where if priority = "high" then (age_days > 2) else (age_days > 14);
~~~

**Report on the run itself** — request status and aggregate metrics:

~~~markdown
<Status />
<Metrics />
~~~

**Write summary blocks** instead of only charts:

~~~markdown
<KeyValue label="Coverage" value={if count(detailed) = count(orders) then "complete" else "partial"} />
<QuickTable title="Scorecard" headers={["Metric", "Value", "Level"]}
  rows={[["Orders", count(orders), if count(orders) > 100 then "High" else "Low"]]} />
~~~

The [reference](query-language-reference.md) has the full grammar, and §8 there maps every `.filter`
keyword to its RQL spelling.

## Reports without the UI

The same query engine is reachable over HTTP, which is how you would drive it from a script, a
scheduled job of your own, or an MCP client.

Analyze a document (no network calls beyond reading the tool list):

~~~sh
curl -s -X POST http://127.0.0.1:8080/api/reports/analyze \
  -H 'Content-Type: application/json' \
  -d '{"source":"let a = request \"List all posts\" |> limit 5;","connectionId":"YOUR_ID"}'
~~~

Execute it and get the rows:

~~~sh
curl -s -X POST http://127.0.0.1:8080/api/reports/execute \
  -H 'Content-Type: application/json' \
  -d '{"source":"let a = request \"List all posts\" |> limit 5;","connectionId":"YOUR_ID","parameters":{}}'
~~~

`connectionId` is required for execution; get it from `GET /api/connections`. The response carries
`datasets` plus the same `diagnostics` the editor shows, so a script can check for `ERROR`-severity
entries before trusting the numbers.

---

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| "Connect an API collection first" | No collection selected | Pick one in the header; only `CONNECTED` collections are listed. |
| `RQL101 Unknown request` | Name does not match | Copy the display name from Apps, or type `request "` and use the completion list. |
| `RQL102` and an empty dataset | Request is disabled | Enable it on the Apps page. |
| `RQL104` | You referenced a write request | Insights run read requests only. |
| `RQL201 returned HTTP 4xx/5xx` | The API rejected the call | Check credentials and required arguments; test the request on the Apps page first. |
| `RQL103 Unknown dataset` | Component or pipeline names a dataset no `let` defines | Check spelling; `let` must come before use. |
| `RQI101` | Component references an unknown dataset | Same as above, from the component side. |
| Chart is blank, table has rows | `y` column is not numeric | `select` a numeric field, or aggregate to produce one. |
| Everything is empty but there are no errors | The request returned an empty array | Verify the request in Apps, and check any `with { … }` arguments. |
| A run is instant and stale | Responses are cached per tool + arguments | Change an argument or restart the server to force a fresh call. |

---

## Where to go next

- [`query-language-reference.md`](query-language-reference.md) — every statement, stage, operator, and
  diagnostic code.
- [`user-guide.md`](user-guide.md) — files, knowledge search, tool invocation, approvals, and audit.
- [`insight-design.md`](insight-design.md) / [`report-query-design.md`](report-query-design.md) —
  the design intent behind the guardrails, and what is planned but not yet built.
