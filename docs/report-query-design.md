# Report Query Language (RQL) — Design

**Status:** proposed, not implemented. Design-only document.
**Origin:** absorbs the `.filter` DSL from `Postman-excel-report-automation` (a separate Java 17 CLI).
**Companion:** [`dashboard-design.md`](./dashboard-design.md) — RQD, the interactive dashboard /
data-visualization layer built on this language. This document is the substrate; that one is the
primary user-facing target.
**Phase:** ahead of the current tracker — see [§10 Phase placement](#10-phase-placement-and-constraints).

---

## 1. Why

`Postman-excel-report-automation` runs a Postman collection and renders a styled Excel
report, driven by a SQL-like `.filter` DSL. The language idea is sound and worth keeping. The
*implementation* has three structural problems that make it unfit for a UI, and one design
problem that makes it unfit for growth.

### 1.1 Implementation problems

| Problem | Evidence | Consequence |
| --- | --- | --- |
| Fail-fast, single-error parse | `FilterQueryParser.java:1188` — `error()` throws `IllegalArgumentException` on the first bad token, unwinding the whole parse | One typo blanks all feedback. Fatal for live editing. |
| Diagnostics are strings | `String.format("%s:%d:%d %s", …)` — no end position, severity, code, or suggestions | Editor cannot underline a range, group by severity, or offer fixes. |
| Monolithic parser | `parseStatement` is a 1,302-line `if (ts.matchKeyword(...))` chain | Every new keyword edits the middle of one function. |
| No schema awareness | Parser never sees response JSON | Autocomplete on field names is impossible. |

Validation is a second, separate throw-on-first pass (`FilterValidator`, 698 lines), so request-name
typos surface only at runtime against a real collection.

### 1.2 The design problem: the target matrix

`FILTER_GUIDE.md` §7 documents a matrix of which statement may target which output type. It is
almost entirely arbitrary restriction:

- `FILTER` cannot target a union, set-op, or compare output
- `COLUMNS` cannot target a lookup table, union, set-op, or compare output
- `EXPAND` does not accept `*`
- there is **no** `JOIN` statement, though the guide admits "the runtime model supports them
  internally"

These are not features. They are the visible edges of a model where *requests* and *derived
outputs* are different kinds of thing, and each verb hardcodes which kinds it accepts. Every new
verb multiplies the matrix.

The guide's own §11 "Common Mistakes" table is, in four of ten rows, users tripping over exactly
this.

### 1.3 What is already solved on this side

The MCP server already owns most of the non-DSL half. Verified:

| `Postman-excel-report-automation` has | `mcp-server` already has | Outcome |
| --- | --- | --- |
| `postman/PostmanCollectionParser` | `tools/PostmanCollectionParser` | duplicate — ours wins |
| `http/RequestExecutor` (320 ln) | `tools/ApiToolExecutor` (+ rate limit, 1MB cap, host allowlist) | duplicate — ours wins |
| `auth/CredentialStore`, `VariableResolver` | `connectors/CredentialCipher` (AES-256-GCM) | ours is stronger |
| CLI-only invocation | async job pattern (`PluginRegistry`, `ConnectionService`) | ours fits long runs |
| no UI | React SPA | where the editor goes |

And `org.apache.poi:poi-ooxml:5.4.1` is **already on the classpath**, transitively via
`tika-parsers-standard-package` (confirmed with `mvn dependency:tree`). Excel output costs **zero
new backend dependencies**.

**So: port the language and the renderer. Drop `http/`, `auth/`, `postman/`, `cli/`.**

### 1.4 Data visualization is the primary driver

The original CLI's only output was a workbook. The actual requirement is **interactive dashboards** —
charts, KPI tiles, filters — with Excel as one export among them. That target is specified in
[`dashboard-design.md`](./dashboard-design.md) and it changes RQL's job description: RQL is the
*substrate*, and it must stay presentation-free so two renderers (workbook, dashboard) can sit on
one language. Concretely this raises the stakes on three things already in this design — the
uniform dataset model (§2), the never-throwing parser (§5), and schema inference (§6) — because a
live dashboard exercises all three on every keystroke.

---

## 2. Core model: everything is a dataset

One rule replaces the entire §7 matrix:

> A **dataset** is an ordered list of rows. Every source produces a dataset. Every stage consumes
> one or more datasets and produces a dataset. Every stage accepts every dataset.

There is no distinction between "a request output" and "a union output". `where` after a `union`
is legal because `where` takes a dataset and a union *is* a dataset. Nothing special is required to
make it work; it falls out of the model.

This kills, by construction:

- "`FILTER` does not change a union"
- "`COLUMNS` does not affect `LOOKUP_TABLE` / `UNION` / `INTERSECT` / `COMPARE` sheets"
- "`FILTER` does not change `INTERSECT` / `EXCEPT` input rows"
- "`EXPAND` wildcard not working"
- the missing `JOIN`

The old language already gestured at this — `$TOP_POSTS = FILTER $ALL_POSTS WHERE …` proves derived
datasets work. RQL generalises that from a special case to *the* case.

---

## 3. Syntax

### 3.1 Shape

Pipelines, with SQL keyword vocabulary:

```rql
use collection "jsonplaceholder";

let posts =
  request "List all posts"
  |> where userId = $targetUser
  |> order by id asc
  |> limit 50;

let top_posts = posts |> where id > 25;

let merged =
  union all [ request "List all posts", request "List all todos" ]
  |> where completed is true          -- legal; impossible in the old language
  |> select id, title;

emit posts     as "Target user posts";
emit merged    as "Posts + todos";
```

### 3.2 Why pipelines

The decisive argument is IDE affordance. After `|>` the set of legal next tokens is small, closed,
and known without any semantic analysis — which is exactly the condition for completion that feels
instant and correct. In a nested-SQL grammar the cursor position inside `SELECT … FROM (SELECT …)`
requires resolving the inner query before the outer field list can be completed.

Secondary: `|>` is a natural **resync point** for error recovery (§5.2), giving statement-level
*and* stage-level recovery for free.

Rejected alternatives:

- **Nested SQL (`SELECT … FROM (…)`)** — familiar, but worse completion, worse recovery, and
  reads poorly for 6-stage transforms.
- **Keep statement-per-verb with a target key** (`FILTER "x" WHERE …`) — smallest diff, but keeps
  the target matrix alive; the restrictions come back the moment a verb forgets to accept a
  derived name.

### 3.3 Grammar (EBNF)

```ebnf
program       = { statement } ;
statement     = ( use_stmt | set_stmt | let_stmt | emit_stmt | report_block ) ";" ;

use_stmt      = "use" , "collection" , string ;
set_stmt      = "set" , ident , "=" , literal ;
let_stmt      = "let" , ident , "=" , pipeline ;
emit_stmt     = "emit" , pipeline , [ "as" , string ] ;

pipeline      = source , { "|>" , stage } ;

source        = "request" , string
              | ident                              (* reference to a prior let *)
              | combinator ;

combinator    = ( "union" [ "all" ] | "intersect" | "except" | "diff" ) ,
                "[" , pipeline , { "," , pipeline } , "]"
              | "compare" , "on" , field , "[" , pipeline , { "," , pipeline } , "]" ;

stage         = "where"    , expr
              | "select"   , projection , { "," , projection }
              | "order" , "by" , sort_key , { "," , sort_key }
              | "limit"    , integer
              | "offset"   , integer
              | "distinct"
              | "group" , "by" , field , { "," , field } , [ "agg" , agg_list ] , [ "having" , expr ]
              | "expand"   , field , [ "as" , ident ]
              | "join"     , pipeline , "on" , expr , [ "kind" , ( "inner" | "left" ) ]
              | "lookup"   , pipeline , "by" , field , [ "as" , ident ]
              | "rename"   , field , "as" , string , { "," , field , "as" , string }
              | "parse" , "date" , field , "format" , string , [ "timezone" , string ] ;

projection    = expr , [ "as" , string ] ;
sort_key      = field , [ "asc" | "desc" ] ;
agg_list      = agg_fn , [ "as" , ident ] , { "," , agg_fn , [ "as" , ident ] } ;
agg_fn        = ( "count" , "(" , ( "*" | field ) , ")" )
              | ( ( "sum" | "avg" | "min" | "max" ) , "(" , field , ")" ) ;

expr          = or_expr ;
or_expr       = and_expr , { "or" , and_expr } ;
and_expr      = not_expr , { "and" , not_expr } ;
not_expr      = [ "not" ] , primary ;
primary       = "(" , expr , ")"
              | "if" , expr , "then" , expr , [ "else" , expr ]
              | comparison ;
comparison    = operand , comp_op , operand
              | operand , ( "is" , [ "not" ] , ( "null" | "true" | "false" ) )
              | operand , [ "not" ] , "in" , "(" , literal , { "," , literal } , ")"
              | operand , [ "not" ] , ( "like" | "ilike" ) , string
              | operand , ( "contains" | "starts" "with" | "ends" "with" | "regex" ) , operand
              | operand , "between" , operand , "and" , operand
              | operand , "date" , "preset" , preset_name ;
comp_op       = "=" | "!=" | ">" | ">=" | "<" | "<=" ;
operand       = field | literal | variable ;

field         = ident , { "." , ident }              (* dotted JSON path *)
              | "[" , string , "]" ;                 (* bracket form: names with spaces/dots *)
variable      = "$" , ident ;
literal       = string | number | "true" | "false" | "null" ;
```

### 3.4 Fixes encoded in the grammar

| Old sharp edge | Fix |
| --- | --- |
| `DATE_CONFIG <request>.<field>` breaks on request names containing spaces | Sources are always quoted strings; the field is a separate token. The `<request>.<field>` fused token is gone. |
| Bare identifiers ambiguous with keywords | `field` supports a bracket form `["order by"]` for any awkward name. |
| `EXPAND` has no `*` | `expand` is a stage; applying to many datasets is a loop over `let`s, not a wildcard. Wildcards removed entirely. |
| No `JOIN` | `join` and `lookup` are ordinary stages. |
| `IF` without `ELSE` silently passes the row | `else` required in `where` position; a diagnostic (`RQL041`) explains why. |
| `ILIKE` documented as "currently behaves the same as `LIKE`" | `ilike` is case-insensitive; `like` is case-sensitive. Actually different. |
| `*` wildcard targets with override precedence | Removed. Explicit `let` bindings replace it; precedence rules vanish. |

### 3.5 Report block

The dashboard sublanguage is kept (it earns its place) but made uniform — every widget is
`widget_kind args [modifiers]`, instead of seven bespoke statement forms:

```rql
report {
  title       "API Showcase Dashboard" color "#1A5276";
  description "Variables and derived datasets";

  metric  "Posts for target user" = count(posts);
  metric  "Completed todos"       = count(done);
  metric  "Coverage"              = if count(posts) > 0 and count(done) > 0
                                      then "present" else "incomplete";

  table   "Scorecard" headers ["Metric", "Count", "Level"] rows [
    ["Target user posts", count(posts), if count(posts) > 0 then "OK" else "None"]
  ];

  table   merged  title "Posts + todos";
  status  color "#228B22";
  metrics;
}
```

`KV` / `LV` collapse into `metric` (the bold/plain distinction becomes a style modifier, not a
keyword). `QT` / `QUICK_TABLE` / `LABEL_TABLE` / `TABLE` collapse into `table` with modifiers.

---

## 4. AST and diagnostic model

### 4.1 Spans on everything

```java
/** Half-open [start, end). Offsets are for the editor; line/col for humans. */
public record Span(int startOffset, int endOffset,
                   int startLine, int startCol,
                   int endLine, int endCol) {}

public sealed interface Node {
    Span span();
}
```

Every node carries a span. This is what enables precise underlining, hover, and
go-to-definition on `$vars` and `let` bindings. The current parser discards position after the
error message is formatted.

### 4.2 Node types

```java
public sealed interface Stmt extends Node
    permits UseStmt, SetStmt, LetStmt, EmitStmt, ReportBlock, ErrorStmt {}

public sealed interface Source extends Node
    permits RequestSource, RefSource, Combinator, ErrorSource {}

public sealed interface Stage extends Node
    permits Where, Select, OrderBy, Limit, Offset, Distinct, GroupBy,
            Expand, Join, Lookup, Rename, ParseDate, ErrorStage {}

public sealed interface Expr extends Node
    permits Binary, Unary, Comparison, IfElse, FieldRef, VarRef, Literal, ErrorExpr {}

public record Pipeline(Source source, List<Stage> stages, Span span) implements Node {}
```

The `Error*` variants are the load-bearing part: a malformed stage becomes an `ErrorStage` **in
the tree**, not an exception. Downstream passes (symbol collection, completion, type checks) keep
running over the rest of the program.

### 4.3 Diagnostics

```java
public enum Severity { ERROR, WARNING, INFO, HINT }

public record Fix(String label, String replacement, Span replaceSpan) {}

public record Diagnostic(Span span,
                         Severity severity,
                         String code,        // stable, e.g. "RQL014"
                         String message,
                         List<Fix> fixes) {}

public record ParseResult(Program program, List<Diagnostic> diagnostics) {}
```

**The parser never throws.** `ParseResult` always has a program; it may contain error nodes.

Stable codes matter — they anchor documentation, allow per-rule suppression later, and make tests
assert on `RQL014` rather than on English prose.

| Range | Meaning |
| --- | --- |
| `RQL0xx` | lexical / syntactic |
| `RQL1xx` | name resolution (unknown request, unbound `let`, undefined `$var`) |
| `RQL2xx` | type / semantic (aggregate outside `group by`, `sum(*)`) |
| `RQL3xx` | lint / style (unused `let`, shadowed binding, `emit` with no rows possible) |

Example with a fix:

```json
{
  "span": { "startOffset": 142, "endOffset": 154, "startLine": 8, "startCol": 12,
            "endLine": 8, "endCol": 24 },
  "severity": "ERROR",
  "code": "RQL101",
  "message": "Unknown request \"List all pots\" in collection \"jsonplaceholder\".",
  "fixes": [ { "label": "Change to \"List all posts\"",
               "replacement": "\"List all posts\"",
               "replaceSpan": { "startOffset": 142, "endOffset": 154, "…": "…" } } ]
}
```

Levenshtein over the known request names produces the suggestion. Same technique for field names
once §6 schema inference is in place.

---

## 5. Fault tolerance

### 5.1 Requirements

1. A syntax error never suppresses diagnostics elsewhere in the file.
2. A syntax error never disables completion elsewhere in the file.
3. Every input, including empty and pathological input, yields a `ParseResult` — no exception
   escapes the parser.
4. Partial programs are analysable: `let a = request "X" |>` (trailing, incomplete) still binds
   `a` and still knows `a`'s schema.

### 5.2 Recovery strategy

Two nested resync levels, both cheap because the grammar has explicit delimiters:

- **Statement level** — on an unrecoverable error, emit a diagnostic, discard tokens to the next
  `;` at nesting depth 0, emit `ErrorStmt`, continue.
- **Stage level** — inside a pipeline, resync to the next `|>` or `;`, emit `ErrorStage`, continue.
  A broken `where` therefore does not cost the `select` after it.

Plus two local techniques:

- **Virtual insertion** — a missing closing `)` or `]` at a resync boundary is reported and
  *assumed present*, so one missing bracket does not cascade.
- **Keyword-set anchoring** — when the token stream is confused inside an expression, resync to the
  next token in the stage-keyword set (`where`, `select`, `order`, …), which cannot appear mid-expression.

### 5.3 Degradation ladder for execution

Fault tolerance is not only a parse concern. A run should degrade, not abort:

| Failure | Behaviour |
| --- | --- |
| One request returns 5xx / times out | That dataset is empty; a `WARNING` row lands on the `status` sheet; other sheets still render. |
| A field in `select` is absent from some rows | Blank cell, one `RQL2xx` hint at validate time. Not an error — JSON is ragged by nature. |
| A date fails `parse date` | Cell keeps the raw string; warning counted once per field, not once per row. |
| `emit` produces zero rows | Sheet is still created, with a "no rows matched" note. Silence is worse than an empty sheet. |
| Excel render fails on one sheet | Remaining sheets still written; failed sheet replaced by an error sheet. |

Rule: **a run always produces a workbook.** A report that partly worked is far more useful than a
stack trace, and this is the difference the CLI never made.

---

## 6. Schema inference (what makes it feel like an IDE)

Autocomplete on `where <cursor>` requires knowing the fields a request returns. Neither collection
files nor OpenAPI reliably describe response *bodies* in practice.

Approach — observed schema, cached:

1. On any successful execution, walk each response's JSON and record the set of dotted paths, each
   with observed types and a null/absent count.
2. Persist per `(connection_id, tool_id)` in a `response_schemas` table (JSON blob, last-seen
   timestamp, sample count).
3. Completion reads this cache. Never blocks on a network call.
4. Offer an explicit **"probe"** action in the editor — executes a single request once, purely to
   populate the schema. Cheap, user-initiated, obvious.
5. Cache miss degrades honestly: completion returns nothing rather than guessing, and a `HINT`
   diagnostic offers the probe as a fix.

Types drive better diagnostics too — `where createdAt > 5` on a string-typed path is a `RQL2xx`
warning.

---

## 7. Endpoint contract

All under `/api/reports`. Follows existing conventions: `IllegalArgumentException` → 400,
`IllegalStateException` → 409 via `@ExceptionHandler`; long work uses the async job pattern already
used by `PluginRegistry` and `ConnectionService`.

### 7.1 Analysis (hot path — called on every keystroke, debounced ~200ms)

```
POST /api/reports/analyze
{
  "source":       "<full document text>",
  "connectionId": "…",          // resolves request names + schema cache
  "cursorOffset": 142            // optional; omit for lint-only
}
→ 200
{
  "diagnostics": [ Diagnostic, … ],
  "completions": [                                   // present only when cursorOffset given
    { "label": "userId", "kind": "FIELD", "detail": "number",
      "insertText": "userId", "replaceSpan": {…} }
  ],
  "symbols": [                                       // outline / go-to-definition
    { "name": "posts", "kind": "LET", "span": {…}, "schema": ["id","userId","title"] }
  ]
}
```

Pure function of `(source, connectionId, cache)`. No execution, no writes. Must stay fast enough
to run on a keystroke — target p95 < 30ms for a 200-line document, which a hand-written parser
over a few KB comfortably meets.

### 7.2 Execution (async job)

```
POST   /api/reports/runs           { "source": "…", "connectionId": "…" }  → 202 { "jobId": "…" }
GET    /api/reports/runs/{jobId}   → { "state": "RUNNING|SUCCEEDED|FAILED|PARTIAL",
                                       "progress": { "done": 3, "total": 7 },
                                       "warnings": [ … ],
                                       "artifactId": "…" }
GET    /api/reports/artifacts/{id} → 200 application/vnd.openxmlformats-…sheet  (the .xlsx)
DELETE /api/reports/runs/{jobId}   → cancel
```

`PARTIAL` is a first-class terminal state — §5.3's degradation made visible rather than laundered
into `SUCCEEDED`.

### 7.3 Saved definitions (CRUD)

```
GET    /api/reports/definitions
POST   /api/reports/definitions       { "name": "…", "source": "…", "connectionId": "…" }
GET    /api/reports/definitions/{id}
PUT    /api/reports/definitions/{id}
DELETE /api/reports/definitions/{id}
```

Table `report_definitions` (id, name, source, connection_id, created_at, updated_at) added to
`schema.sql` per the existing additive-`ALTER TABLE` convention.

### 7.4 Frontend

- Route `/reports` — **must** also be registered in `WebMvcConfig` as
  `registry.addViewController("/reports")`, or direct navigation 404s. This bug has already
  happened once on `/connections`.
- All calls go in `src/api.ts`. No `fetch()` in components.
- Editor: **CodeMirror 6** (~200KB) — `@codemirror/state`, `@codemirror/view`,
  `@codemirror/language`, `@codemirror/autocomplete`, `@codemirror/lint`. This is the SPA's first
  substantial UI dependency (`package.json` currently holds only `react`, `react-dom`,
  `react-router-dom`). Monaco is rejected: 2MB+, ships web workers, fights Vite's bundling, and
  would dominate a 280KB bundle.
- `linter` extension ← `/analyze` diagnostics. `autocompletion` ← `/analyze` completions.
  Syntax highlighting via a hand-written `StreamLanguage` — the grammar is small enough that a
  Lezer grammar is not worth the build-step cost.
- Styling uses existing OKLCH tokens from `styles.css`. No new colour values.

---

## 8. Package layout

```
com.mcpserver.reports
├── lang/
│   ├── Lexer.java            token stream, spans, never throws
│   ├── Parser.java           recursive descent + recovery; returns ParseResult
│   ├── ast/                  sealed Node hierarchy (§4.2)
│   ├── Diagnostic.java       Severity, Fix, codes
│   └── Analyzer.java         name resolution, schema checks, lints
├── exec/
│   ├── Planner.java          AST → dataset op graph
│   ├── Dataset.java          rows + inferred schema
│   ├── ops/                  Where, Select, GroupBy, Join, Union, …
│   └── ReportRunner.java     drives ApiToolExecutor, degradation ladder
├── render/
│   └── ExcelRenderer.java    POI; ported from ExcelReportGenerator (2,643 ln)
├── ReportController.java
├── ReportService.java
└── ReportDefinitionRepository.java
```

`exec/` calls the existing `tools.ApiToolExecutor`. It does **not** open HTTP connections itself —
that would bypass the rate limit, host allowlist, and 1MB response cap already enforced there.

Per the standing convention, `services/` and `exec/` must not leak servlet types; the controller is
the only HTTP-aware layer.

---

## 9. What gets ported vs. rewritten

| Source (old repo) | Lines | Disposition |
| --- | --- | --- |
| `filter/FilterQueryParser` | 1,302 | **Rewrite** — the recovery model is incompatible with a throwing parser |
| `filter/FilterValidator` | 698 | **Rewrite** — folds into `Analyzer`, becomes non-throwing |
| `filter/RowConditionEvaluator` | 411 | **Port**, lightly — the operator semantics are good |
| `filter/DateWindowResolver` | 134 | **Port** as-is |
| `filter/*Spec`, `*Item` records | ~700 | **Port** as AST/plan records with spans added |
| `excel/ExcelReportGenerator` | 2,643 | **Port** — largest single win, and POI is already on the classpath |
| `excel/SheetStyleFactory` | — | **Port** |
| `http/RequestExecutor` | 320 | **Drop** → `tools.ApiToolExecutor` |
| `postman/*` | ~600 | **Drop** → `tools.PostmanCollectionParser` |
| `auth/*` | ~400 | **Drop** → `connectors.CredentialCipher` |
| `cli/*`, `Main` | ~250 | **Drop** |

The 13 files in `filters/` become the **migration test corpus**: each is hand-translated to RQL
once, and the pair is asserted to produce identical workbooks. That is the only credible proof the
redesign did not silently lose semantics.

---

## 10. Phase placement and constraints

**This is ahead of the tracker.** `docs/plan.md` has Phase 1 (Foundation) and Phase 2 (Knowledge &
Search) still open. Report generation is Phase 3/4 work — it is closest to Phase 4's "an unseen API
onboards by file". Proceeding is a deliberate sequencing choice and needs the `DECISIONS.md` entry
that accompanies this document.

Constraints this design must respect (from `CLAUDE.md`):

- **ACL tags.** If a report run ever writes through `IngestionService`, chunks must carry ACL tags
  at capture time, not deferred. As designed it does not ingest — it reads tools and writes a file
  — so this is a no-op today, but any future "ingest the report" feature inherits the rule.
- **No new backing services.** POI is on the classpath; CodeMirror is a frontend dependency. No
  Docker, no external renderer, no headless browser.
- **`server.address=127.0.0.1` stands.** Generated `.xlsx` artifacts are served from the same
  guarded origin. No public artifact URLs.
- **Additive schema only.** `report_definitions` and `response_schemas` go into `schema.sql` as
  plain `CREATE TABLE IF NOT EXISTS` / `ALTER TABLE ADD COLUMN`, tolerated on re-run by
  `continue-on-error: true`.
- **E2E or it didn't ship.** Done means: type a query in the browser, see live diagnostics, run it,
  download a correct workbook. Not a green unit suite.

---

## 11. Open questions

1. **Is a report an MCP tool?** A saved definition could be exposed as an MCP tool
   (`run-weekly-api-report`) so an AI client can trigger it. Attractive, but a write-ish action
   returning a binary — likely needs the Phase 3 confirmation-token workflow. Deferred, not
   designed here. *(Partly resolved by the dashboard layer: `POST /api/dashboards/data` returns
   JSON, not a binary, so a dashboard-as-tool fits the existing read-only tool shape without the
   confirmation flow — see `dashboard-design.md` §12.1. The workbook case keeps the problem.)*
2. **File extension / name.** `.rql` proposed. Alternative: keep `.filter` to signal continuity.
   The grammar is breaking either way, so a new extension is the more honest signal.
3. **Multi-collection blocks.** The old language allowed several `COLLECTION` blocks per file with
   merge rules (§8 of the old guide). RQL currently assumes one `use collection`. Multi-connection
   reports are plausible — deferring until someone asks.
4. **Streaming progress.** `/runs/{jobId}` polling matches existing conventions, but the app
   already does SSE for chat. SSE progress would be nicer; polling is consistent. Chose consistency,
   revisit if runs get long.
5. **Formatting / prettify.** A canonical formatter is cheap once a CST with spans exists and would
   make the editor feel finished. Not in the first cut.

---

## 12. Recommended build order

Each step is independently verifiable — no step requires the next to prove itself.

1. **`lang/`** — lexer, parser, recovery, diagnostics. Test: the 13 corpus files, plus a
   fuzz/truncation suite asserting *no exception escapes* on any prefix of any corpus file.
2. **`Analyzer`** — name resolution against a real connection, `RQL1xx` codes, Levenshtein fixes.
3. **`POST /api/reports/analyze`** — verifiable with `curl` alone, before any UI exists.
4. **`exec/`** — dataset ops over the existing `ApiToolExecutor`, degradation ladder, `PARTIAL`.
5. **`render/`** — port the POI renderer. Test: corpus workbook equivalence.
6. **CodeMirror editor** — highlight → lint → completion, in that order.
7. **Schema inference + probe** — last, because everything before it works without it.

Steps 1–4 are the prerequisite for the dashboard layer; `dashboard-design.md` §11 picks up from
there. Step 5 (the POI renderer) is **not** a prerequisite for dashboards and can be deferred or
resequenced after them — the workbook is one export target, not the trunk.
