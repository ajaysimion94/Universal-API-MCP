package com.mcpserver.dashboards;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardDocumentParserTests {

    private final DashboardDocumentParser parser = new DashboardDocumentParser();

    @Test
    void parsesFrontmatterRqlAndSafeComponents() {
        DashboardModel.Document document = parser.parse("""
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
        assertThat(document.components()).extracting(DashboardModel.Component::type)
                .containsExactly("Stat", "BarChart");
        assertThat(document.diagnostics()).isEmpty();
    }

    @Test
    void rejectsUnsupportedVisualEscapes() {
        DashboardModel.Document document = parser.parse("<BarChart data={rows} x=\"a\" y=\"b\" y2=\"c\" color=\"#fff\" />");

        assertThat(document.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .contains("RQD011", "RQD013");
    }
}
