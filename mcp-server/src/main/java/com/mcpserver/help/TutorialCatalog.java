package com.mcpserver.help;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Ordered, hands-on walkthroughs, kept beside {@link HelpCatalog} for the same reason that one
 * exists: instructions live on the server so they cannot drift from what the application actually
 * does, rather than being hardcoded in the SPA.
 *
 * <p>The distinction from {@code HelpCatalog} is shape, not subject. A help topic is reference —
 * browse a topic, read the rules. A tutorial is a sequence with an outcome: every step names the
 * route it happens on and how to tell it worked, so a reader can follow it with the application
 * open beside them. Tutorials are deliberately <em>not</em> projected to MCP clients the way help
 * topics are ({@code McpGuideBridge}): they instruct a person driving the web UI, so an agent
 * reading them as operating rules would be misled.
 *
 * <p>Examples carry their own label and expected result rather than being one anonymous snippet per
 * step. A reader following along needs to know which of several spellings they are looking at and
 * what the application should do in response — a bare snippet with neither is the thing that makes
 * a tutorial impossible to check yourself against.
 */
@Component
public class TutorialCatalog {

    /**
     * One worked example. {@code result} is what the application should do in response, which is
     * what turns a snippet into something a reader can verify rather than merely copy.
     */
    public record TutorialExample(String label, String description, String language, String code,
                                  String result) {
    }

    /** A symptom a reader can actually hit at this step, paired with the fix. */
    public record TutorialFix(String symptom, String fix) {
    }

    /**
     * One step. {@code route} is where the work happens, so the page can link straight to it;
     * {@code verify} is how the reader knows to move on, which is what keeps a walkthrough honest.
     */
    public record TutorialStep(String title, String body, List<String> actions, String route,
                               String routeLabel, List<TutorialExample> examples, String verify,
                               String note, List<TutorialFix> troubleshooting) {
    }

    public record Tutorial(String id, String title, String summary, String outcome, String duration,
                           String level, List<String> prerequisites, List<String> nextTutorials,
                           List<TutorialStep> steps) {
    }

    public record TutorialSummary(String id, String title, String summary, String outcome,
                                  String duration, String level, int steps, int examples) {
    }

    private final List<Tutorial> tutorials = List.of(
            firstAnswer(),
            queryBar(),
            connectSources(),
            apiTools(),
            firstInsight(),
            rqlQueries(),
            mcpClient());

    public List<TutorialSummary> summaries() {
        return tutorials.stream()
                .map(tutorial -> new TutorialSummary(tutorial.id(), tutorial.title(), tutorial.summary(),
                        tutorial.outcome(), tutorial.duration(), tutorial.level(), tutorial.steps().size(),
                        exampleCount(tutorial)))
                .toList();
    }

    public Optional<Tutorial> find(String id) {
        return tutorials.stream().filter(tutorial -> tutorial.id().equals(id)).findFirst();
    }

    private static int exampleCount(Tutorial tutorial) {
        return tutorial.steps().stream().mapToInt(step -> step.examples().size()).sum();
    }

    // ---------------------------------------------------------------------------------------
    // 1. First grounded answer
    // ---------------------------------------------------------------------------------------

