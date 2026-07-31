package com.mcpserver.reports;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps stage validation and stage execution reading the same table.
 *
 * <p>Every rejected case below used to pass analysis clean and then fail at run time with RQL014,
 * because validation compared only a stage's first word while the executor matched the full
 * multi-word form — so {@code order} (no {@code by}) and bare {@code date}/{@code parse}, which
 * exist only as the openings of {@code date config} and {@code parse date}, were accepted. An
 * editor that reports clean and then breaks on run is the failure this grammar was designed against.
 */
class RqlStageFormTests {

    @ParameterizedTest
    @ValueSource(strings = {
            "order id",        // 'by' omitted
            "group id",        // 'by' omitted
            "date",            // opening of 'date config' only
            "date nonsense",
            "parse",           // opening of 'parse date' only
            "parse nonsense",
            "where",           // form needs an argument, given none
            "select",
            "join",
            "nonsense id",
    })
    void stagesThatCannotExecuteAreRejectedByAnalysis(String stage) {
        assertThat(ReportQueryService.isKnownStage(stage))
                .as("analysis must reject '%s' rather than defer the failure to execution", stage)
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "where id = 1", "having id = 1", "select id", "order by id", "group by id agg count(*) as n",
            "limit 10", "offset 5", "distinct", "distinct id", "expand items", "rename a to b",
            "parse date created", "date config created", "lookup request \"Detail\" on id",
            "join other on id = id",
    })
    void executableStagesStayAccepted(String stage) {
        assertThat(ReportQueryService.isKnownStage(stage)).as("'%s' must remain valid", stage).isTrue();
    }

    @Test
    void matchingIsCaseInsensitiveTheWayTheExecutorIs() {
        assertThat(ReportQueryService.isKnownStage("ORDER BY id")).isTrue();
        assertThat(ReportQueryService.isKnownStage("Group By id agg count(*) as n")).isTrue();
    }

    @Test
    void aStageMissingItsSecondWordSuggestsTheRealForm() {
        assertThat(ReportQueryService.suggestedStage("order id")).isEqualTo("order by");
        assertThat(ReportQueryService.suggestedStage("group id")).isEqualTo("group by");
        assertThat(ReportQueryService.suggestedStage("date whatever")).isEqualTo("date config");
        assertThat(ReportQueryService.suggestedStage("nonsense id")).isNull();
    }

    @Test
    void everyOfferedCompletionIsItselfAValidStageOpening() {
        // The editor must never complete something the validator would then reject.
        for (String snippet : ReportQueryService.stageSnippets()) {
            assertThat(ReportQueryService.isKnownStage(snippet + " x"))
                    .as("completion '%s' must lead to a valid stage", snippet).isTrue();
        }
    }
}
