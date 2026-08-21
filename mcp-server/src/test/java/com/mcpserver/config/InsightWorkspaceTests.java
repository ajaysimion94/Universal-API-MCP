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
    void theEditorPanelIsOnlyRenderedInCodeMode() throws IOException {
        String page = page();
        assertThat(page).contains("data-action=\"set-mode\"");

        int workspace = page.indexOf("class=\"insight-workspace");
        int preview = page.indexOf("insight-preview-panel", workspace);
        assertThat(workspace).isGreaterThan(-1);
        assertThat(preview).isGreaterThan(workspace);
        assertThat(page.substring(workspace, preview))
                .as("the editor panel must sit behind a mode check, not always render")
                .contains("state.mode === \"code\" ? editorPanel()");
    }

    /**
     * The design panes edit the document, never a parallel model of the dashboard. If a gesture ever
     * wrote to something other than {@code state.source}, Design and Code would drift apart and the
     * .rqd file would stop being the thing that was built — see DECISIONS.md (2026-08-07).
     */
    @Test
    void everyDesignGestureWritesBackIntoTheDocument() throws IOException {
        String page = page();
        assertThat(page).contains("function spliceSource(")
                .contains("function setTagProp(")
                .contains("function editComponent(");

        int splice = page.indexOf("function spliceSource(");
        String body = page.substring(splice, page.indexOf("\n  }", splice));
        assertThat(body).as("the splice must rewrite the source text").contains("state.source =");
        assertThat(body).as("spans after the edit must be shifted, or the next edit lands astray")
                .contains("startOffset + delta");
    }

    /**
     * The status bar's rules must stay in one pure function. Inlining any of them into the template
     * would put "what state is this insight in" in two places, and the bar is only worth having while
     * it is right — a segment reporting "Saved" over unsaved edits is worse than no segment.
     */
    @Test
    void theStatusBarDerivesItsValuesAndTonesInOnePureFunction() throws IOException {
        String page = page();
        int start = page.indexOf("function statusSegments(state)");
        assertThat(start).as("statusSegments present").isGreaterThan(-1);
        String body = page.substring(start, page.indexOf("\n}", start));

        assertThat(body).as("it must read state only, never the DOM or the network")
                .doesNotContain("document.").doesNotContain("outlet").doesNotContain("await");
        assertThat(body).as("every segment carries a tone").contains("tone:");
        for (String key : new String[] { "run", "freshness", "data", "requests", "diagnostics",
                "document", "app", "params", "selection" }) {
            assertThat(body).as("segment %s is derived", key).contains("key: \"" + key + "\"");
        }
        assertThat(page.substring(page.indexOf("function statusBar()")))
                .as("the renderer must not re-derive state, only lay the segments out")
                .startsWith("function statusBar()")
                .contains("statusSegments(state)");
    }

    /**
     * A run's request count is unknowable up front — one `lookup` stage issues a request per row — so
     * the bar must stay indeterminate. An aria-valuenow here would be a fabricated claim of progress.
     */
    @Test
    void theRunProgressBarDoesNotClaimAPositionItCannotKnow() throws IOException {
        String page = page();
        int start = page.indexOf("function runProgress()");
        assertThat(start).as("runProgress present").isGreaterThan(-1);
        String body = page.substring(start, page.indexOf("\n  }", start));

        assertThat(body).contains("role=\"progressbar\"");
        assertThat(body).as("indeterminate: no value may be asserted").doesNotContain("aria-valuenow");
        assertThat(body).as("elapsed time is the honest live signal").contains("data-run-elapsed");
    }

    /** Ticking the clock through render() would drop focus and the caret ten times a second. */
    @Test
    void theElapsedClockIsPaintedWithoutRerenderingThePage() throws IOException {
        String page = page();
        int start = page.indexOf("function tickElapsed()");
        assertThat(start).as("tickElapsed present").isGreaterThan(-1);
        String body = page.substring(start, page.indexOf("\n  }", start));

        assertThat(body).contains("textContent");
        assertThat(body).as("must not repaint the page on every tick").doesNotContain("render()");
        assertThat(page).as("the interval must be cleared when the route unmounts")
                .contains("clearInterval(elapsedTimer)");
    }

    @Test
    void staleAsyncResponsesCannotOverwriteNewerWorkspaceState() throws IOException {
        String page = page();
        assertThat(page).contains("let analysisSequence = 0;")
                .contains("let runSequence = 0;")
                .contains("function invalidateAnalysis()")
                .contains("if (sequence !== analysisSequence) return;")
                .contains("if (sequence !== runSequence) return;");
    }

    @Test
    void resultFreshnessTracksDraftEditsAsWellAsSavedDocuments() throws IOException {
        String page = page();
        assertThat(page).contains("changeRevision")
                .contains("runRevision")
                .contains("state.changeRevision += 1")
                .contains("state.changeRevision !== state.runRevision");
    }

    @Test
    void chartProjectionDoesNotTurnMissingValuesIntoZeroes() throws IOException {
        assertThat(page()).contains("Number(null)")
                .contains("typeof raw === \"boolean\"")
                .contains("typeof raw !== \"number\" && typeof raw !== \"string\"");
    }

    @Test
    void componentMovesDoNotCrossContainersOrProse() throws IOException {
        assertThat(page()).contains("item.type !== \"KpiRow\"")
                .contains("if (between.trim()) return;");
    }

    @Test
    void deletingTheActiveInsightResetsItsCanvasBeforeAnalyzingTheNewDraft() throws IOException {
        String page = page();
        int delete = page.indexOf("} else if (action === \"delete-insight\")");
        assertThat(delete).isGreaterThan(-1);
        String body = page.substring(delete, page.indexOf("} else if (action === \"show-diagnostics\")", delete));
        assertThat(body).contains("state.analysis = null")
                .contains("state.outline = []")
                .contains("state.selected = null")
                .contains("if (!state.activeId) analyze();");
    }

    /** Selection survives a re-analysis only if it is keyed by source offset, not by list index. */
    @Test
    void canvasSelectionIsKeyedBySourceOffset() throws IOException {
        String page = page();
        assertThat(page).contains("function componentKey(")
                .contains("component.span?.startOffset");

        int render = page.indexOf("function renderInsight(");
        String body = page.substring(render, page.indexOf("\n}", render));
        assertThat(body).as("blocks must carry the offset, not an array index")
                .contains("data-offset=\"${componentKey(component)}\"");
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
    void everyWorkspaceLayoutIsStyled() throws IOException {
        String styles = Files.readString(STYLES);
        int base = styles.indexOf(".insight-workspace {");
        int editing = styles.indexOf(".insight-workspace.is-editing {");
        // Anchored to the start of a line so this finds the top-level rule rather than the indented
        // collapse of the same selector inside a breakpoint.
        int design = styles.indexOf("\n.insight-workspace.is-design {");
        assertThat(base).isGreaterThan(-1);
        assertThat(editing).isGreaterThan(-1);
        assertThat(design).isGreaterThan(-1);

        assertThat(countTracks(styles.substring(base, styles.indexOf("}", base))))
                .as("default layout is library + result").isEqualTo(2);
        assertThat(countTracks(styles.substring(editing, styles.indexOf("}", editing))))
                .as("editing layout adds the editor").isEqualTo(3);
        assertThat(styles.substring(design, styles.indexOf("}", design)))
                .as("design layout is library + canvas + a fixed-width rail")
                .containsPattern("grid-template-columns:.*minmax.*minmax.*\\d+px");
    }

    /** Every breakpoint that collapses the workspace must cover the editing and design layouts too. */
    @Test
    void everyWorkspaceBreakpointCollapsesEveryLayout() throws IOException {
        String styles = Files.readString(STYLES);
        for (String breakpoint : new String[] { "max-width: 1180px", "max-width: 980px" }) {
            int start = styles.indexOf("@media (" + breakpoint + ")");
            assertThat(start).as("%s block present", breakpoint).isGreaterThan(-1);
            String block = styles.substring(start, styles.indexOf("\n}", start));
            if (!block.contains(".insight-workspace")) continue;
            assertThat(block).as("%s must handle the editing layout", breakpoint)
                    .contains(".insight-workspace.is-editing");
            assertThat(block).as("%s must handle the design layout", breakpoint)
                    .contains(".insight-workspace.is-design");
            assertThat(block).as("%s must reset the desktop min-height when stacking", breakpoint)
                    .contains("min-height: 0");
        }
    }

    private static int countTracks(String rule) {
        return rule.split("minmax\\(", -1).length - 1;
    }
}
