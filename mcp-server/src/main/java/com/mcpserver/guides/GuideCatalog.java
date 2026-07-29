package com.mcpserver.guides;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One small, intentionally stable catalogue for the in-app guide, REST clients, and MCP client
 * orientation resources. Runtime guidance lives here rather than in the SPA so an MCP client sees
 * the same safety and workflow rules as a person using the web application.
 */
@Component
public class GuideCatalog {

    public record GuideSummary(String id, String title, String summary, String audience) {
    }

    public record GuideSection(String title, String body, List<String> steps, String code, String note) {
    }

    public record GuideArticle(String id, String title, String summary, String audience,
                               List<GuideSection> sections) {
    }

    private final List<GuideArticle> articles = List.of(
            new GuideArticle(
                    "start",
                    "Get started",
                    "The shortest path from an empty workspace to cited answers.",
                    "Everyone",
                    List.of(
                            section("1. Prepare search", "Open Plugins and install the vector store and embedding model. "
                                            + "Keyword search can still use already indexed documents if semantic search is unavailable.",
                                    List.of("Open Plugins.", "Install and enable SQLite Vec Store and Nomic Embedding.",
                                            "Confirm both show Ready."), null, null),
                            section("2. Add knowledge", "Create folders or upload files in Files. Uploading starts extraction, "
                                            + "chunking, and indexing in the background.",
                                    List.of("Open Files.", "Choose a folder and upload files or a folder tree.",
                                            "Wait for ingestion to finish before relying on the new content."), null, null),
                            section("3. Ask and verify", "Use Chat for a focused conversation. Each answer keeps its evidence collapsed "
                                            + "until you need to inspect the underlying RAG files or web results.",
                                    List.of("Ask a specific question.", "Expand evidence when you need provenance.",
                                            "Refine the question when the cited passages do not answer it."), null,
                                    "This local app returns grounded search context; it does not send your prompts or files to a third-party answer provider.")
                    )),
            new GuideArticle(
                    "knowledge",
                    "Knowledge and web search",
                    "How files, RAG evidence, and the optional web source fit together.",
                    "Everyone",
                    List.of(
                            section("Local knowledge first", "Plain chat questions use the same hybrid RAG search as the search API. "
                                            + "Results retain source names, paths, excerpts, and scores so you can assess the evidence.",
                                    List.of("Keep important source names meaningful.", "Use exact terms from documents for narrow searches.",
                                            "Treat cited excerpts as evidence, not a complete source document."), null, null),
                            section("Optional web augmentation", "Web results come from the local SearXNG plugin and are merged for the current "
                                            + "query only. They are not added to your local knowledge store.",
                                    List.of("Install and start SearXNG from Plugins.", "Enable web search only when current external information is necessary.",
                                            "Open the resulting URL before relying on a web claim."), null,
                                    "Web augmentation degrades gracefully when SearXNG is unavailable; local RAG evidence remains available.")
                    )),
            new GuideArticle(
                    "api-tools",
                    "API tools and insights",
                    "Connect an API, invoke an imported request deliberately, then explore safe read data.",
                    "Operators",
                    List.of(
                            section("Import and find a tool", "Connections can import a Postman collection or OpenAPI document. "
                                            + "Enabled requests appear as deterministic tools and can be invoked with the # grammar.",
                                    List.of("Open Connections and create/import a connection.", "Review each request before enabling it.",
                                            "Use #tool_name or @app #tool_name in Chat."),
                                    "#inventory_list_products\n@app #create_ticket \"Password reset for Ada\"", null),
                            section("Approval is intentional", "Read requests execute immediately. Write requests return a preview and a short-lived "
                                            + "confirmation token; they must not execute until a person explicitly approves the preview.",
                                    List.of("Inspect the resolved URL, headers, and body.", "Ask the person for a clear approval.",
                                            "Confirm once; tokens are single-use and expire."), null,
                                    "Never reuse a confirmation token or treat an earlier approval as approval for changed arguments."),
                            section("Build a small insight", "Insights run enabled GET tools through a constrained RQL document and render "
                                            + "charts, tables, and summary blocks. One insight can read from several connected apps.",
                                    List.of("Open Insights and start from the included example.",
                                            "Qualify a request with its app when two apps share a request name.",
                                            "Run it, inspect the data table, then save it to the library."), null, null)
                    )),
            new GuideArticle(
                    "queries",
                    "Query system (RQL)",
                    "Turn imported read requests — from one app or several — into rows you can filter, aggregate, and chart.",
                    "Operators",
                    List.of(
                            section("What a query runs against", "RQL queries imported API requests, not the knowledge base. "
                                            + "Every query goes through the same executor and credentials as any other tool call: enabled read "
                                            + "requests only, no second credential path.",
                                    List.of("Import a collection on Connections.", "Enable the GET requests you want to query on Apps.",
                                            "Reference a request by its display name."),
                                    "let posts = request \"List all posts\"\n  |> limit 25;", null),
                            section("Statements and pipelines", "A program is statements ending in ';'. Use set for parameters, let to bind a "
                                            + "dataset, and emit to publish one. A pipeline is a source followed by stages joined with '|>'.",
                                    List.of("set name = value; then reference it as $name.", "let name = pipeline; binds a dataset.",
                                            "Stages: where, select, order by, limit, offset, distinct, group by, expand, rename."),
                                    "set minUser = 1;\n\nlet by_user = request \"List all posts\"\n  |> where userId >= $minUser\n"
                                            + "  |> group by userId agg count(*) as posts\n  |> order by posts desc;", null),
                            section("Combine and enrich", "Beyond filtering, a pipeline can pull detail per row, join a prior dataset, "
                                            + "work with dates, and compare sources. Text matching is case-insensitive throughout.",
                                    List.of("lookup request \"...\" by <field> runs a detail request for every row.",
                                            "parse date <field> format \"...\" timezone \"...\" enables date_preset and date ranges.",
                                            "intersect, except, and diff add _source and _in_<label> provenance columns.",
                                            "compare [a as \"x\", b as \"y\"] on <field> builds a value matrix with _count."),
                                    "let detailed = orders\n  |> lookup request \"Get order detail\" by id\n"
                                            + "  |> where createdAt date_preset THIS_MONTH;", null),
                            section("Read the diagnostics", "The editor analyses the document as you type and returns coded diagnostics with "
                                            + "source positions. Execution never aborts: a failed request becomes an empty dataset and every other "
                                            + "dataset still runs.",
                                    List.of("RQL101 — the request name does not exist in this collection.",
                                            "RQL102 — the request is disabled and returns no rows.",
                                            "RQL104 — the request writes data and cannot be queried.",
                                            "RQL201 — the request ran but returned an error status."), null,
                                    "See docs/query-language-reference.md for the full grammar, every diagnostic code, and the "
                                            + "mapping from .filter report keywords to their RQL spelling.")
                    )),
            new GuideArticle(
                    "insights",
                    "Build an insight",
                    "A short path from one request to a saved insight with KPIs, a chart, and a table.",
                    "Operators",
                    List.of(
                            section("1. Start from one request", "Open Insights. Begin with a single request and a table, so you "
                                            + "can see the real field names before designing anything.",
                                    List.of("Optionally pick a default app in the header.", "Write one let with a limit.",
                                            "Bind it to a DataTable and press Run insight."),
                                    "```rql\nlet records = request \"List all posts\" |> limit 25;\n```\n\n<DataTable data={records} />",
                                    "If the table shows one odd row, the API nests its rows under a key. Use expand to unwrap it."),
                            section("2. Aggregate, then visualise", "Charts need one row per category, which is what group by produces. "
                                            + "Add the KPI row and the bar chart once the aggregated table looks right.",
                                    List.of("group by a field with count(*), sum, avg, min, or max.",
                                            "Use Stat for single numbers, BarChart for comparisons.",
                                            "DataTable, x, y, and data props are required where listed."),
                                    "<Stat value={count(records)} label=\"Rows\" />\n"
                                            + "<BarChart data={by_user} x=\"userId\" y=\"posts\" title=\"Posts per user\" />",
                                    "Dual axes and author-chosen series colours are rejected by design; add a second chart instead. "
                                            + "Colour is semantic only — true/false and request status colour themselves."),
                            section("3. Summarise beyond charts", "Text, KeyValue, LabelValue, QuickTable, and LabelTable write summary blocks "
                                            + "directly in the document; Status and Metrics report on the run itself. Values accept conditionals.",
                                    List.of("Use Text or KeyValue for a computed sentence or a single fact.",
                                            "Use QuickTable/LabelTable for inline tables written in the document.",
                                            "Add Status for per-request status and duration, Metrics for the aggregate."),
                                    "<KeyValue label=\"Coverage\" value={if count(detailed) = count(orders) then \"complete\" else \"partial\"} />\n"
                                            + "<Status />", null),
                            section("4. Parameterise, combine apps, and save", "Declare parameters in front matter and read them as $name. "
                                            + "Requests resolve across every connected app, and Save keeps the insight in the library.",
                                    List.of("Add params to the front matter with a type and default.",
                                            "Qualify a request as \"App: Request\" to read from another app in the same document.",
                                            "Name the insight and press Save; reopen it any time from the library."),
                                    "---\ntitle: API activity\nparams:\n  minUser: { type: number, default: 1 }\n---", null)
                    )),
            new GuideArticle(
                    "mcp-clients",
                    "Connect an MCP client",
                    "A protocol-level checklist for ChatGPT, Claude, Copilot, or another MCP-compatible client.",
                    "MCP client authors",
                    List.of(
                            section("Connect locally", "The Streamable HTTP endpoint is local-only until the authentication phase ships. "
                                            + "Point a compatible MCP client at the endpoint below.",
                                    List.of("Start the server.", "Add the Streamable HTTP endpoint in the MCP client.",
                                            "Complete initialize, then inspect server capabilities."),
                                    "http://127.0.0.1:8080/mcp", null),
                            section("Read orientation before acting", "After initialize, call resources/list and read the human-readable guide and the "
                                            + "machine-readable playbook. Prompts provide a reusable starting instruction.",
                                    List.of("Read mcp://enterprise-mcp/guides/operating-guide.",
                                            "Read mcp://enterprise-mcp/guides/llm-playbook.json.",
                                            "Optionally get the execute-grounded-task prompt with a task."), null,
                                    "The server still exposes its actual enabled tools through tools/list. Treat the guide as the workflow contract, not as a substitute for tool schemas."),
                            section("Use tools safely", "Use search-knowledge-base to get cited context. Imported tools are dynamic; inspect "
                                            + "their schemas before a call. Never call confirm-action without explicit, current human approval.",
                                    List.of("Search before making unsupported claims.", "Describe a proposed write and show its preview.",
                                            "Only then use the confirmation token supplied by that exact preview."), null, null)
                    )),
            new GuideArticle(
                    "development",
                    "Develop and operate",
                    "The everyday build, test, and troubleshooting commands for maintainers.",
                    "Developers",
                    List.of(
                            section("Run in development", "Spring Boot serves the API, MCP endpoint, and browser-native Web UI "
                                            + "from one process. Static HTML, CSS, and JavaScript changes appear after a browser refresh.",
                                    List.of("Start Spring Boot on port 8080.",
                                            "Open http://127.0.0.1:8080.",
                                            "Refresh the browser after a static UI edit."),
                                    "cd mcp-server && mvn spring-boot:run", null),
                            section("Verify before handoff", "The Maven test suite and complete JAR build are the required baseline. "
                                            + "The frontend has no generated bundle or Node dependency.",
                                    List.of("Run mvn test.", "Run mvn package -Dskip.bundle=true for a fast artifact check.",
                                            "Smoke the served routes in a browser."),
                                    "cd mcp-server && mvn test\ncd mcp-server && mvn package -Dskip.bundle=true", null),
                            section("Keep guides current", "The long-form developer and MCP-client guides live in docs/. The in-app guide and "
                                            + "MCP resources are generated from this catalog so people and MCP clients receive the same active workflow.",
                                    List.of("Update the relevant Markdown guide for durable detail.",
                                            "Update this catalog when the runtime workflow changes.",
                                            "Verify resources/list and prompts/list after MCP changes."), null, null)
                    ))
    );

