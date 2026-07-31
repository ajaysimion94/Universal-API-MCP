package com.mcpserver.insights;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InsightDocumentParserTests {

    private final InsightDocumentParser parser = new InsightDocumentParser();

    @Test
    void parsesFrontmatterRqlAndSafeComponents() {
        InsightModel.Document document = parser.parse("""
                ---
                title: API Health
                connection: demo-api
                params:
                  minUser: { type: number, default: 2 }
                ---
                # API Health
                ```rql
                let posts = request "List posts" |> where userId >= $minUser;
                ```
                <Stat value={count(posts)} label="Posts" />
                <BarChart data={posts} x="userId" y="id" />
                """);

        assertThat(document.title()).isEqualTo("API Health");
        assertThat(document.connection()).isEqualTo("demo-api");
        assertThat(document.params()).singleElement().satisfies(param -> {
            assertThat(param.name()).isEqualTo("minUser");
            assertThat(param.type()).isEqualTo("number");
        });
        assertThat(document.rql()).contains("let posts");
        // The '# API Health' heading is prose, and prose is a rendered block in document order —
        // it used to be parsed into a field nothing read, so headings vanished from the page.
        assertThat(document.components()).extracting(InsightModel.Component::type)
                .containsExactly("Prose", "Stat", "BarChart");
        assertThat(document.components().get(0).props().get("value")).isEqualTo("# API Health");
        assertThat(document.diagnostics()).isEmpty();
    }

    @Test
    void rejectsUnsupportedVisualEscapes() {
        InsightModel.Document document = parser.parse("<BarChart data={rows} x=\"a\" y=\"b\" y2=\"c\" color=\"#fff\" />");

        assertThat(document.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .contains("RQI011", "RQI013");
    }
}