    private static Tutorial firstAnswer() {
        return new Tutorial(
                "first-answer",
                "Ask your first grounded question",
                "Turn an empty workspace into cited answers from your own documents.",
                "A question answered from your files, with the passages it came from.",
                "About 10 minutes",
                "Beginner",
                List.of("The server running on http://127.0.0.1:8080",
                        "One document you already know the contents of"),
                List.of("query-bar", "connect-sources"),
                List.of(
                        step("Turn on search",
                                "Search needs a vector store and an embedding model. Both ship with the app and "
                                        + "install locally — nothing is sent to a third party at query time.",
                                List.of("Open Plugins.",
                                        "Install and enable SQLite Vec Store.",
                                        "Install and enable Nomic Embedding."),
                                "/plugins", "Open Plugins",
                                List.of(),
                                "Both plugins report Ready.",
                                "Until they are ready the app runs in a degraded mode: files still work, and search "
                                        + "returns a structured \"not ready\" response rather than failing.",
                                List.of(
                                        fix("Install sits at Installing for a long time",
                                                "The embedding model is unpacking on first install. The job is polled, "
                                                        + "so navigating away does not cancel it."),
                                        fix("Semantic results are missing but keyword results work",
                                                "The embedding plugin is down. Already-indexed chunks stay lexically "
                                                        + "searchable, so restore the plugin rather than re-ingesting."))),
                        step("Add something to search",
                                "Uploading a file starts extraction, chunking, and indexing in the background. PDFs, "
                                        + "Office documents, Markdown, HTML, and plain text are all handled.",
                                List.of("Open Files.",
                                        "Create a folder, or pick an existing one.",
                                        "Upload a document you know the contents of.",
                                        "Wait for ingestion to finish before searching for it."),
                                "/files", "Open Files",
                                List.of(),
                                "The file appears in the folder and ingestion finishes.",
                                "Pick something you can check the answer against — verifying the first answer is the "
                                        + "point of this walkthrough.",
                                List.of(fix("A new file is not searchable yet",
                                        "Ingestion is still running. Watch the progress on Files; chunks appear only "
                                                + "once extraction, chunking, and embedding have all completed."))),
                        step("Ask, then check the evidence",
                                "A plain question runs hybrid retrieval — lexical and vector search, merged with RRF, "
                                        + "then reranked. The server does not write the answer for you; it shows you the "
                                        + "passages it found, with their source and score.",
                                List.of("Open Search.",
                                        "Ask something only your document can answer.",
                                        "Expand the evidence and read the matched passages.",
                                        "If they do not answer the question, narrow it using wording from the document."),
                                "/", "Open Search",
                                List.of(
                                        example("A question in plain language",
                                                "Anything that does not start with @ or # is a knowledge search.",
                                                "search",
                                                "how do we rotate database credentials",
                                                "Ranked passages with source name, path, excerpt, and score."),
                                        example("Narrowing with exact wording",
                                                "Lexical matching carries half the hybrid score, so terms lifted "
                                                        + "verbatim from the document sharpen a vague result.",
                                                "search",
                                                "credential rotation runbook quarterly owner",
                                                "The same search, biased toward passages containing those terms."),
                                        example("The same query over HTTP",
                                                "The Web UI is a REST channel over the same service — useful for "
                                                        + "checking retrieval outside the browser.",
                                                "bash",
                                                "curl -s 'http://127.0.0.1:8080/api/search?q=credential+rotation&topK=5'",
                                                "JSON with a results array; each result carries its source and score.")),
                                "The cited passages actually support the answer.",
                                "topK accepts 1-100 and defaults to 20. Nothing you type here leaves the machine.",
                                List.of(
                                        fix("Search says setup is required",
                                                "The vector store or embedding model is not ready. Finish step 1 on Plugins."),
                                        fix("The passages are off-topic",
                                                "That is a retrieval signal, not a failure. Re-ask with terms that appear "
                                                        + "in the document, or check the file finished ingesting."))),
                        step("Add current web results, deliberately",
                                "The Web toggle merges results from the local SearXNG plugin into the current query "
                                        + "only. Web content is never written into your knowledge store.",
                                List.of("Open Plugins and install and start SearXNG.",
                                        "Return to Search and enable the Web toggle in the composer.",
                                        "Ask a question whose answer changes over time.",
                                        "Open the source URL before relying on any web claim."),
                                "/", "Open Search",
                                List.of(example("Web-augmented search over HTTP",
                                        "The same flag the composer toggle sets.",
                                        "bash",
                                        "curl -s 'http://127.0.0.1:8080/api/search?q=latest+postgres+release&web=true'",
                                        "Local evidence merged with web results for this query only.")),
                                "Web results appear alongside local evidence, each with an openable URL.",
                                "The toggle stays disabled until SearXNG is installed and running, and a query with web "
                                        + "enabled degrades to local evidence if it stops.",
                                List.of(fix("The Web toggle is greyed out",
                                        "SearXNG is not installed or not started. Both happen on Plugins."))),
                        step("Export what you gathered",
                                "The export action in the header takes a source picker — indexed files and connected "
                                        + "apps — and returns a plain-text export of their chunks.",
                                List.of("Open Search and use the export action in the header.",
                                        "Select the files and apps you want.",
                                        "Download, then check the reported source and chunk counts."),
                                "/", "Open Search",
                                List.of(example("The endpoint behind the picker",
                                        "The counts come back as response headers, so a scripted export can assert "
                                                + "on them.",
                                        "bash",
                                        "curl -s -D - -o export.txt -X POST \\\n"
                                                + "  http://127.0.0.1:8080/api/summary-exports \\\n"
                                                + "  -H 'Content-Type: application/json' \\\n"
                                                + "  -d '{\"fileIds\":[\"<file-id>\"]}'",
                                        "A text body plus X-Export-Source-Count and X-Export-Chunk-Count headers.")),
                                "The download arrives and the UI reports how many sources and chunks it covered.",
                                "Limits are 500 selected sources and 25 MB of text. A source with nothing indexed yet "
                                        + "returns 409 telling you to wait for ingestion or run a backfill.",
                                List.of())));
    }

    // ---------------------------------------------------------------------------------------
    // 2. The query bar
    // ---------------------------------------------------------------------------------------

    private static Tutorial queryBar() {
        return new Tutorial(
                "query-bar",
                "Master the query bar",
                "One input, two behaviours: knowledge search and deterministic tool calls, told apart by syntax.",
                "Fluency with every form of query the bar accepts, and when each one applies.",
                "About 10 minutes",
                "Beginner",
                List.of("Search working (see \"Ask your first grounded question\")",
                        "Optional: one imported API connection for the tool examples"),
                List.of("api-tools", "first-insight"),
                List.of(
                        step("Know which of the two things you are doing",
                                "The distinction is syntactic, never a guess. A query starting with @ or # is a tool "
                                        + "query; anything else is a knowledge search. A # in the middle of a sentence is "
                                        + "ordinary text and falls through to search untouched.",
                                List.of("Open Search.",
                                        "Type a plain question and note the evidence-shaped result.",
                                        "Type a question containing a # mid-sentence and confirm it still searches."),
                                "/", "Open Search",
                                List.of(
                                        example("Knowledge search",
                                                "No leading sigil.",
                                                "search",
                                                "incident response runbook",
                                                "Cited passages from indexed content."),
                                        example("A # that is not a tool call",
                                                "Only a leading sigil switches modes.",
                                                "search",
                                                "what does ticket #4412 say about rollback",
                                                "A knowledge search, exactly as typed.")),
                                "Plain questions return evidence; only a leading @ or # opens the tool path.",
                                "Autocomplete appears as soon as you type either sigil, so you can discover names "
                                        + "without leaving the bar.",
                                List.of()),
                        step("Call a tool by name",
                                "# invokes an enabled tool deterministically — no interpretation and no guessing at "
                                        + "arguments. Names are keyword-matched across every enabled tool.",
                                List.of("Type # and start typing a request name.",
                                        "Pick a GET request from the autocomplete.",
                                        "Run it and read the response preview."),
                                "/", "Open Search",
                                List.of(
                                        example("Tool by name",
                                                "Matched across all enabled tools.",
                                                "search",
                                                "#list_projects",
                                                "The request runs immediately and its response is previewed."),
                                        example("Tool with an argument",
                                                "Quoted text is passed through as the argument.",
                                                "search",
                                                "#create_todo \"Call the vendor\"",
                                                "A write: a preview and a single-use confirmation token, not an execution.")),
                                "A GET request returns its response; a write returns a preview awaiting approval.",
                                "Read requests execute immediately. Writes never do — the API tools walkthrough covers "
                                        + "the full approval contract.",
                                List.of(fix("The tool cannot be found",
                                        "It is disabled, or the name belongs to another app. Enable it on Apps, or "
                                                + "scope the query with its @app slug."))),
                        step("Scope a call to one app or group",
                                "@ scopes a tool call to one connection or tool group, which is what you need when two "
                                        + "imported collections use the same request name.",
                                List.of("Type @ and pick an app from the autocomplete.",
                                        "Add # and the request name.",
                                        "Try @app on its own to browse what that app exposes."),
                                "/", "Open Search",
                                List.of(
                                        example("Scoped to an app",
                                                "The connection name becomes the @app slug at import.",
                                                "search",
                                                "@todo-app #create_todo \"Call the vendor\"",
                                                "The same call, resolved only within that connection."),
                                        example("Scoped to a tool group",
                                                "Groups scope the same way apps do.",
                                                "search",
                                                "@reporting #monthly_summary",
                                                "Resolved within that group's tools."),
                                        example("Browse an app",
                                                "An @app with no # lists the app's tools.",
                                                "search",
                                                "@todo-app",
                                                "A table of that app's requests, so you can see what exists before calling.")),
                                "The scoped call resolves to the request you expected, from the app you named.",
                                null,
                                List.of(fix("Two apps expose the same request name",
                                        "Scope the call with @app, or qualify the request as \"App: Request\" when "
                                                + "writing an insight."))),
                        step("Work in a session, not one query at a time",
                                "A session is a working transcript. Every search or tool call appends a turn and leaves "
                                        + "earlier requests and responses visible, so you can list records, read an id, "
                                        + "and then act on it without losing the list that supplied it.",
                                List.of("Run a GET that lists records.",
                                        "Read an id out of the response.",
                                        "Run a second tool using that id.",
                                        "Switch between Preview and Raw response on any turn."),
                                "/", "Open Search",
                                List.of(
                                        example("List, then act",
                                                "Two turns in one session; the first stays on screen.",
                                                "search",
                                                "@todo-app #list_todos\n@todo-app #update_todo 42 \"done\"",
                                                "The list stays visible above the update's preview."),
                                        example("How a response is shown",
                                                "Preview formats by content type; Raw response keeps the exact body.",
                                                "text",
                                                "JSON  -> objects become field lists, arrays become tables\n"
                                                        + "CSV   -> parsed into a table, quoted fields honoured\n"
                                                        + "XML   -> re-indented and shown as source\n"
                                                        + "HTML  -> shown as source, never rendered",
                                                "The same body, two views, neither of them executed.")),
                                "Both turns remain in the transcript and the second used the first's data.",
                                "Sessions and their ordered turns persist in this browser until you delete them.",
                                List.of())));
    }

