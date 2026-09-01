package com.mcpserver.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guards on the Insights query studio: the two-pane workbench, the persisted run
 * snapshot, and the remembered last-opened insight.
 *
 * <p>Source assertions complement the React typecheck because this compatibility controller still
 * owns the detailed workspace behavior. They pin properties a well-meaning edit could quietly undo.
 */
class InsightWorkspaceTests {

    private static final Path PAGE = Path.of("src/main/resources/static/pages/insights.js");
    private static final Path STYLES = Path.of("src/main/resources/static/components.css");

    private String page() throws IOException {
        return Files.readString(PAGE);
    }

    @Test
    void theEditorPanelIsOnlyRenderedBehindTheSourceTab() throws IOException {
        String page = page();
        assertThat(page).doesNotContain("class=\"insight-mode-switch\"");

        assertThat(page)
                .as("the editor panel must sit behind the Source authoring tab, not always render")
                .contains("if (state.mode === \"view\")")
                .contains(": tab === \"source\"")
                .contains("? editorPanel()")
                .contains("role=\"tabpanel\"")
                .contains("state.authorTab = \"source\"");
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
    void authoringModeUpdatesStableRegionsInsteadOfRepaintingTheWholeWorkbench() throws IOException {
        String page = page();
        int start = page.indexOf("function render()");
        assertThat(start).as("render present").isGreaterThan(-1);
        String body = page.substring(start, page.indexOf("\n  async function analyze()", start));

        assertThat(body)
                .contains("if (state.mode === \"view\")")
                .contains("outlet.innerHTML = `<section class=\"insight-studio-page is-view\"")
                .contains("ensureAuthorShell()")
                .contains("syncRegion(\"masthead\"")
                .contains("syncRegion(\"build-panel\"")
                .contains("syncRegion(\"output-panel\"")
                .contains("syncRegion(\"status-bar\"");
        assertThat(page)
                .contains("function authorShell()")
                .contains("data-region=\"build-panel\"")
                .contains("data-region=\"output-panel\"");
    }

    @Test
    void authoringProgressivelyRevealsAdvancedToolsAndKeepsMobileEditingReachable() throws IOException {
        String page = page();
        String styles = Files.readString(STYLES);

        assertThat(page)
                .contains("Step 1 of 3")
                .contains("Add your first data source")
                .contains("function projectExplorerDrawer()")
                .contains("Dataset map")
                .contains("hasData ? visualToolbar() : \"\"")
                .contains("Build a visual")
                .contains("data-status-key=\"${escapeAttr(segment.key)}\"");
        assertThat(styles)
                .contains("content-visibility: auto")
                .contains(".insight-studio-page.is-design {")
                .contains("overflow-y: auto")
                .contains("grid-template-rows: auto minmax(18rem, 46vh)")
                .contains(".insight-studio-source select")
                .contains(".insight-studio-visual-workspace > summary")
                .contains("min-height: 44px")
                .contains("data-status-key=\"freshness\"");
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
    void saveStateIsIndependentFromRunFreshnessAndDiscardingEditsRequiresConsent() throws IOException {
        String page = page();

        assertThat(page)
                .contains("function hasUnsavedChanges(state)")
                .contains("state.savedSource")
                .contains("state.savedName")
                .contains("state.savedConnectionId")
                .contains("Start a new insight? Unsaved changes")
                .contains("Open \"${next?.name || \"this insight\"}\"? Unsaved changes")
                .contains("Replace this draft with")
                .contains("Save insight")
                .contains("icon(\"save\", 15)")
                .contains("data-action=\"export-insight\"")
                .contains("icon(\"download\", 15)")
                .doesNotContain("Build starter")
                .doesNotContain("Export CSV");
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
    void selectedCanvasBlocksCanBeResizedAndDeletedInPlace() throws IOException {
        String page = page();
        String styles = Files.readString(STYLES);

        assertThat(page)
                .contains("function confirmDeleteComponent(")
                .contains("Delete this ${label.toLowerCase()} from the dashboard?")
                .contains("data-action=\"canvas-delete-component\"")
                .contains("data-resize-handle")
                .contains("let resizeDrag = null")
                .contains("setSelectedGridLayout({")
                .contains("state.selected = next.offset")
                .contains("component.type !== \"Filter\" && component.type !== \"KpiRow\"")
                .doesNotContain("component.type !== \"Prose\" && component.type !== \"Filter\"")
                .doesNotContain("data-action=\"canvas-resize-component\"")
                .doesNotContain("insight-canvas-size");
        assertThat(styles)
                .contains(".insight-canvas-controls")
                .contains(".insight-canvas-resize-handle")
                .contains("body.is-resizing-insight")
                .contains(".insight-rendered.is-design .insight-grid-item.is-selected");
    }

    @Test
    void dashboardGridPlacementPreventsOverlappingBlocks() throws IOException {
        String page = page();

        assertThat(page)
                .contains("function gridLayoutsOverlap(")
                .contains("function gridLayoutCollides(")
                .contains("function resolveGridCollision(")
                .contains("function compactGridRows(")
                .contains("while (next.y > 0)")
                .contains("function resolvedComponentGridLayouts(")
                .contains("function nonOverlappingResize(")
                .contains("function nonOverlappingMove(")
                .contains("function occupiedGridLayouts(")
                .contains("const layout = resolveGridCollision(rawLayout, occupied, grid)")
                .contains("const blocks = compactGridRows(renderItems, grid)")
                .contains("applyGridLayouts(entries)")
                .contains("occupied.push(layout)")
                .contains("occupied: resolvedLayouts")
                .contains("nonOverlappingResize(")
                .contains("nonOverlappingMove(")
                .contains("resolveGridCollision(nextAutoGridSlot(cursor, grid, { type, props: {} }), occupied, grid)");
    }

    @Test
    void selectedCanvasBlocksCanMoveFreelyWhileSnappingToTheGrid() throws IOException {
        String page = page();
        String styles = Files.readString(STYLES);

        assertThat(page)
                .contains("let moveDrag = null")
                .contains("let suppressCanvasClick = false")
                .contains("event.target.closest(\".insight-grid-item.is-selected\")")
                .contains("const resolvedLayouts = resolvedComponentGridLayouts(state.outline || [], grid)")
                .contains("const layout = resolvedEntry?.layout || componentGridLayout(component, grid, fallback)")
                .contains("Math.round((event.clientX - moveDrag.startX) / moveDrag.columnTrack)")
                .contains("moveDrag.item.style.setProperty(\"--grid-x\", String(next.x + 1))")
                .contains("moveDrag.item.style.setProperty(\"--grid-y\", String(next.y + 1))")
                .contains("gridX: next.lastX")
                .contains("gridY: next.lastY")
                .contains("if (next.moved) suppressCanvasClick = true");
        assertThat(styles)
                .contains(".insight-rendered.is-design .insight-grid-item.is-selected")
                .contains("cursor: grab")
                .contains("body.is-moving-insight");
    }

    @Test
    void deletingTheActiveInsightResetsItsCanvasBeforeAnalyzingTheNewDraft() throws IOException {
        String page = page();
        int reset = page.indexOf("function resetDraft(");
        assertThat(reset).isGreaterThan(-1);
        String resetBody = page.substring(reset, page.indexOf("\n  }", reset));
        assertThat(resetBody).contains("state.analysis = null")
                .contains("state.outline = []")
                .contains("state.selected = null");

        int delete = page.indexOf("} else if (action === \"delete-insight\")");
        assertThat(delete).isGreaterThan(-1);
        String body = page.substring(delete, page.indexOf("} else if (action === \"show-diagnostics\")", delete));
        assertThat(body).contains("resetDraft();")
                .contains("if (!state.activeId) analyze();");
    }

    @Test
    void aNewDraftStartsCleanAndCanBeBuiltFromAnActualReadRequest() throws IOException {
        String page = page();

        assertThat(page)
                .contains("const EMPTY_INSIGHT")
                .contains("function requestBindings(")
                .contains("function relationshipBindings(")
                .contains("function allDatasetNames(")
                .contains("function nextDatasetName(")
                .contains("function renameRequestSource(")
                .contains("function removeRequestSource(")
                .contains("function addDatasetTable(")
                .contains("function addRelationship(")
                .contains("function editRelationship(")
                .contains("function removeRelationship(")
                .contains("removeRelationshipBindingSource")
                .contains("removeDatasetTables")
                .contains("api.listTools()")
                .contains("tool.method === \"GET\"")
                .contains("tool.enabled && !tool.pending")
                .contains("function requestStarter(")
                .contains("withGridLayoutProps(\"<DataTable data={rows} />\"")
                .contains("function addRequestSource(")
                .contains("data-action=\"add-request-source\"")
                .contains("data-action=\"shape-request-source\"")
                .contains("data-action=\"add-dataset-table\"")
                .contains("data-action=\"remove-request-source\"")
                .contains("data-action=\"add-relationship\"")
                .contains("data-action=\"edit-relationship\"")
                .contains("data-action=\"remove-relationship\"")
                .contains("data-request-name=")
                .contains("data-relationship=\"left\"")
                .contains("grid:")
                .contains("gridX: 0")
                .contains("gridW: 8")
                .doesNotContain("List all posts");
    }

    @Test
    void aNewInsightExplainsTheRequestBlocksToDashboardWorkflow() throws IOException {
        String page = page();
        String styles = Files.readString(STYLES);

        assertThat(page)
                .contains("Dashboard IDE")
                .contains("const AUTHOR_TABS = [\"compose\", \"explorer\", \"api\", \"source\"]")
                .contains("explorer: \"Explorer\"")
                .contains("class=\"insight-studio-heading-copy\"")
                .contains("class=\"insight-title-field\"")
                .contains("Dashboard inputs")
                .contains("Dataset designer")
                .contains("Project Explorer")
                .contains("Request datasets")
                .contains("Relationships")
                .contains("Visual bindings")
                .contains("Shape selected dataset")
                .contains("Filter")
                .contains("Summarize")
                .contains("Model relationships")
                .contains("Selected visual")
                .contains("Step 1 of 3")
                .contains("Add your first data source")
                .contains("function projectExplorerDrawer()")
                .contains("projectExplorer({ openCompose: true })")
                .contains("data-open-compose=\"true\"")
                .contains("Dataset map")
                .contains("Build a visual")
                .contains("function authorTabs(")
                .contains("function apiTesterPanel(")
                .contains("Request test")
                .contains("Request to test")
                .contains("Add as input")
                .contains("id=\"insight-api-test-form\"")
                .contains("data-action=\"set-author-tab\"")
                .contains("aria-label=\"Dashboard editor sections\"")
                .contains("aria-controls=\"${authorPanelId(tab)}\"")
                .contains("role=\"tabpanel\"")
                .contains("aria-labelledby=\"${authorTabId(tab)}\"")
                .contains("event.key === \"Home\"")
                .contains("event.key === \"End\"")
                .contains("data-action=\"add-api-test-source\"")
                .contains("api.invokeTool(tool.id, toolArguments(tool, draft.values))")
                .contains("function insightListPanel(")
                .contains("title=\"Delete insight\"")
                .contains("class=\"insight-studio-workbench is-view\"")
                .contains("class=\"insight-studio-page is-${state.mode}\"")
                .contains("data-action=\"edit-insight\"")
                .contains("Add collection request")
                .contains("Add first input")
                .contains("Add input")
                .contains("Add this request as another input")
                .contains("function sourceLabelParts(")
                .contains("function projectExplorer(")
                .contains("function dashboardGridSettings(")
                .contains("function writeDashboardGridSettings(")
                .contains("function setGridSettings(")
                .contains("data-grid-setting=\"columns\"")
                .contains("data-grid-layout=\"gridW\"")
                .contains("data-action=\"grid-auto-arrange\"")
                .contains("optgroup label=")
                .doesNotContain("'<li class=\"is-empty\"><span>${icon(\"globe\", 12)}")
                .doesNotContain("'<li class=\"is-empty\"><span>${icon(\"file\", 12)}")
                .doesNotContain("insight-studio-source-name")
                .doesNotContain("insight-studio-source-title");
        assertThat(styles)
                .contains(".insight-studio-heading-copy")
                .contains(".insight-title-field")
                .contains("margin-left: auto")
                .contains(".insight-title-field { width: 100%; margin-left: 0; }")
                .contains(".insight-project-explorer")
                .contains(".insight-project-tree")
                .contains(".insight-author-tabs")
                .contains("grid-template-columns: repeat(auto-fit, minmax(96px, 1fr))")
                .contains("grid-template-columns: repeat(auto-fit, minmax(86px, 1fr))")
                .contains("grid-auto-flow: row")
                .contains(".insight-author-panel")
                .contains(".insight-api-tester")
                .contains(".insight-api-result")
                .contains(".insight-dashboard-grid")
                .contains(".insight-studio-workbench.is-view")
                .contains("grid-template-columns: minmax(0, 1fr) minmax(0, 5fr)")
                .contains(".insight-grid-toolbar")
                .contains(".insight-grid-layout-controls")
                .doesNotContain(".insight-studio-source-title");
    }

    @Test
    void theVisualQueryIdeGeneratesTheExistingRqlPipeline() throws IOException {
        String page = page();

        assertThat(page)
                .contains("function parseVisualQuery(")
                .contains("function applyVisualQuery(")
                .contains("function visualQueryPipeline(")
                .contains("function datasetStatement(")
                .contains("queryDataset: \"rows\"")
                .contains("function syncQueryDataset(")
                .contains("function setQueryDataset(")
                .contains("id=\"insight-query-dataset\"")
                .contains("joined to")
                .contains("visualStages")
                .contains("data-action=\"toggle-query-column\"")
                .contains("data-action=\"add-query-condition\"")
                .contains("data-query=\"groupField\"")
                .contains("data-query=\"sortField\"")
                .contains("data-query=\"limit\"")
                .contains("Generated plan")
                .contains("Preview")
                .contains("SELECT${query.distinct")
                .contains("stages.push(`where")
                .contains("stages.push(`select")
                .contains("stages.push(`group by")
                .contains("stages.push(`order by")
                .contains("stages.push(`limit");
    }

    @Test
    void codeModeOffersKeyboardAccessibleRqlCompletions() throws IOException {
        String page = page();
        String styles = Files.readString(STYLES);

        assertThat(page)
                .contains("cursorOffset: state.cursorOffset")
                .contains("function acceptCompletion(")
                .contains("function updateCompletionCursor(")
                .contains("data-action=\"accept-completion\"")
                .contains("aria-autocomplete")
                .contains("event.key === \"Tab\"")
                .contains("event.key === \"Escape\"")
                .contains("event.ctrlKey || event.metaKey")
                .doesNotContain("completionItems().slice(0, 8)");
        assertThat(styles)
                .contains(".insight-completions {")
                .contains("max-height: min(22rem")
                .contains("overflow-y: auto")
                .contains(".insight-completion {");
    }

    @Test
    void everyDraftOffersAVisibleNewInsightAction() throws IOException {
        String page = page();
        int masthead = page.indexOf("function mastheadContent(");
        int shell = page.indexOf("function authorShell()", masthead);
        assertThat(masthead).isGreaterThan(-1);
        assertThat(shell).isGreaterThan(masthead);
        assertThat(page.substring(masthead, shell))
                .contains("data-action=\"new-insight\"")
                .contains("Create");
        assertThat(page.substring(shell))
                .contains("class=\"insight-studio-build\"")
                .contains("data-region=\"build-panel\"");
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
        int page = styles.indexOf(".insight-studio-page {");
        int workbench = styles.indexOf(".insight-studio-workbench {");
        int output = styles.indexOf(".insight-studio-output {\n  display: grid;");
        assertThat(page).isGreaterThan(-1);
        assertThat(workbench).isGreaterThan(page);
        assertThat(output).isGreaterThan(workbench);

        String workbenchRule = styles.substring(workbench, styles.indexOf("}", workbench));
        String outputRule = styles.substring(output, styles.indexOf("}", output));
        assertThat(countTracks(workbenchRule))
                .as("the studio starts as query authoring beside live output").isEqualTo(2);
        assertThat(outputRule)
                .as("the output panel reserves the body for the result canvas")
                .contains("grid-template-rows: auto minmax(0, 1fr)");
        assertThat(styles)
                .as("design mode keeps query/properties on the left and an auto-sized dashboard preview on the right")
                .contains(".insight-studio-workbench.is-design")
                .contains("grid-template-columns: minmax(0, 2fr) minmax(0, 4fr)")
                .contains("gap: var(--space-sm)")
                .contains(".insight-studio-page.is-design")
                .contains(".insight-studio-page.is-design:has(> [data-region=\"hidden-diagnostics\"]:not(:empty))")
                .contains("grid-template-rows: auto auto minmax(0, 1fr) auto")
                .contains(".insight-studio-page > [data-region=\"hidden-diagnostics\"]:empty")
                .contains("display: none")
                .contains(".insight-studio-workbench.is-design .insight-studio-build")
                .contains(".insight-studio-workbench.is-design .insight-studio-output")
                .contains("overflow-y: auto")
                .contains("position: sticky")
                .contains(".insight-studio-output.is-design { grid-template-rows: auto minmax(0, 1fr); }")
                .contains(".insight-dashboard-device")
                .contains("grid-row: 4")
                .contains(".insight-dashboard-device-screen > .insight-rendered")
                .contains("min-height: 100%")
                .contains("height: 100%")
                .contains("padding: 0")
                .contains(".insight-dashboard-grid")
                .contains(".insight-dashboard-grid > .insight-preview-empty")
                .contains("grid-column: 1 / -1")
                .contains("justify-content: center")
                .contains(".insight-dashboard-device-screen > .insight-rendered.is-design .insight-dashboard-grid")
                .contains("margin: var(--space-sm)")
                .contains("flex: 1 1 auto")
                .doesNotContain("aspect-ratio: 4 / 3")
                .doesNotContain("grid-template-columns: minmax(360px, 0.92fr) minmax(500px, 1.08fr)")
                .doesNotContain("grid-template-columns: minmax(350px, 0.96fr) minmax(430px, 1.04fr)")
                .contains(".insight-studio-build .insight-studio-visual-toolbar")
                .contains(".insight-studio-visual-toolbar {")
                .contains(".insight-query-dataset {")
                .contains(".insight-source-block.is-shaping")
                .contains(".insight-source-block {")
                .contains(".insight-author-tabs")
                .contains(".insight-api-tester")
                .contains(".insight-relationship-form {")
                .contains(".insight-relationship-preview {")
                .contains(".insight-relationship-card.is-shaping")
                .contains(".insight-relationship-actions");
    }

    /** Design keeps the canvas adjacent to controls without pinning fixed minimum panel widths. */
    @Test
    void theQueryStudioKeepsDesignSideBySideAtTheCompactBreakpoint() throws IOException {
        String styles = Files.readString(STYLES);
        int start = styles.indexOf("@media (max-width: 1180px)");
        assertThat(start).as("compact breakpoint present").isGreaterThan(-1);
        String block = styles.substring(start, styles.indexOf("\n}", start));

        assertThat(block).contains(".insight-studio-workbench")
                .contains(".insight-studio-workbench:not(.is-view):not(.is-design)")
                .contains(".insight-studio-workbench.is-design")
                .contains("minmax(0, 2fr) minmax(0, 4fr)")
                .contains(".insight-studio-workbench:not(.is-design) .insight-studio-build")
                .contains(".insight-studio-workbench:not(.is-design) .insight-studio-output");
    }

    private static int countTracks(String rule) {
        return rule.split("minmax\\(", -1).length - 1;
    }
}
