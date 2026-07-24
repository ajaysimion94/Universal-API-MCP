package com.mcpserver.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class JiraAndConfluenceToolProviderTests {

    @Autowired
    private JiraToolProvider jiraToolProvider;

    @Autowired
    private ConfluenceToolProvider confluenceToolProvider;

    @Test
    void testJiraDefinitions() {
        List<ApiToolDefinition> defs = jiraToolProvider.getDefinitions();
        assertThat(defs).isNotEmpty();
        assertThat(defs).anyMatch(d -> d.requestSlug().equals("search_issues"));
        assertThat(defs).anyMatch(d -> d.requestSlug().equals("create_issue"));
        assertThat(defs).anyMatch(d -> d.requestSlug().equals("add_comment"));
    }

    @Test
    void testConfluenceDefinitions() {
        List<ApiToolDefinition> defs = confluenceToolProvider.getDefinitions();
        assertThat(defs).isNotEmpty();
        assertThat(defs).anyMatch(d -> d.requestSlug().equals("search_pages"));
        assertThat(defs).anyMatch(d -> d.requestSlug().equals("read_page"));
        assertThat(defs).anyMatch(d -> d.requestSlug().equals("create_page"));
    }
}
