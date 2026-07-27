package com.mcpserver.reports;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RqlParserTests {

    private final RqlParser parser = new RqlParser();

    @Test
    void recoversAfterABrokenStatementAndKeepsTheFollowingLet() {
        RqlModel.ParsedProgram result = parser.parse("""
                let = request "broken";
                let posts = request "List all posts" |> where userId >= $minUser;
                """);

        assertThat(result.diagnostics()).extracting(RqlModel.Diagnostic::code).contains("RQL002");
        assertThat(result.statements()).anyMatch(statement -> statement instanceof RqlModel.LetStatement let
                && let.name().equals("posts"));
    }

    @Test
    void keepsPipesAndSemicolonsInsideQuotedRequestNamesIntact() {
        RqlModel.ParsedProgram result = parser.parse("""
                let posts = request "Posts |> active; all" |> limit 5;
                """);

        assertThat(result.diagnostics()).isEmpty();
        RqlModel.LetStatement let = (RqlModel.LetStatement) result.statements().get(0);
        assertThat(let.pipeline()).contains("Posts |> active; all");
    }

    @Test
    void reportsAMissingTerminatorWithoutThrowing() {
        RqlModel.ParsedProgram result = parser.parse("let posts = request \"List all posts\"");

        assertThat(result.diagnostics()).extracting(RqlModel.Diagnostic::code).contains("RQL001");
        assertThat(result.statements()).hasSize(1);
    }
}
