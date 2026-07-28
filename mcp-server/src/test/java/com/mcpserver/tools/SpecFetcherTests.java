package com.mcpserver.tools;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Offline behavior only (parseContent + YAML detection); URL resolution is covered by WireMock connector tests. */
class SpecFetcherTests {

    private final SpecFetcher fetcher =
            new SpecFetcher(List.of(new PostmanCollectionParser(), new OpenApiParser()));

    @Test
    void parseContentHandlesYamlSpecs() throws IOException {
        String yaml = fixture("/specs/petstore-openapi.yaml");
        SpecFetcher.FetchedSpec spec = fetcher.parseContent(yaml, null);
        assertThat(spec.parser().format()).isEqualTo("OPENAPI");
        assertThat(spec.parser().parse(spec.parsed())).hasSize(1);
    }

    @Test
    void parseContentHandlesPostmanJson() throws IOException {
        String json = fixture("/specs/postman-todo.json");
        assertThat(fetcher.parseContent(json, null).parser().format()).isEqualTo("POSTMAN");
    }

    @Test
    void parseContentHandlesSwagger2Json() throws IOException {
        String json = fixture("/specs/petstore-swagger-2.json");
        SpecFetcher.FetchedSpec spec = fetcher.parseContent(json, null);
        assertThat(spec.parser().format()).isEqualTo("OPENAPI");
        assertThat(spec.parser().parse(spec.parsed())).hasSize(2);
    }

    @Test
    void parseContentRejectsNonSpecDocuments() {
        assertThatThrownBy(() -> fetcher.parseContent("{\"hello\": \"world\"}", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a recognizable");
    }

    private static String fixture(String path) throws IOException {
        try (InputStream in = SpecFetcherTests.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