    // ---------------------------------------------------------------------------------------
    // 3. Confluence and Jira
    // ---------------------------------------------------------------------------------------

    private static Tutorial connectSources() {
        return new Tutorial(
                "connect-sources",
                "Ingest Confluence and Jira",
                "Connect a remote source once, backfill it, and keep it current without re-uploading anything.",
                "A connected space or project whose pages and issues are searchable and stay up to date.",
                "About 20 minutes",
                "Intermediate",
                List.of("Search working (see \"Ask your first grounded question\")",
                        "A Confluence or Jira URL and credentials that can read it"),
                List.of("query-bar", "api-tools"),
                List.of(
                        step("Create the connection",
                                "Deployment type — Cloud versus Server/Data Center — is auto-detected, because the two "
                                        + "have diverged enough that guessing wrong breaks pagination and search.",
                                List.of("Open Connections and create a connection.",
                                        "Choose Confluence or Jira as the type.",
                                        "Enter the base URL.",
                                        "Choose the authentication mode that matches your deployment."),
                                "/connections", "Open Connections",
                                List.of(
                                        example("Cloud",
                                                "Basic auth with your account email and an API token.",
                                                "text",
                                                "Base URL:  https://your-team.atlassian.net\n"
                                                        + "Auth:      Cloud token / password (Basic)\n"
                                                        + "Username:  you@example.com\n"
                                                        + "Password:  <Atlassian API token>",
                                                "Deployment is detected as Cloud."),
                                        example("Server / Data Center",
                                                "A personal access token, with no username.",
                                                "text",
                                                "Base URL:  https://confluence.internal.example.com\n"
                                                        + "Auth:      Data Center PAT (Bearer)\n"
                                                        + "Token:     <personal access token>",
                                                "Deployment is detected as Server/Data Center.")),
                                "The connection is saved and shows the deployment it detected.",
                                "Credentials are encrypted with AES-256-GCM using a locally generated key file at "
                                        + "./data/connections.key. There is no Vault/KMS integration yet — a deliberate, "
                                        + "documented scope limit.",
                                List.of(fix("The wrong deployment was detected",
                                        "Check the base URL is the site root rather than a page or board URL, then "
                                                + "test the connection again."))),
                        step("Test the connection before trusting it",
                                "Test connection runs as a background job so a slow or unreachable host cannot block "
                                        + "the page. The job id is polled until it settles.",
                                List.of("Press Test connection on the new connection.",
                                        "Wait for the job to finish.",
                                        "Read the reported deployment and account."),
                                "/connections", "Open Connections",
                                List.of(example("Polling the job yourself",
                                        "The same async-job pattern the plugins use.",
                                        "bash",
                                        "curl -s http://127.0.0.1:8080/api/connections/jobs/<jobId>",
                                        "A job status that ends in a success or a specific credential/host error.")),
                                "The connection reports Connected.",
                                null,
                                List.of(
                                        fix("401 or 403 from the test",
                                                "The credential is right but the mode is wrong: Cloud needs email + API "
                                                        + "token (Basic), Data Center needs a PAT (Bearer)."),
                                        fix("The host is unreachable",
                                                "The server calls the URL directly, so the machine running this app "
                                                        + "needs network access to it — a VPN on your laptop is not enough "
                                                        + "if the server runs elsewhere."))),
                        step("Backfill the existing content",
                                "Backfill walks the source and ingests everything through the same pipeline as an "
                                        + "uploaded file — extraction, chunking, embedding — so nothing about search "
                                        + "needs to know where a chunk came from.",
                                List.of("Press Backfill.",
                                        "Watch the job until it completes.",
                                        "Open Search and query for something you know is in that space or project."),
                                "/connections", "Open Connections",
                                List.of(example("Confirming it landed",
                                        "Search has no awareness of the source; the content simply becomes findable.",
                                        "search",
                                        "onboarding checklist new starter",
                                        "Passages whose source is a Confluence page or Jira issue.")),
                                "Content from the remote source appears in search results.",
                                "ACL tags are captured on every chunk during ingestion; enforcement arrives in a later "
                                        + "phase. Capture is never deferred, even though nothing filters on it yet.",
                                List.of(fix("Backfill finishes but nothing is searchable",
                                        "Confirm the embedding plugin is Ready — ingestion needs it, and a degraded "
                                                + "plugin means chunks are stored without vectors."))),
                        step("Keep it current",
                                "Two mechanisms keep a connected source fresh, and you do not have to choose: webhooks "
                                        + "for immediacy where the deployment supports registering them, delta polling "
                                        + "as the floor.",
                                List.of("Register a webhook if your deployment supports it.",
                                        "Otherwise leave delta polling to run on its interval.",
                                        "Use Disable to pause a connection without deleting it."),
                                "/connections", "Open Connections",
                                List.of(
                                        example("Webhook intake",
                                                "The callback durably queues the event and acknowledges immediately, so "
                                                        + "processing time never threatens the ack SLA.",
                                                "http",
                                                "POST /api/connections/{id}/webhook?token=<callback-token>\n"
                                                        + "-> 202 Accepted",
                                                "The event is written to ingestion_events and processed on a worker thread."),
                                        example("Pausing a source",
                                                "Disabling excludes the connection from the polling query — that is the "
                                                        + "entire pause mechanism.",
                                                "text",
                                                "Connections -> the connection -> Disable",
                                                "Delta polling skips it until you enable it again.")),
                                "An edit made in the remote system becomes searchable here without a manual backfill.",
                                "If the process crashes mid-event, rows left PROCESSING are reset to PENDING at "
                                        + "startup, so a queued webhook is never silently dropped.",
                                List.of()),
                        step("Rotate credentials without losing state",
                                "Edit settings changes the name, base URL, or credentials in place. Changing the base "
                                        + "URL is the one edit that resets deployment and cursor state, because it is a "
                                        + "different source.",
                                List.of("Open Edit settings on the connection.",
                                        "Enter the new token; leaving it blank keeps the current one.",
                                        "Test the connection again after saving."),
                                "/connections", "Open Connections",
                                List.of(example("Rotation, in order",
                                        "Test before trusting the next scheduled poll.",
                                        "text",
                                        "1. Issue the new token in Confluence/Jira\n"
                                                + "2. Edit settings -> paste it -> Save\n"
                                                + "3. Test connection\n"
                                                + "4. Revoke the old token",
                                        "The connection returns to Connected on the new credential.")),
                                "The connection reports Connected on the new credential.",
                                "Switching the base URL performs a fresh backfill before reconnecting, so treat it as "
                                        + "moving to a new source rather than an edit.",
                                List.of())));
    }

