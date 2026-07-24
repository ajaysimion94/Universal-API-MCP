package com.mcpserver.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Provides first-party GitHub read + write tool definitions.
 */
@Component
public class GitHubToolProvider {
    private final ObjectMapper mapper = new ObjectMapper();

    public List<ApiToolDefinition> getDefinitions() {
        // github_list_repositories
        ObjectNode listSchema = mapper.createObjectNode();
        listSchema.put("type", "object");

        ApiToolDefinition listRepos = new ApiToolDefinition(
                "List GitHub Repositories",
                "list_repositories",
                "List repositories belonging to the authenticated user",
                "github",
                "GET",
                "/user/repos?per_page=10",
                listSchema,
                Map.of(),
                Map.of("Accept", "application/vnd.github+json"),
                null,
                null
        );

        // github_search_repositories
        ObjectNode searchSchema = mapper.createObjectNode();
        searchSchema.put("type", "object");
        ObjectNode searchProps = searchSchema.putObject("properties");
        searchProps.putObject("query").put("type", "string").put("description", "Search query term");
        searchSchema.putArray("required").add("query");

        ApiToolDefinition searchRepos = new ApiToolDefinition(
                "Search GitHub Repositories",
                "search_repositories",
                "Search GitHub repositories by query term",
                "github",
                "GET",
                "/search/repositories?q={query}",
                searchSchema,
                Map.of("query", "query"),
                Map.of("Accept", "application/vnd.github+json"),
                null,
                "query"
        );

        // github_create_issue
        ObjectNode createSchema = mapper.createObjectNode();
        createSchema.put("type", "object");
        ObjectNode createProps = createSchema.putObject("properties");
        createProps.putObject("owner").put("type", "string").put("description", "Repository owner");
        createProps.putObject("repo").put("type", "string").put("description", "Repository name");
        createProps.putObject("title").put("type", "string").put("description", "Issue title");
        createProps.putObject("body").put("type", "string").put("description", "Issue description body");
        createSchema.putArray("required").add("owner").add("repo").add("title");

        String createBodyTemplate = """
                {
                  "title": "${title}",
                  "body": "${body}"
                }
                """;

        ApiToolDefinition createIssue = new ApiToolDefinition(
                "Create GitHub Issue",
                "create_issue",
                "Create an issue in a repository",
                "github",
                "POST",
                "/repos/{owner}/{repo}/issues",
                createSchema,
                Map.of(
                        "owner", "path",
                        "repo", "path",
                        "title", "body",
                        "body", "body"
                ),
                Map.of("Content-Type", "application/json", "Accept", "application/vnd.github+json"),
                createBodyTemplate,
                "title"
        );

        // github_get_issue
        ObjectNode getSchema = mapper.createObjectNode();
        getSchema.put("type", "object");
        ObjectNode getProps = getSchema.putObject("properties");
        getProps.putObject("owner").put("type", "string").put("description", "Repository owner");
        getProps.putObject("repo").put("type", "string").put("description", "Repository name");
        getProps.putObject("issueNumber").put("type", "integer").put("description", "Issue number");
        getSchema.putArray("required").add("owner").add("repo").add("issueNumber");

        ApiToolDefinition getIssue = new ApiToolDefinition(
                "Get GitHub Issue",
                "get_issue",
                "Get a single issue from a repository by number",
                "github",
                "GET",
                "/repos/{owner}/{repo}/issues/{issueNumber}",
                getSchema,
                Map.of(
                        "owner", "path",
                        "repo", "path",
                        "issueNumber", "path"
                ),
                Map.of("Accept", "application/vnd.github+json"),
                null,
                "issueNumber"
        );

        // github_create_pull_request
        ObjectNode prSchema = mapper.createObjectNode();
        prSchema.put("type", "object");
        ObjectNode prProps = prSchema.putObject("properties");
        prProps.putObject("owner").put("type", "string").put("description", "Repository owner");
        prProps.putObject("repo").put("type", "string").put("description", "Repository name");
        prProps.putObject("title").put("type", "string").put("description", "Pull request title");
        prProps.putObject("head").put("type", "string").put("description", "The name of the branch where your changes are implemented");
        prProps.putObject("base").put("type", "string").put("description", "The name of the branch you want the changes pulled into");
        prProps.putObject("body").put("type", "string").put("description", "PR description body");
        prSchema.putArray("required").add("owner").add("repo").add("title").add("head").add("base");

        String prBodyTemplate = """
                {
                  "title": "${title}",
                  "head": "${head}",
                  "base": "${base}",
                  "body": "${body}"
                }
                """;

        ApiToolDefinition createPr = new ApiToolDefinition(
                "Create GitHub Pull Request",
                "create_pull_request",
                "Create a new pull request in a repository",
                "github",
                "POST",
                "/repos/{owner}/{repo}/pulls",
                prSchema,
                Map.of(
                        "owner", "path",
                        "repo", "path",
                        "title", "body",
                        "head", "body",
                        "base", "body",
                        "body", "body"
                ),
                Map.of("Content-Type", "application/json", "Accept", "application/vnd.github+json"),
                prBodyTemplate,
                "title"
        );

        return List.of(listRepos, searchRepos, createIssue, getIssue, createPr);
    }
}
