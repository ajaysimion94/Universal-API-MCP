# Query language reference

RQL is the generated source language behind Insights. Most users should build dashboards with collection-aware dashboard inputs and IDE controls. Use this reference when inspecting generated source or writing advanced custom logic.

## Request datasets

A request dataset binds a connected GET request to a variable:

```rql
let rows = request "Orders: List orders";
```

Additional dashboard inputs generate additional datasets:

```rql
let customers = request "Customers: List customers";
```

Visual components bind to those datasets:

```md
<DataTable data={rows} gridX="0" gridY="0" gridW="8" gridH="4" />
<DataTable data={customers} gridX="8" gridY="0" gridW="4" gridH="4" />
```

Grid settings are stored in front matter:

```yaml
grid:
  columns: 12
  rowHeight: 96
  gap: 12
```

`gridX`, `gridY`, `gridW`, and `gridH` are no-code layout metadata. They do not change the dataset or query logic; they only control where a visual appears on the dashboard canvas.

## Pipelines

The IDE can round-trip generated request pipelines when they use supported stages:

```rql
let rows = request "Orders: List orders"
  |> where status = "open"
  |> select id, customer_id, total
  |> order by total desc
  |> limit 100;
```

The same supported stages can be applied to another request dataset:

```rql
let customers = request "Customers: List customers"
  |> select id, name, tier
  |> where active = true
  |> limit 100;
```

Supported IDE stages:

- `where`
- `select`
- `group by ... agg ...`
- `order by`
- `limit`
- `distinct`

## Joins

Relationship blocks generate joins between existing datasets produced by dashboard inputs:

```rql
let rows_customers = rows
  |> join customers on customer_id = id prefix "customers";
```

Join behavior:

- The left dataset keeps all of its rows.
- Matching right-side fields are added to the result.
- Right-side fields are also available with the configured prefix, for example `customers.name`.
- If a field does not exist on the left row, the unprefixed right-side field is also copied.

The visual IDE creates these joins from the **Model relationships** stage. Source mode can still edit them directly.

Joined datasets can also be shaped by the IDE. The join stays as the base of the pipeline and supported visual stages are appended:

```rql
let rows_customers = rows
  |> join customers on customer_id = id prefix "customers"
  |> where customers.tier = "gold"
  |> group by status agg sum(total) as sum_total
  |> order by sum_total desc
  |> limit 20;
```

## Custom logic

If a selected dataset contains source the IDE cannot represent safely, Build mode shows a custom-logic notice for that dataset. You can keep editing in Source mode or convert that pipeline back to supported IDE controls.

## Design rule

RQL and component markup are the source of truth, but they should not be the first authoring surface for dashboards. The IDE should generate RQL, visual bindings, and grid layout metadata from dashboard inputs, transforms, relationships, and visual placement controls.