    // ---------------------------------------------------------------------------------------
    // 4. API tools
    // ---------------------------------------------------------------------------------------

    private static Tutorial apiTools() {
        return new Tutorial(
                "api-tools",
                "Import an API and run a tool safely",
                "Turn a Postman collection or OpenAPI document into governed tools, then exercise the approval path.",
                "An imported API whose reads you can call and whose writes require an explicit approval.",
                "About 20 minutes",
                "Intermediate",
                List.of("A Postman collection or OpenAPI/Swagger document",
                        "Familiarity with the query bar (see \"Master the query bar\")"),
                List.of("first-insight", "rql-queries"),
                List.of(
                        step("Import the collection",
                                "Every request in the document becomes a callable tool named {app}_{request-name}, and "
                                        + "the connection name becomes the @app slug you use in the query bar.",
                                List.of("Open Connections and create an API connection.",
                                        "Upload a .json/.yaml/.yml file, or point at a spec URL.",
                                        "Press Detect auth to have the spec's scheme proposed.",
                                        "Choose the URL policy."),
                                "/connections", "Open Connections",
                                List.of(
                                        example("Use one base URL (default)",
                                                "Every imported request is sent to the connection's base URL — the "
                                                        + "setting that lets one collection move between environments.",
                                                "text",
                                                "Name:      Todo App        -> @todo-app\n"
                                                        + "Spec:      openapi.json\n"
                                                        + "URLs:      Use one base URL\n"
                                                        + "Base URL:  https://staging.api.example.com",
                                                "Tools resolve against the staging host regardless of the spec's servers."),
                                        example("Keep source URLs",
                                                "Preserves each absolute request URL; relative requests fall back to the "
                                                        + "base URL. Connection credentials can then reach several hosts, "
                                                        + "so use it only for sources you trust.",
                                                "text",
                                                "URLs:      Keep source URLs\n"
                                                        + "Base URL:  https://api.example.com  (fallback only)",
                                                "Each request keeps the host declared in the source document.")),
                                "The connection reports Connected and its requests are listed.",
                                "Changing the URL policy later reimports the collection, so persisted tool URLs and "
                                        + "their allowed hosts stay in sync.",
                                List.of(fix("Detect auth proposes nothing",
                                        "The spec declares no security scheme. Choose the mode manually — Basic, "
                                                + "Bearer, API key header, OAuth2, or none."))),
                        step("Enable only what should be callable",
                                "Nothing is callable straight after import: requests arrive disabled, and enabling one "
                                        + "is the moment a person decides it may run.",
                                List.of("Open Apps.",
                                        "Review each request and what it does.",
                                        "Enable the GET requests you want to use.",
                                        "Leave writes disabled until you need them."),
                                "/apps", "Open Apps",
                                List.of(example("Check what is enabled from the bar",
                                        "An @app with no # lists what that app exposes.",
                                        "search",
                                        "@todo-app",
                                        "A table of the app's requests and their enabled state.")),
                                "Only the requests you chose appear as callable tools.",
                                "Disabling a request that also feeds knowledge clears that role too, so it stops being "
                                        + "ingested as well as stopping being callable.",
                                List.of()),
                        step("Test a request in the builder",
                                "The request builder fills parameters, resolves the exact call, and shows the response "
                                        + "— including the preview path for writes — without you having to guess at the "
                                        + "shape from the spec.",
                                List.of("Open a request on Apps.",
                                        "Fill in its parameters.",
                                        "Press Preview request to resolve the call without sending it.",
                                        "Send it and read the response."),
                                "/apps", "Open Apps",
                                List.of(
                                        example("A resolved read",
                                                "Preview shows the URL, headers, and body that would be sent.",
                                                "http",
                                                "GET https://api.example.com/todos?limit=25\n"
                                                        + "Authorization: Bearer ****",
                                                "The resolved call, with secrets masked, before anything is sent."),
                                        example("Raw body for a write",
                                                "Form uses the imported schema; Raw body sends a verbatim payload with "
                                                        + "the content type you choose. Path, query, and header arguments "
                                                        + "stay available in raw-body mode.",
                                                "json",
                                                "{\n  \"title\": \"Call the vendor\",\n  \"done\": false\n}",
                                                "A preview of the write, still awaiting approval.")),
                                "The resolved request matches what you intended before anything was sent.",
                                "You can also build a request from scratch and save it as a manual tool; manual tools "
                                        + "can be edited and deleted like any other.",
                                List.of()),
                        step("Walk the approval path for a write",
                                "This is the application's core safety contract, and it is identical for the web UI and "
                                        + "for MCP clients: a write produces a preview and a short-lived, single-use "
                                        + "confirmation token, and executes only when a person approves that exact preview.",
                                List.of("Enable one write request on Apps.",
                                        "Call it from the query bar.",
                                        "Read the resolved URL, headers, and body in the preview.",
                                        "Approve it, and watch the token be consumed."),
                                "/", "Open Search",
                                List.of(
                                        example("The call that does not execute",
                                                "A write returns a preview instead of a result.",
                                                "search",
                                                "@todo-app #create_todo \"Call the vendor\"",
                                                "A preview plus a single-use confirmation token."),
                                        example("Approving, or discarding",
                                                "The confirm endpoint is what executes; reject discards the preview.",
                                                "http",
                                                "POST /api/tools/confirm/{token}   executes it\n"
                                                        + "POST /api/tools/reject/{token}    discards it",
                                                "Execution happens only on confirm, and only once per token.")),
                                "The write executes only after you approve it, and the token cannot be used twice.",
                                "Never reuse a token, and never treat an earlier approval as approval for changed "
                                        + "arguments. If the arguments change, the preview changes, and it needs its own "
                                        + "approval.",
                                List.of(fix("A write appears to do nothing",
                                        "It is waiting on confirmation. Approve the preview; tokens are single-use "
                                                + "and expire."))),
                        step("Mark a read as a knowledge source",
                                "A GET can feed the knowledge base as well as being callable. Its response is extracted "
                                        + "and ingested through the same pipeline as files and connectors.",
                                List.of("Open the request on Apps.",
                                        "Mark it as a knowledge source.",
                                        "Wait for ingestion, then search for content only that API returns."),
                                "/apps", "Open Apps",
                                List.of(example("What gets extracted",
                                        "Ingestion is broader than RQL: an insight reads JSON only, but a knowledge "
                                                + "source also extracts text from HTML and XML.",
                                        "text",
                                        "Ingestion    -> JSON, HTML, XML\n"
                                                + "RQL/Insights -> JSON only",
                                        "The API's content becomes searchable alongside your files.")),
                                "A plain question returns passages sourced from that API.",
                                null,
                                List.of())));
    }