    public List<GuideSummary> summaries() {
        return articles.stream()
                .map(article -> new GuideSummary(article.id(), article.title(), article.summary(), article.audience()))
                .toList();
    }

    public List<GuideArticle> articles() {
        return articles;
    }

    public Optional<GuideArticle> find(String id) {
        return articles.stream().filter(article -> article.id().equals(id)).findFirst();
    }

    /** A compact, procedural resource designed to be loaded directly into an MCP client's context. */
    public String llmGuideMarkdown() {
        return """
                # Enterprise MCP operating guide

                ## Purpose
                This local MCP server provides grounded knowledge retrieval and enabled API tools. It is bound to
                `127.0.0.1` and has no authentication yet; do not expose it beyond a trusted local/internal network.

                ## Start-of-session procedure
                1. Call `resources/list`.
                2. Read this resource and `mcp://enterprise-mcp/guides/llm-playbook.json`.
                3. Call `tools/list`; enabled imported tools can change while the server is running.
                4. For a concrete task, use the `execute-grounded-task` prompt or follow the workflow below.

                ## Grounded-answer workflow
                1. Call `search-knowledge-base` with a clear `query` and an appropriate `topK`.
                2. Base statements on the returned excerpts. Identify the source name/path when presenting evidence.
                3. If the evidence is insufficient, say what is missing and ask a focused follow-up. Do not invent
                   source content or claim an API action was performed when it was not.

                ## API-tool workflow
                1. Inspect the current tool schema from `tools/list` before calling an imported tool.
                2. Read-only tools may be called with valid arguments.
                3. A write tool returns a preview plus a short-lived, single-use confirmation token. Show the preview
                   and obtain explicit, current human approval before calling `confirm-action` with that exact token.
                4. Never infer approval from a previous request, reuse a token, or confirm a materially changed action.

                ## Error and privacy rules
                - Treat errors and empty search results as evidence that the task is incomplete; explain the next safe step.
                - Do not send user files, prompts, credentials, or tool output to an unrelated external service.
                - This guide describes workflow; the live tool list and each tool's input schema are authoritative.
                """;
    }

