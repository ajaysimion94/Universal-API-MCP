# User guide

## Insights

Use Insights when you want to turn connected API data into a reusable dashboard.

The Insights landing page is read-first: saved insights are listed on the left, and the selected dashboard is shown on the right. Use **Create** to start a new dashboard or **Edit** to open the selected dashboard in the authoring IDE.

Insights is built for API product and operations users who need to answer questions such as:

- Which customers have open orders?
- Which requests are slow or failing?
- How do records from one collection relate to another?
- What operational view should be saved and rerun later?

## Build a dashboard

1. Open **Insights**.
2. Select **Create**.
3. Choose a connected read request from **Add collection request**.
4. Select **Add first input**. The IDE creates a primary dataset named `rows`.
5. Add more requests with **Add input**. Each request becomes a named dashboard input and dataset.
6. Run the dashboard to fetch data.
7. Use **Shape** on a request or relationship block, or the **Shape dataset** selector, to choose fields, filters, grouping, sorting, and limits for that dataset.
8. Use **Model relationships** to create joined datasets when two dashboard inputs share a key.
9. Add visuals from the visual toolbar.
10. Use **Grid** controls to set dashboard columns, row height, gap, and visual placement.
11. Select a visual and bind it to the dataset it should display.
12. Save the dashboard.

## Dashboard inputs

A dashboard input represents a connected GET request from any collection. Inputs show:

- dataset name
- source collection
- source request
- run status
- loaded row count when data is available

The first dataset is named `rows` by default. Additional datasets can be renamed from the input card. Any input can be selected for shaping; the IDE rewrites that dataset's generated RQL pipeline behind the scenes.

## Relationship blocks

Use relationship blocks when two input datasets describe different sides of the same object.

Example:

- `rows.customer_id`
- `customers.id`

The **Model relationships** stage creates a derived dataset using a generated join. Joined datasets can be used like any other dataset: shape them, add a table for them, bind charts to them, or inspect them in Source mode.

The current relationship block is a left join: every row from the left dataset stays in the result, and matching fields from the right dataset are added with a prefix.

Relationship cards show row-count status after a run, warn when the selected key fields are not available, and can be shaped, edited, or removed. Editing loads the relationship back into the modeling form and keeps any supported shape stages already applied to the joined dataset. Removing it deletes the generated join and direct tables bound to that derived dataset. If you remove a dashboard input, relationships that depend on that input are removed as well.

## Custom dashboard grid

The result canvas uses a persisted grid. The toolbar above the canvas controls the dashboard grid:

- columns
- row height
- gap
- auto-arrange

Select a visual and open **Configure** to move it, set `X/Y`, and resize `W/H`. The IDE writes those placement values into the generated document, so the saved dashboard keeps the same layout.

## Source mode

Source mode shows the generated document and RQL. Use it for advanced edits only. If the source contains logic the IDE cannot safely represent, the Build mode will show a custom-logic notice instead of pretending it can round-trip the document.