    // ---------------------------------------------------------------------------------------
    // 5. First insight
    // ---------------------------------------------------------------------------------------

    private static Tutorial firstInsight() {
        return new Tutorial(
                "first-insight",
                "Build your first insight",
                "Import an API, run one of its requests, and turn the response into a saved view.",
                "A saved insight that reopens on its last result.",
                "About 15 minutes",
                "Intermediate",
                List.of("One imported API connection with at least one enabled GET request"),
                List.of("rql-queries", "api-tools"),
                List.of(
                        step("See what the API exposes",
                                "Typing an app on its own lists every request it exposes, so you can see what is "
                                        + "available before designing anything around it.",
                                List.of("Open Search.",
                                        "Type @ followed by the app name and press Enter.",
                                        "Note the display name of a GET request you want to use."),
                                "/", "Open Search",
                                List.of(example("Browse an app",
                                        "The display name is what RQL references, not the tool id.",
                                        "search",
                                        "@todo-app",
                                        "A table of the app's requests with their display names.")),
                                "You have the exact display name of a working GET request.",
                                "RQL references a request by its display name — \"List all posts\", not "
                                        + "todo_app_list_all_posts.",
                                List.of()),
                        step("Start from one request and a table",
                                "Begin with a single dataset and a DataTable, so you see the real field names before "
                                        + "designing anything on top of them.",
                                List.of("Open Insights and press New.",
                                        "Press Edit to show the document.",
                                        "Write one let with a limit, bind it to a DataTable.",
                                        "Press Run insight."),
                                "/insights", "Open Insights",
                                List.of(example("The smallest useful document",
                                        "An insight is Markdown with fenced rql blocks and components.",
                                        "rqd",
                                        """
                                        ```rql
                                        let records = request "List all posts" |> limit 25;
                                        ```

                                        <DataTable data={records} />""",
                                        "A table of up to 25 rows with the API's real field names.")),
                                "A table appears in the result panel with recognisable columns.",
                                "If the table shows one odd row, the API nests its rows under a key — use expand to "
                                        + "unwrap it.",
                                List.of(
                                        fix("RQL101: the request name does not exist",
                                                "The display name is misspelled, or belongs to another app. Browse the "
                                                        + "app with @app to copy the exact name."),
                                        fix("RQL102: the request is disabled",
                                                "Enable it on Apps. A disabled request returns no rows rather than an "
                                                        + "error, so the table renders empty."))),
                        step("Aggregate, then visualise",
                                "Charts need one row per category, which is exactly what group by produces. Add the "
                                        + "chart only once the aggregated table looks right.",
                                List.of("Add a second let that groups the first dataset.",
                                        "Bind it to a DataTable and check the numbers.",
                                        "Replace the table with a BarChart once they are right."),
                                "/insights", "Open Insights",
                                List.of(
                                        example("Aggregate first",
                                                "group by with an aggregate produces one row per category.",
                                                "rql",
                                                """
                                                let by_user = records
                                                  |> group by userId agg count(*) as posts
                                                  |> order by posts desc;""",
                                                "One row per user, ordered by post count."),
                                        example("Then chart it",
                                                "data, x, and y are required props.",
                                                "rqd",
                                                "<BarChart data={by_user} x=\"userId\" y=\"posts\" title=\"Posts per user\" />",
                                                "SVG bars with a collapsible \"Show chart data table\" twin.")),
                                "The chart's bars match the numbers in the aggregated table.",
                                "The axis charts the first 24 categories and says so when there are more; the data "
                                        + "table twin carries up to 100 rows.",
                                List.of(fix("RQI011 / RQI013: a colour prop was rejected",
                                        "Colour is semantic, never authored — true/false and request status colour "
                                                + "themselves. Add a second chart instead of a second axis or a palette."))),
                        step("Write the numbers out in words",
                                "Stat, Text, KeyValue, QuickTable, and LabelTable write summary blocks straight into "
                                        + "the document, and their values accept a small expression language including "
                                        + "conditionals.",
                                List.of("Add a KPI row of Stats above the chart.",
                                        "Add a sentence that computes itself.",
                                        "Add Status and Metrics to report on the run."),
                                "/insights", "Open Insights",
                                List.of(
                                        example("KPI row",
                                                "Consecutive Stats collapse into a row automatically.",
                                                "rqd",
                                                "<Stat value={count(records)} label=\"Posts\" />\n"
                                                        + "<Stat value={count(by_user)} label=\"Active users\" />",
                                                "Two large numbers side by side."),
                                        example("A computed sentence",
                                                "if/then/else, and/or, and parentheses all work in a value expression.",
                                                "rqd",
                                                "<Text value={if count(records) > 0 then count(records) + \" posts indexed\""
                                                        + " else \"Nothing returned\"} />",
                                                "A line of prose that changes with the data."),
                                        example("Report on the run itself",
                                                "Status lists one row per request issued; Metrics gives the aggregate.",
                                                "rqd",
                                                "<Status />\n<Metrics />",
                                                "Per-request status codes and durations, then the totals.")),
                                "The summary blocks agree with the chart and the table.",
                                "A prop a component does not read is reported (RQI014) rather than ignored, so a typo "
                                        + "like titel is visible instead of silently doing nothing.",
                                List.of()),
                        step("Parameterise it",
                                "Front-matter params become the input controls above the result and are readable in "
                                        + "RQL as $name. Defaults apply first, then whatever the controls hold.",
                                List.of("Add a params block to the front matter.",
                                        "Reference the parameter in a where clause.",
                                        "Run it, then change the control and run it again."),
                                "/insights", "Open Insights",
                                List.of(example("A parameterised document",
                                        "type: number renders a numeric input; anything else renders text.",
                                        "rqd",
                                        """
                                        ---
                                        title: API activity
                                        params:
                                          minUser: { type: number, default: 1 }
                                        ---

                                        ```rql
                                        let records = request "List all posts"
                                          |> where userId >= $minUser;
                                        ```""",
                                        "A numeric control above the result that re-runs the document.")),
                                "Changing the parameter changes the result without editing the document.",
                                "<Filter> is accepted but not rendered and is reported as inert (RQI311) — parameter "
                                        + "inputs come from front-matter params.",
                                List.of()),
                        step("Save it and come back",
                                "Saving keeps the document; running keeps the result. Reopening an insight shows the "
                                        + "last run rather than an empty panel.",
                                List.of("Give the insight a name and press Save.",
                                        "Press Run insight once more so the saved document has a stored result.",
                                        "Navigate away, then return to Insights."),
                                "/insights", "Open Insights",
                                List.of(),
                                "The insight reopens showing its last result, labelled with when it ran.",
                                "A restored result is a snapshot and can be old — it is labelled \"Saved result\" with "
                                        + "its run time, and says so when you edit the document afterwards.",
                                List.of())));
    }

