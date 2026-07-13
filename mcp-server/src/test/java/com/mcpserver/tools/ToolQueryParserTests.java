package com.mcpserver.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolQueryParserTests {

    @Test
    void hashKeywordWithFreeText() {
        var parsed = ToolQueryParser.parse("#todo_app_create_todo Need to meet chairman").orElseThrow();
        assertThat(parsed.appSlug()).isNull();
        assertThat(parsed.toolKeyword()).isEqualTo("todo_app_create_todo");
        assertThat(parsed.remainder()).isEqualTo("Need to meet chairman");
    }

    @Test
    void atAppWithHashToolAndFreeText() {
        var parsed = ToolQueryParser.parse("@appname #create_to go for shopping list: - apple - tomato").orElseThrow();
        assertThat(parsed.appSlug()).isEqualTo("appname");
        assertThat(parsed.toolKeyword()).isEqualTo("create_to");
        assertThat(parsed.remainder()).isEqualTo("go for shopping list: - apple - tomato");
    }

    @Test
    void atAppAloneBrowsesTools() {
        var parsed = ToolQueryParser.parse("@petstore").orElseThrow();
        assertThat(parsed.appSlug()).isEqualTo("petstore");
        assertThat(parsed.toolKeyword()).isEmpty();
        assertThat(parsed.remainder()).isEmpty();
    }

    @Test
    void inlineJsonRemainderIsPreservedVerbatim() {
        var parsed = ToolQueryParser.parse("@petstore #add_pet {\"name\": \"Rex\"}").orElseThrow();
        assertThat(parsed.remainder()).isEqualTo("{\"name\": \"Rex\"}");
    }

    @Test
    void keywordIsNormalizedLikeASlug() {
        var parsed = ToolQueryParser.parse("@todo #createTodo milk").orElseThrow();
        assertThat(parsed.toolKeyword()).isEqualTo("create_todo");
    }

    @Test
    void plainQueriesFallThroughToRag() {
        assertThat(ToolQueryParser.parse("how do I configure #tags in the wiki")).isEmpty();
        assertThat(ToolQueryParser.parse("ordinary search query")).isEmpty();
        assertThat(ToolQueryParser.parse("  ")).isEmpty();
    }

    @Test
    void blankIsNotAToolQuery() {
        assertThat(ToolQueryParser.parse("")).isEmpty();
        assertThat(ToolQueryParser.parse(null)).isEmpty();
    }
}
