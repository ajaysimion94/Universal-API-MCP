package com.mcpserver.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guards on the Insights workspace: the two-column default, the persisted run snapshot,
 * and the remembered last-opened insight.
 *
 * <p>Source assertions rather than behavioural ones because the browser application deliberately has
 * no Node toolchain (asserted by {@link StaticFrontendTests#browserApplicationHasNoNodeBuildDependency}),
 * so there is no JS runner to execute the module in. They pin the properties a well-meaning edit
 * could quietly undo.
 */
class InsightWorkspaceTests {

    private static final Path PAGE = Path.of("src/main/resources/static/pages/insights.js");
    private static final Path STYLES = Path.of("src/main/resources/static/components.css");

    private String page() throws IOException {
        return Files.readString(PAGE);
    }

    @Test
    void theEditorPanelIsOnlyRenderedBehindTheToggle() throws IOException {
        String page = page();
        assertThat(page).contains("data-action=\"toggle-editor\"");

        int workspace = page.indexOf("class=\"insight-workspace");
        int preview = page.indexOf("insight-preview-panel", workspace);
        assertThat(workspace).isGreaterThan(-1);
        assertThat(preview).isGreaterThan(workspace);
        assertThat(page.substring(workspace, preview))
                .as("the editor panel must sit behind a state.editing check, not always render")
                .contains("state.editing ? editorPanel()");
    }

    /**
     * The library list omits the run snapshot on purpose, so the open path has to fetch the full
     * row. An "optimisation" back to {@code state.saved.find(...)} would leave the preview
     * permanently empty and look like the feature had simply stopped working.
     */
    @Test
    void openingAnInsightFetchesTheFullRowSoTheStoredResultIsHydrated() throws IOException {
        String page = page();
        int open = page.indexOf("async function openInsight(");
        assertThat(open).as("openInsight present").isGreaterThan(-1);
        String body = page.substring(open, page.indexOf("\n  }", open));

        assertThat(body).contains("api.getInsight(");
        assertThat(body).contains("insight.lastRun");
        assertThat(body).doesNotContain("state.saved.find");
    }

    @Test
    void theLastOpenedInsightIsRememberedInBrowserStorage() throws IOException {
        String page = page();
        assertThat(page).contains("mcp.insights.workspace.v1")
                .contains("function loadStore(")
                .contains("function saveStore(");

        for (String function : new String[] { "function loadStore(", "function saveStore(" }) {
            int start = page.indexOf(function);
            String body = page.substring(start, page.indexOf("\n}", start));
            assertThat(body).as("%s must not let storage failure break the page", function)
                    .contains("catch");
        }
    }

    /** A restored snapshot can be arbitrarily old, so the preview always says when it ran. */
    @Test
    void theRenderedResultAlwaysStatesWhenItWasRun() throws IOException {
        String page = page();
        assertThat(page).contains("function previewStatus(")
                .contains("lastRunAt")
                .contains("Saved result")
                .contains("Document edited since this run");
    }

    /** Hiding the editor must not hide document errors, which live in its footer. */
    @Test
    void documentErrorsStaySurfacedWhileTheEditorIsHidden() throws IOException {
        assertThat(page()).contains("function hiddenDiagnosticsNote(");
    }

    @Test
    void bothWorkspaceLayoutsAreStyled() throws IOException {
        String styles = Files.readString(STYLES);
        int base = styles.indexOf(".insight-workspace {");
        int editing = styles.indexOf(".insight-workspace.is-editing {");
        assertThat(base).isGreaterThan(-1);
        assertThat(editing).isGreaterThan(-1);

        String baseRule = styles.substring(base, styles.indexOf("}", base));
        String editingRule = styles.substring(editing, styles.indexOf("}", editing));
        assertThat(countTracks(baseRule)).as("default layout is library + result").isEqualTo(2);
        assertThat(countTracks(editingRule)).as("editing layout adds the editor").isEqualTo(3);
    }

    /** Every breakpoint that collapses the workspace must cover the editing layout too. */
    @Test
    void everyWorkspaceBreakpointCollapsesTheEditingLayout() throws IOException {
        String styles = Files.readString(STYLES);
        for (String breakpoint : new String[] { "max-width: 1180px", "max-width: 980px" }) {
            int start = styles.indexOf("@media (" + breakpoint + ")");
            assertThat(start).as("%s block present", breakpoint).isGreaterThan(-1);
            String block = styles.substring(start, styles.indexOf("\n}", start));
            if (!block.contains(".insight-workspace")) continue;
            assertThat(block).as("%s must handle the editing layout", breakpoint)
                    .contains(".insight-workspace.is-editing");
            assertThat(block).as("%s must reset the desktop min-height when stacking", breakpoint)
                    .contains("min-height: 0");
        }
    }

    private static int countTracks(String rule) {
        return rule.split("minmax\\(", -1).length - 1;
    }
}
