# Reports and Insights tutorial

This tutorial demonstrates the intended Insights workflow: combine multiple API requests into a dashboard without typing RQL.

## Scenario

You have two connected read requests:

- `Customers: List customers`
- `Orders: List orders`

You want a dashboard that shows order activity with customer context.

## Step 1: Add the first dashboard input

Open **Insights**. The page first shows saved dashboards on the left and the selected dashboard on the right. Select **Create**, choose `Orders: List orders` from **Add collection request**, and select **Add first input**.

The IDE creates:

- a primary dashboard input named `rows`
- a KPI row showing row count
- a data table bound to `rows`
- request status output

## Step 2: Add another collection request

Choose `Customers: List customers` and select **Add input**.

The IDE appends a second dashboard input with its own dataset name and a table bound to that dataset. You can rename non-primary datasets to meaningful names such as `customers`.

## Step 3: Run the dashboard

Select **Run dashboard**.

Each dashboard input reports whether it fetched successfully and how many rows were loaded. Visuals can now bind to any loaded dataset.

## Step 4: Model relationships across requests

In **Model relationships**, choose:

- From dataset: `rows`
- From key: `customer_id`
- Match dataset: `customers`
- Match key: `id`
- Output dataset: `rows_customers`

Select **Create relationship**.

The IDE creates a derived dataset and adds a table bound to it. The generated source uses a left join, so all rows from `rows` remain visible even when a matching customer is absent.

If the inferred keys are wrong, use **Edit** on the relationship card, change the key fields, and select **Update relationship**. Use the remove action when the joined dataset is no longer part of the dashboard.

## Step 5: Shape request and joined datasets

Use **Shape** on a dashboard input or relationship block, or choose a dataset from **Shape dataset**, then use the Composition IDE controls to:

- choose fields
- add filters
- group and aggregate rows
- sort rows
- cap the result size

These controls generate source behind the scenes for the selected dataset. You can shape `rows`, switch to `customers`, then switch to `rows_customers` and shape the joined result used by the dashboard visuals.

The relationship card keeps showing the join definition and row-count status, so you can distinguish “edit the join keys” from “shape the joined output.”

## Step 6: Add visuals

Use the visual toolbar to add:

- data tables
- charts
- KPI cards
- key/value blocks
- request status blocks

Select a visual and use **Configure** to bind it to a dataset and fields.

Use the **Grid** controls above the canvas to choose the dashboard's column count, row height, and gap. Select a visual to move it with arrow controls or set its `X`, `Y`, `W`, and `H` values directly. Select **Auto-arrange** when you want the IDE to place the visuals into the next available grid slots.

## Advanced: generated source

Open **Source** only when you need to inspect or customize the generated document. Source edits are supported, but custom logic may not be convertible back into IDE controls.
