package com.mcpserver.guides;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Ordered, hands-on walkthroughs, kept beside {@link GuideCatalog} for the same reason that one
 * exists: instructions live on the server so they cannot drift from what the application actually
 * does, rather than being hardcoded in the SPA.
 *
 * <p>The distinction from {@code GuideCatalog} is shape, not subject. A guide article is reference —
 * browse a topic, read the rules. A tutorial is a sequence with an outcome: every step names the
 * route it happens on and how to tell it worked, so a reader can follow it with the application
 * open beside them. Tutorials are deliberately <em>not</em> projected to MCP clients the way guides
 * are ({@code McpGuideBridge}): they instruct a person driving the web UI, so an agent reading them
 * as operating rules would be misled.
 */
@Component
public class TutorialCatalog {

    public record TutorialSummary(String id, String title, String summary, String outcome,
                                  String duration, int steps) {
    }

    /**
     * One step. {@code route} is where the work happens, so the page can link straight to it;
     * {@code verify} is how the reader knows to move on, which is what keeps a walkthrough honest.
     */
    public record TutorialStep(String title, String body, List<String> actions, String route,
                               String routeLabel, String code, String verify, String note) {
    }

    public record Tutorial(String id, String title, String summary, String outcome, String duration,
                           List<TutorialStep> steps) {
    }

    private final List<Tutorial> tutorials = List.of(
            new Tutorial(
                    "first-answer",
                    "Ask your first grounded question",
                    "Turn an empty workspace into cited answers from your own documents.",
                    "A question answered from your files, with the passages it came from.",
                    "About 10 minutes",
                    List.of(
                            step("Turn on search",
                                    "Search needs a vector store and an embedding model. Both ship with the app and "
                                            + "install locally — nothing is downloaded from a third party at query time.",
                                    List.of("Open Plugins.",
                                            "Install and enable SQLite Vec Store.",
                                            "Install and enable Nomic Embedding."),
                                    "/plugins", "Open Plugins", null,
                                    "Both plugins report Ready.",
                                    "Until they are ready the app runs in a degraded mode: files still work, and search "
                                            + "returns a structured \"not ready\" response rather than failing."),
                            step("Add something to search",
                                    "Uploading a file starts extraction, chunking, and indexing in the background. PDFs, "
                                            + "Office documents, Markdown, and plain text are all handled.",
                                    List.of("Open Files.",
                                            "Create a folder, or pick an existing one.",
                                            "Upload a document you know the contents of."),
                                    "/files", "Open Files", null,
                                    "The file appears in the folder and ingestion finishes.",
                                    "Pick something you can check the answer against — verifying the first answer is the "
                                            + "point of this step."),
                            step("Ask, then check the evidence",
                                    "A plain question runs hybrid retrieval and returns passages with their source and "
                                            + "score. The server does not write the answer for you; it shows you what it found.",
                                    List.of("Open Search.",
                                            "Ask something only your document can answer.",
                                            "Expand the evidence and read the matched passages."),
                                    "/", "Open Search", null,
                                    "The cited passages actually support the answer.",
                                    "If the passages do not answer the question, narrow it or use exact wording from the "
                                            + "document — that is a retrieval signal, not a failure."))),
            new Tutorial(
                    "first-insight",
                    "Build your first insight",
                    "Import an API, run one of its requests, and turn the response into a saved view.",
                    "A saved insight that reopens on its last result.",
                    "About 15 minutes",
                    List.of(
                            step("Import an API",
                                    "A Postman collection or an OpenAPI document becomes a set of deterministic tools, one "
                                            + "per request. Read requests are enabled on import; anything that changes data "
                                            + "waits for you to approve it.",
                                    List.of("Open Connections.",
                                            "Import a Postman collection or OpenAPI/Swagger document.",
                                            "Wait for the connection to report Connected."),
                                    "/connections", "Open Connections", null,
                                    "The connection is Connected and its requests are listed.",
                                    "Only GET requests are enabled automatically. State-changing requests stay pending "
                                            + "until you approve them, and always run behind a preview."),
                            step("See what the API exposes",
                                    "Typing an app on its own lists every request it exposes as a table, so you can see "
                                            + "what is available before calling anything.",
                                    List.of("Open Search.",
                                            "Type @ followed by the app name and press Enter.",
                                            "Note the name of a GET request you want to use."),
                                    "/", "Open Search", "@my_app",
                                    "A table of the app's GET requests appears.", null),
                            step("Run one request",
                                    "The # grammar invokes a tool deterministically — no interpretation, no guessing at "
                                            + "arguments. Read requests run immediately and show the response.",
                                    List.of("Still in Search, type # and the request name.",
                                            "Run it and read the response preview."),
                                    "/", "Open Search", "@my_app #list_items",
                                    "The response renders as a table or formatted body.",
                                    "JSON becomes a table, CSV is parsed, XML and plain text keep their structure, and "
                                            + "HTML is shown as source rather than rendered."),
                            step("Write the insight",
                                    "An insight is Markdown with fenced RQL blocks and a small set of components. The "
                                            + "let binds a dataset; the components below render it.",
                                    List.of("Open Insights and press New.",
                                            "Press Edit to show the document.",
                                            "Replace the example with your own request name.",
                                            "Press Run insight."),
                                    "/insights", "Open Insights",
                                    "```rql\nlet items = request \"List items\";\n```\n\n<Stat value={count(items)} label=\"Items\" />\n<DataTable data={items} />",
                                    "Numbers and a table appear in the result panel.",
                                    "Diagnostics appear under the editor as you type. Press Edit if the document has "
                                            + "errors while the editor is hidden."),
                            step("Save it and come back",
                                    "Saving keeps the document; running keeps the result. Reopening an insight shows the "
                                            + "last run rather than an empty panel.",
                                    List.of("Give the insight a name and press Save.",
                                            "Press Run insight once more so the saved document has a stored result.",
                                            "Navigate away, then return to Insights."),
                                    "/insights", "Open Insights", null,
                                    "The insight reopens showing its last result, labelled with when it ran.",
                                    "A restored result is a snapshot and can be old — it is labelled \"Saved result\" with "
                                            + "its run time, and says so when you edit the document afterwards."))));

    public List<TutorialSummary> summaries() {
        return tutorials.stream()
                .map(tutorial -> new TutorialSummary(tutorial.id(), tutorial.title(), tutorial.summary(),
                        tutorial.outcome(), tutorial.duration(), tutorial.steps().size()))
                .toList();
    }

    public Optional<Tutorial> find(String id) {
        return tutorials.stream().filter(tutorial -> tutorial.id().equals(id)).findFirst();
    }

    private static TutorialStep step(String title, String body, List<String> actions, String route,
                                     String routeLabel, String code, String verify, String note) {
        return new TutorialStep(title, body, actions, route, routeLabel, code, verify, note);
    }
}
