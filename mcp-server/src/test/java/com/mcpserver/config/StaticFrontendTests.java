package com.mcpserver.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class StaticFrontendTests {

    private static final Path STATIC = Path.of("src/main/resources/static");
    private static final Pattern MODULE_IMPORT =
            Pattern.compile("(?:from\\s+|import\\()[\"']([^\"']+\\.js)[\"']");

    @Test
    void browserApplicationHasNoNodeBuildDependency() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom)
                .doesNotContain("frontend-maven-plugin")
                .doesNotContain("node.version")
                .doesNotContain("npm.version")
                .doesNotContain("skip.frontend");
        assertThat(Files.isRegularFile(STATIC.resolve("index.html"))).isTrue();
        assertThat(Files.isRegularFile(STATIC.resolve("app.js"))).isTrue();
        assertThat(Files.exists(Path.of("webui"))).isFalse();
    }

    @Test
    void everyLocalJavaScriptImportResolves() throws IOException {
        List<Path> modules;
        try (var files = Files.walk(STATIC)) {
            modules = files.filter(path -> path.toString().endsWith(".js")).toList();
        }

        assertThat(modules).isNotEmpty();
        for (Path module : modules) {
            String source = Files.readString(module);
            var matcher = MODULE_IMPORT.matcher(source);
            while (matcher.find()) {
                String specifier = matcher.group(1);
                if (!specifier.startsWith(".")) continue;
                Path target = module.getParent().resolve(specifier).normalize();
                assertThat(target)
                        .as("%s imports %s", module, specifier)
                        .isRegularFile();
            }
        }
    }

    @Test
    void applicationShellLoadsOnlyLocalStaticAssets() throws IOException {
        String index = Files.readString(STATIC.resolve("index.html"));

        assertThat(index)
                .contains("type=\"module\" src=\"/app.js?")
                .contains("href=\"/styles.css?")
                .contains("href=\"/components.css?")
                .doesNotContain("node_modules")
                .doesNotContain("react")
                .doesNotContain("vite");
    }

    @Test
    void searchSessionsKeepConsecutiveTurnsAndExposeRequestAndResponseModes() throws IOException {
        String search = Files.readString(STATIC.resolve("pages/search.js"));

        assertThat(search)
                .contains("mcp.search.sessions.v2")
                .contains("session.turns.push(turn)")
                .contains("Raw response")
                .contains("Raw body")
                .contains("bodyMode: \"RAW\"")
                .contains("api.previewTool")
                .doesNotContain("if (!reuse && session.query)");
    }

    /**
     * Guards the two ways the feedback UI can silently produce garbage: a vote button that also
     * carries {@code data-example} would never reach its handler (the delegated listener returns
     * early on that attribute), and crediting the pre-expanded first group would attach a phantom
     * EXPAND to every single search.
     */
    @Test
    void resultFeedbackUsesDataActionAndOnlyCreditsUserInitiatedExpands() throws IOException {
        String search = Files.readString(STATIC.resolve("pages/search.js"));

        assertThat(search)
                .contains("data-action=\"rate-result\"")
                .contains("api.sendFeedback")
                .contains("signal: \"EXPAND\"")
                .contains("signal: \"OPEN\"")
                // Votes live on the turn so they survive the full re-render every action triggers.
                .contains("patchTurn(session.id, turnId, { votes })")
                // The expand signal is emitted only inside the branch that opens a group.
                .contains("if (!content.hidden) sendExpandSignals(target, content);");

        assertThat(search.replaceAll("\\s+", " "))
                .as("a vote button must not carry data-example")
                .doesNotContain("data-action=\"rate-result\" data-example");
    }

    @Test
    void searchAutocompleteDiscoversAppsGroupsAndScopedRequests() throws IOException {
        String search = Files.readString(STATIC.resolve("pages/search.js"));

        assertThat(search)
                .contains("api.listGroups()")
                .contains("api.getGroup(group.id)")
                .contains("Apps and groups")
                .contains("Requests in @")
                .contains("state.groupTools[scope]")
                .contains("requestSlug")
                .contains("event.key === \"ArrowDown\"")
                .contains("role=\"listbox\"");
    }

    @Test
    void connectionImportOffersBaseOverrideAndSourceUrlPolicies() throws IOException {
        String connections = Files.readString(STATIC.resolve("pages/connections.js"));

        assertThat(connections)
                .contains("name=\"apiUrlMode\"")
                .contains("value=\"CONNECTION_BASE\"")
                .contains("value=\"SOURCE_URLS\"")
                .contains("Keep source URLs")
                .contains("apiUrlMode: data.apiUrlMode");
    }
}
