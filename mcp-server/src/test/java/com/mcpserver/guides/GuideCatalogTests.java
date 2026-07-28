package com.mcpserver.guides;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GuideCatalogTests {

    private final GuideCatalog catalog = new GuideCatalog();

    @Test
    void exposesArticlesForPeopleAndMcpClientInstructions() {
        assertThat(catalog.summaries()).extracting(GuideCatalog.GuideSummary::id)
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
