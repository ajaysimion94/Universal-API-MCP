package com.mcpserver.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Provides first-party Jira read + write tool definitions.
 */
@Component
public class JiraToolProvider {
    private final ObjectMapper mapper = new ObjectMapper();

    public List<ApiToolDefinition> getDefinitions() {
        // jira_search_issues
        ObjectNode searchSchema = mapper.createObjectNode();
        searchSchema.put("type", "object");
        ObjectNode searchProps = searchSchema.putObject("properties");
        searchProps.putObject("query").put("type", "string").put("description", "Jira Query Language (JQL) search query");
        searchSchema.putArray("required").add("query");

        ApiToolDefinition searchIssues = new ApiToolDefinition(
                "Search Jira Issues",
                "search_issues",
                "Search Jira issues using JQL query",
                "jira",
                "GET",
                "/rest/api/3/search?jql={query}",
                searchSchema,
                Map.of("query", "query"),
                Map.of("Accept", "application/json"),
                null,
                "query"
        );

        // jira_create_issue
        ObjectNode createSchema = mapper.createObjectNode();
        createSchema.put("type", "object");
        ObjectNode createProps = createSchema.putObject("properties");
        createProps.putObject("projectKey").put("type", "string").put("description", "Project key");
        createProps.putObject("summary").put("type", "string").put("description", "Issue summary title");
        createProps.putObject("description").put("type", "string").put("description", "Issue description");
        createProps.putObject("issueType").put("type", "string").put("description", "Issue type").put("default", "Task");
        createSchema.putArray("required").add("projectKey").add("summary");

        String createBodyTemplate = """
                {
                  "fields": {
                    "project": { "key": "${projectKey}" },
                    "summary": "${summary}",
                    "description": {
                      "type": "doc",
                      "version": 1,
                      "content": [
                        {
                          "type": "paragraph",
                          "content": [
                            { "type": "text", "text": "${description}" }
                          ]
                        }
                      ]
                    },
                    "issuetype": { "name": "${issueType}" }
                  }
                }
                """;

        ApiToolDefinition createIssue = new ApiToolDefinition(
                "Create Jira Issue",
                "create_issue",
                "Create a new Jira issue",
                "jira",
                "POST",
                "/rest/api/3/issue",
                createSchema,
                Map.of(
                        "projectKey", "body",
                        "summary", "body",
                        "description", "body",
                        "issueType", "body"
                ),
                Map.of("Content-Type", "application/json", "Accept", "application/json"),
                createBodyTemplate,
                "summary"
        );

        // jira_add_comment
        ObjectNode commentSchema = mapper.createObjectNode();
        commentSchema.put("type", "object");
        ObjectNode commentProps = commentSchema.putObject("properties");
        commentProps.putObject("issueKey").put("type", "string").put("description", "Issue key or key id");
        commentProps.putObject("comment").put("type", "string").put("description", "Comment body text");
        commentSchema.putArray("required").add("issueKey").add("comment");

        String commentBodyTemplate = """
                {
                  "body": {
                    "type": "doc",
                    "version": 1,
                    "content": [
                      {
                        "type": "paragraph",
                        "content": [
                          { "type": "text", "text": "${comment}" }
                        ]
                      }
                    ]
                  }
                }
                """;

        ApiToolDefinition addComment = new ApiToolDefinition(
                "Add Jira Comment",
                "add_comment",
                "Add a comment to an existing Jira issue",
                "jira",
                "POST",
                "/rest/api/3/issue/{issueKey}/comment",
                commentSchema,
                Map.of(
                        "issueKey", "path",
                        "comment", "body"
                ),
                Map.of("Content-Type", "application/json", "Accept", "application/json"),
                commentBodyTemplate,
                "comment"
        );

        return List.of(searchIssues, createIssue, addComment);
    }
}
