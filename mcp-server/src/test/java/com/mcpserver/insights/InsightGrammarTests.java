package com.mcpserver.insights;

import com.mcpserver.reports.RqlModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the .rqd component surface against elements that parse but do nothing: props no component
 * reads, containment rules that could not previously be expressed, and prose that was parsed into a
 * field nothing ever read. Stage-form coverage lives in
 * {@link com.mcpserver.reports.RqlStageFormTests}, where those helpers are visible.
 */
class InsightGrammarTests {

    private final InsightDocumentParser parser = new InsightDocumentParser();

    // ── component props ──────────────────────────────────────────────────────────

    @Test
    void propsAComponentDoesNotReadAreReportedRatherThanSilentlyIgnored() {
        RqlModel.Diagnostic[] diagnostics = parser.parse(
                "<Stat value={count(a)} label=\"L\" delta=\"7d\" format=\"0.0\" />")
                .diagnostics().toArray(new RqlModel.Diagnostic[0]);

        assertThat(diagnostics).extracting(RqlModel.Diagnostic::code).contains("RQI014");
        assertThat(diagnostics).extracting(RqlModel.Diagnostic::message)
                .anySatisfy(message -> assertThat(message).contains("delta"))
                .anySatisfy(message -> assertThat(message).contains("format"));
    }

    @Test
    void aTypoedPropIsReported() {
        assertThat(parser.parse("<BarChart data={a} x=\"i\" y=\"v\" titel=\"typo\" />").diagnostics())
                .extracting(RqlModel.Diagnostic::code).contains("RQI014");
    }

    @Test
    void propsAComponentDoesReadStaySilent() {
        assertThat(parser.parse("<BarChart data={a} x=\"i\" y=\"v\" title=\"Fine\" />").diagnostics())
                .extracting(RqlModel.Diagnostic::code).doesNotContain("RQI014");
    }

    @Test
    void rejectedPropsAreReportedOnceUnderTheirOwnCodeNotAlsoAsUnknown() {
        List<String> codes = parser.parse("<BarChart data={a} x=\"i\" y=\"v\" y2=\"z\" color=\"#fff\" />")
                .diagnostics().stream().map(RqlModel.Diagnostic::code).toList();

        assertThat(codes).contains("RQI011", "RQI013").doesNotContain("RQI014");
    }

    // ── nesting ──────────────────────────────────────────────────────────────────

    @Test
    void aFilterNestedInAChartIsRejected() {
        assertThat(parser.parse("<BarChart data={a} x=\"i\" y=\"v\"><Filter param=\"w\" /></BarChart>")
                .diagnostics()).extracting(RqlModel.Diagnostic::code).contains("RQI012");
    }

    @Test
    void aFilterAtDocumentLevelIsNotANestingErrorButIsFlaggedAsInert() {
        List<String> codes = parser.parse("<Filter param=\"window\" />").diagnostics().stream()
                .map(RqlModel.Diagnostic::code).toList();

        assertThat(codes).contains("RQI311").doesNotContain("RQI012");
    }

    @Test
    void nestingIsTrackedWithoutDisturbingTheFlatComponentOrder() {
        // KpiRow stays a sibling entry so the renderer's consecutive-Stat grouping is unaffected.
        assertThat(parser.parse("<KpiRow><Stat value={count(a)} /></KpiRow>").components())
                .extracting(InsightModel.Component::type).containsExactly("KpiRow", "Stat");
    }

    @Test
    void aStrayClosingTagDoesNotUnwindTheNestingStack() {
        assertThat(parser.parse("</Nope><BarChart data={a} x=\"i\" y=\"v\" /><Filter param=\"w\" />")
                .diagnostics()).extracting(RqlModel.Diagnostic::code).doesNotContain("RQI012");
    }

    // ── prose ────────────────────────────────────────────────────────────────────

    @Test
    void proseIsEmittedAsOrderedBlocksBetweenComponents() {
        InsightModel.Document document = parser.parse("""
                ---
                title: T
                ---
                # Heading

                Intro sentence.

                ```rql
                let a = request "Rows";
                ```

                <Stat value={count(a)} label="Rows" />

                Closing note.
                """);

        assertThat(document.components()).extracting(InsightModel.Component::type)
                .containsExactly("Prose", "Stat", "Prose");
        assertThat(document.components().get(0).props().get("value"))
                .contains("# Heading").contains("Intro sentence.");
        assertThat(document.components().get(2).props().get("value")).isEqualTo("Closing note.");
    }

    @Test
    void proseExcludesFrontMatterRqlBlocksAndTagText() {
        InsightModel.Document document = parser.parse("""
                ---
                title: T
                ---
                Visible.
                ```rql
                let a = request "Rows";
                ```
                <KpiRow><Stat value={count(a)} /></KpiRow>
                """);

        String prose = document.components().stream()
                .filter(component -> component.type().equals("Prose"))
                .map(component -> component.props().get("value"))
                .reduce("", (left, right) -> left + "\n" + right);

        assertThat(prose).contains("Visible.");
        assertThat(prose).doesNotContain("title:").doesNotContain("request").doesNotContain("KpiRow");
    }

    @Test
    void aDocumentOfOnlyProseStillProducesARenderableBlock() {
        assertThat(parser.parse("Just a sentence.").components())
                .extracting(InsightModel.Component::type).containsExactly("Prose");
    }
}