    // ---------------------------------------------------------------------------------------
    // 6. RQL
    // ---------------------------------------------------------------------------------------

    private static Tutorial rqlQueries() {
        return new Tutorial(
                "rql-queries",
                "Query across apps with RQL",
                "Filter, aggregate, enrich, and compare imported read requests — including across two apps at once.",
                "A multi-source query whose provenance you can read off the rows.",
                "About 25 minutes",
                "Advanced",
                List.of("A working insight (see \"Build your first insight\")",
                        "Two imported apps if you want to follow the cross-app examples"),
                List.of("first-insight", "api-tools"),
                List.of(
                        step("Learn the shape of a program",
                                "A program is statements ending in ';'. set declares a parameter, let binds a dataset, "
                                        + "and a pipeline is a source followed by stages joined with '|>'. RQL queries "
                                        + "imported requests, never the knowledge base.",
                                List.of("Open Insights and press Edit.",
                                        "Write a set and a let.",
                                        "Add stages one at a time, running between each."),
                                "/insights", "Open Insights",
                                List.of(
                                        example("Statements and a pipeline",
                                                "Every statement ends in a semicolon; stages chain with |>.",
                                                "rql",
                                                """
                                                set minUser = 1;

                                                let posts = request "List all posts"
                                                  |> where userId >= $minUser
                                                  |> order by id desc
                                                  |> limit 25;""",
                                                "One dataset of at most 25 rows."),
                                        example("The stages available",
                                                "Each takes the previous stage's rows and returns rows.",
                                                "text",
                                                "where · select · order by · limit · offset · distinct\n"
                                                        + "group by · expand · rename · parse date · lookup · join",
                                                "A vocabulary you can compose in any order that makes sense.")),
                                "The document runs and the row count changes as you add stages.",
                                "Every query goes through the same executor and credentials as any other tool call: "
                                        + "enabled read requests only, no second credential path.",
                                List.of(fix("RQL104: the request writes data",
                                        "A write request cannot back a dataset. Query a GET and drive the write from "
                                                + "the query bar's approval path instead."))),
                        step("Reshape rows that do not arrive flat",
                                "expand unwraps a nested array into rows, rename fixes a field name, and select narrows "
                                        + "the columns. Reach for these before concluding an API is unusable.",
                                List.of("Run the raw request and look at the shape.",
                                        "Add expand if one row contains everything.",
                                        "Rename and select until the columns read well."),
                                "/insights", "Open Insights",
                                List.of(
                                        example("Unwrap a nested payload",
                                                "A response like { \"data\": [ ... ] } arrives as one row until expanded.",
                                                "rql",
                                                """
                                                let orders = request "List orders"
                                                  |> expand data
                                                  |> rename created_at as createdAt
                                                  |> select id, createdAt, total;""",
                                                "One row per order, with three readable columns."),
                                        example("Drop duplicates",
                                                "distinct after select is usually what you want.",
                                                "rql",
                                                "let customers = orders |> select customerId |> distinct;",
                                                "One row per customer id.")),
                                "The table shows one row per record rather than one row overall.",
                                null,
                                List.of()),
                        step("Aggregate and work with dates",
                                "group by with an aggregate is how you get one row per category. parse date makes a "
                                        + "text timestamp comparable, which is what date presets and ranges need.",
                                List.of("Group by a field with count(*), sum, avg, min, or max.",
                                        "Parse the date field before filtering on it.",
                                        "Filter with a preset, then check the row count."),
                                "/insights", "Open Insights",
                                List.of(
                                        example("Aggregate",
                                                "agg names the output column.",
                                                "rql",
                                                """
                                                let by_customer = orders
                                                  |> group by customerId agg count(*) as orders, sum(total) as revenue
                                                  |> order by revenue desc;""",
                                                "One row per customer with a count and a sum."),
                                        example("Dates",
                                                "parse date declares the format and timezone the source uses.",
                                                "rql",
                                                """
                                                let recent = orders
                                                  |> parse date createdAt format "yyyy-MM-dd'T'HH:mm:ssX" timezone "UTC"
                                                  |> where createdAt date_preset THIS_MONTH;""",
                                                "Only rows inside the current month.")),
                                "The aggregate row count equals the number of distinct categories.",
                                "Text matching is case-insensitive throughout, and conditions compare numerically "
                                        + "first, then case-insensitively — the same rule the value expressions use.",
                                List.of()),
                        step("Enrich a dataset with a second request",
                                "lookup runs a detail request once per row, and join matches one bound dataset against "
                                        + "another. lookup costs one HTTP call per row, so limit before you enrich.",
                                List.of("Limit the base dataset first.",
                                        "Add a lookup keyed on the field the detail request expects.",
                                        "Check the new columns arrived on every row."),
                                "/insights", "Open Insights",
                                List.of(
                                        example("Per-row detail",
                                                "One request per row of the incoming dataset.",
                                                "rql",
                                                """
                                                let detailed = orders
                                                  |> limit 50
                                                  |> lookup request "Get order detail" by id;""",
                                                "The original rows, widened with the detail request's fields."),
                                        example("Dataset to dataset",
                                                "join matches against a dataset already bound in the document.",
                                                "rql",
                                                "let enriched = orders |> join customers on customerId;",
                                                "Order rows carrying their customer's fields.")),
                                "Every row carries the enriched fields, and the run's request count matches the row count.",
                                "Execution never aborts: a failed request becomes an empty dataset and every other "
                                        + "dataset in the document still runs. Add <Status /> to see which ones failed.",
                                List.of(fix("RQL201: the request ran but returned an error status",
                                        "The call reached the API and was rejected. Open the request on Apps and test "
                                                + "it with the same arguments to see the body."))),
                        step("Compare two apps in one document",
                                "Requests resolve across every connected app, so one document can read from two "
                                        + "systems. Qualify a request as \"App: Request\" when the names collide, and use "
                                        + "the combinators to make the difference legible.",
                                List.of("Bind one dataset from each app.",
                                        "Use intersect, except, or diff to compare them.",
                                        "Read the provenance columns the combinators add."),
                                "/insights", "Open Insights",
                                List.of(
                                        example("Two apps, one document",
                                                "Qualification is only needed when two apps share a request name.",
                                                "rql",
                                                """
                                                let jira_open = request "Jira: Search issues";
                                                let tracker_open = request "Tracker: List open";""",
                                                "Two datasets from two connections."),
                                        example("What is in both, or only one",
                                                "intersect, except, and diff add _source and _in_<label> columns.",
                                                "rql",
                                                "let both = intersect [jira_open as \"jira\", tracker_open as \"tracker\"] on key;",
                                                "Rows present in both, tagged with where each came from."),
                                        example("A value matrix",
                                                "compare builds a matrix with a _count column.",
                                                "rql",
                                                "let matrix = compare [jira_open as \"jira\", tracker_open as \"tracker\"] on status;",
                                                "One row per status value with a count per source.")),
                                "The provenance columns let you say which system a row came from without guessing.",
                                "docs/query-language-reference.md carries the full grammar, every diagnostic code, and "
                                        + "the mapping from .filter report keywords to their RQL spelling.",
                                List.of())));
    }

