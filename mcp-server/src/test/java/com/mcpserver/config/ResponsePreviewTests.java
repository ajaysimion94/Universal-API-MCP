package com.mcpserver.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guards on how the search page previews a tool response body.
 *
 * <p>These source assertions complement the React typecheck because the compatibility controller
 * still owns response rendering. They pin the properties that matter and that a well-meaning edit
 * could quietly undo.
 */
class ResponsePreviewTests {

    private static final Path SEARCH_PAGE = Path.of("src/main/resources/static/pages/search.js");

    private String source() throws IOException {
        return Files.readString(SEARCH_PAGE);
    }

    @Test
    void everyResponsePreviewBranchIsPresent() throws IOException {
        assertThat(source())
                .contains("function responsePreview(")
                .contains("function csvPreview(")
                .contains("function xmlPreview(")
                .contains("function htmlPreview(")
                .contains("function textPreview(");
    }

    /**
     * A response body must never go through the Markdown renderer. It joins consecutive lines into
     * one paragraph, which silently destroyed the structure of every line-oriented format — CSV rows
     * were merged onto a single line. Markdown stays correct for retrieval excerpts, which are prose.
     */
    @Test
    void theResponseBodyIsNeverRenderedAsMarkdown() throws IOException {
        String source = source();
        int start = source.indexOf("// ── non-JSON response previews");
        int end = source.indexOf("function confirmPanel(");
        assertThat(start).as("preview section present").isGreaterThan(-1);
        assertThat(end).as("confirmPanel marks the end of the section").isGreaterThan(start);

        assertThat(source.substring(start, end))
                .as("no preview branch may pass a response body through markdown()")
                .doesNotContain("markdown(");
    }

    /** Markdown is still the right renderer for retrieval excerpts, so it must not be removed. */
    @Test
    void retrievalExcerptsStillRenderAsMarkdown() throws IOException {
        assertThat(source()).contains("markdown(chunk.excerpt");
    }

    /**
     * A response body is untrusted third-party content: every preview branch escapes it, and none
     * renders it as live markup. Rendering an upstream API's HTML inside this page would execute
     * whatever that API chose to return.
     */
    @Test
    void everyNonJsonPreviewEscapesTheBody() throws IOException {
        String source = source();
        for (String function : new String[] { "function csvPreview(", "function xmlPreview(",
                "function htmlPreview(", "function textPreview(" }) {
            int start = source.indexOf(function);
            assertThat(start).as("%s present", function).isGreaterThan(-1);
            String body = source.substring(start, source.indexOf("\n}", start));
            assertThat(body).as("%s must escape what it renders", function).contains("escapeHtml(");
        }
    }

    @Test
    void htmlResponsesAreShownAsSourceAndSaidToBeUnrendered() throws IOException {
        String source = source();
        int start = source.indexOf("function htmlPreview(");
        String body = source.substring(start, source.indexOf("\n}", start));

        assertThat(body).contains("not rendered");
        assertThat(body).contains("escapeHtml(body)");
        // Script and style contents are stripped before the text extract, so a token embedded in a
        // script tag is not surfaced as if it were page copy.
        assertThat(body).contains("script|style");
    }
}