    /** Structured counterpart to {@link #llmGuideMarkdown()} for clients that prefer JSON. */
    public Map<String, Object> llmPlaybook() {
        return Map.of(
                "version", "1",
                "server", Map.of(
                        "endpoint", "http://127.0.0.1:8080/mcp",
                        "transport", "Streamable HTTP",
                        "networkScope", "local/trusted internal network only until authentication ships"),
                "sessionStart", List.of(
                        "Call resources/list.",
                        "Read mcp://enterprise-mcp/guides/operating-guide.",
                        "Call tools/list; do not assume a stale tool list is current."),
                "groundedAnswer", List.of(
                        "Call search-knowledge-base for evidence.",
                        "Cite source name or path from returned content.",
                        "State uncertainty or ask for a focused follow-up when evidence is insufficient."),
                "actionSafety", Map.of(
                        "readTools", "Call after validating the current input schema.",
                        "writeTools", "Show preview, obtain explicit current human approval, then call confirm-action with that exact token.",
                        "prohibited", List.of("Guessing tool arguments", "Reusing confirmation tokens", "Treating old approval as approval for changed actions")),
                "availableResources", List.of(
                        "mcp://enterprise-mcp/guides/operating-guide",
                        "mcp://enterprise-mcp/guides/llm-playbook.json"),
                "availablePrompts", List.of("orient-to-enterprise-mcp", "execute-grounded-task")
        );
    }

    private static GuideSection section(String title, String body, List<String> steps, String code, String note) {
        return new GuideSection(title, body, steps, code, note);
    }
}