    // ---------------------------------------------------------------------------------------
    // 7. MCP clients
    // ---------------------------------------------------------------------------------------

    private static Tutorial mcpClient() {
        return new Tutorial(
                "mcp-client",
                "Connect an MCP client",
                "Point Claude, ChatGPT, an IDE extension, or your own client at the server and use it safely.",
                "A client that reads the operating guide, searches for grounded context, and honours the approval contract.",
                "About 15 minutes",
                "Advanced",
                List.of("The server running on http://127.0.0.1:8080",
                        "An MCP-compatible client that supports Streamable HTTP"),
                List.of("api-tools", "query-bar"),
                List.of(
                        step("Point the client at the endpoint",
                                "The Streamable HTTP endpoint is local-only until the authentication phase ships — the "
                                        + "server binds 127.0.0.1 deliberately, and that guardrail is what makes running "
                                        + "without auth defensible.",
                                List.of("Start the server.",
                                        "Add the Streamable HTTP endpoint in your MCP client.",
                                        "Complete initialize and inspect the server's capabilities."),
                                "/help", "Back to Help",
                                List.of(
                                        example("The endpoint",
                                                "One URL; no auth header yet.",
                                                "text",
                                                "http://127.0.0.1:8080/mcp",
                                                "A client that connects and completes initialize."),
                                        example("Adding it from a CLI",
                                                "The equivalent of filling in a connection dialog.",
                                                "bash",
                                                "claude mcp add --transport http enterprise-mcp http://127.0.0.1:8080/mcp",
                                                "The server appears in the client's server list."),
                                        example("A client config file",
                                                "The same connection for clients configured by JSON.",
                                                "json",
                                                """
                                                {
                                                  "mcpServers": {
                                                    "enterprise-mcp": {
                                                      "type": "http",
                                                      "url": "http://127.0.0.1:8080/mcp"
                                                    }
                                                  }
                                                }""",
                                                "The same connection, declared rather than typed.")),
                                "initialize completes and the client lists the server's capabilities.",
                                "Do not expose this endpoint beyond a trusted local or internal network — there is no "
                                        + "authentication yet.",
                                List.of(fix("The client cannot connect",
                                        "The backend is not running, or it is bound elsewhere. Start it and confirm "
                                                + "http://127.0.0.1:8080 answers in a browser."))),
                        step("Read orientation before acting",
                                "After initialize, the client should read the two orientation resources. Both are "
                                        + "generated from the same catalog that powers this Help page, so a client and a "
                                        + "person see the same workflow and safety rules.",
                                List.of("Call resources/list.",
                                        "Read the operating guide and the playbook.",
                                        "Optionally get the orient-to-enterprise-mcp prompt."),
                                "/help", "Back to Help",
                                List.of(
                                        example("The resources",
                                                "Human-readable and structured versions of the same rules.",
                                                "text",
                                                "mcp://enterprise-mcp/guides/operating-guide\n"
                                                        + "mcp://enterprise-mcp/guides/llm-playbook.json",
                                                "Workflow, safety, and error-handling rules in the client's context."),
                                        example("The prompts",
                                                "Reusable starting instructions.",
                                                "text",
                                                "orient-to-enterprise-mcp   load the guide, establish the workflow\n"
                                                        + "execute-grounded-task      run one task under it",
                                                "A conversation that starts already oriented.")),
                                "The client has the operating guide in context before it calls anything.",
                                "Treat the guide as the workflow contract, not as a substitute for tool schemas — "
                                        + "tools/list stays authoritative for what exists and what it accepts.",
                                List.of()),
                        step("Search for grounded context",
                                "search-knowledge-base is the tool that makes an answer citable. It returns excerpts "
                                        + "with their source, not a generated answer, which is what lets the client "
                                        + "attribute rather than assert.",
                                List.of("Call tools/list to see the current surface.",
                                        "Call search-knowledge-base with a clear query and an appropriate topK.",
                                        "Have the client cite the source name or path it used."),
                                "/help", "Back to Help",
                                List.of(example("A grounded call",
                                        "topK is the number of passages, not the number of documents.",
                                        "json",
                                        """
                                        {
                                          "name": "search-knowledge-base",
                                          "arguments": {
                                            "query": "database credential rotation policy",
                                            "topK": 8
                                          }
                                        }""",
                                        "Excerpts with source name, path, and score.")),
                                "The client's answer names the sources it came from.",
                                "Treat an empty result as evidence the task is incomplete — the right move is to say "
                                        + "what is missing, not to fill the gap from memory.",
                                List.of(fix("The client sees a stale tool list",
                                        "Imported tools are enabled and disabled at runtime. Call tools/list again "
                                                + "rather than trusting a cached surface."))),
                        step("Honour the approval contract",
                                "A write tool returns a preview and a short-lived, single-use confirmation token — the "
                                        + "same contract as the web UI. The client must show the preview and get explicit, "
                                        + "current human approval before confirming.",
                                List.of("Have the client call a write tool.",
                                        "Read the preview it returns.",
                                        "Approve it, and have the client call confirm-action with that exact token."),
                                "/help", "Back to Help",
                                List.of(example("Confirming a write",
                                        "The token belongs to one preview and one approval.",
                                        "json",
                                        """
                                        {
                                          "name": "confirm-action",
                                          "arguments": { "token": "<token from that exact preview>" }
                                        }""",
                                        "The write executes once, and the token is spent.")),
                                "The write executes only after a person approved that specific preview.",
                                "Never infer approval from a previous request, reuse a token, or confirm a materially "
                                        + "changed action. This is the one rule the server cannot enforce for a client.",
                                List.of())));
    }

    // ---------------------------------------------------------------------------------------

    private static TutorialStep step(String title, String body, List<String> actions, String route,
                                     String routeLabel, List<TutorialExample> examples, String verify,
                                     String note, List<TutorialFix> troubleshooting) {
        return new TutorialStep(title, body, actions, route, routeLabel, examples, verify, note,
                troubleshooting);
    }

    private static TutorialExample example(String label, String description, String language, String code,
                                           String result) {
        return new TutorialExample(label, description, language, code, result);
    }

    private static TutorialFix fix(String symptom, String fix) {
        return new TutorialFix(symptom, fix);
    }
}
