package com.mcpserver.help;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HelpCatalogTests {

    private final HelpCatalog catalog = new HelpCatalog();

    @Test
    void exposesTopicsForPeopleAndMcpClientInstructions() {
        assertThat(catalog.summaries()).extracting(HelpCatalog.TopicSummary::id)
                .containsExactly("start", "knowledge", "api-tools", "queries", "insights", "mcp-clients", "development");
        assertThat(catalog.find("mcp-clients")).isPresent();
        assertThat(catalog.llmGuideMarkdown())
                .contains("search-knowledge-base", "confirm-action", "explicit, current human approval");
    }

    @Test
    void exposesAStructuredPlaybookWithCurrentResources() {
        assertThat(catalog.llmPlaybook())
                .containsKeys("sessionStart", "groundedAnswer", "actionSafety", "availableResources");
        assertThat(catalog.llmPlaybook().get("availableResources").toString())
                .contains("mcp://enterprise-mcp/guides/operating-guide");
    }
}
