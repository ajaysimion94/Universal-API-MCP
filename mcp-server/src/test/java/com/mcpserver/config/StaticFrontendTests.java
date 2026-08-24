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
    private static final Path WEBUI = Path.of("webui");
    private static final Pattern MODULE_IMPORT =
            Pattern.compile("(?:from\\s+|import\\()[\"']([^\"']+\\.js)[\"']");

    @Test
    void reactApplicationIsBuiltAndPackagedByMaven() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom)
                .contains("frontend-maven-plugin")
                .contains("<node.version>")
                .contains("<npm.version>")
                .contains("<arguments>ci --include=dev</arguments>")
                .contains("<id>npm-test</id>")
                .contains("<arguments>test</arguments>")
                .contains("<id>clean-generated-frontend-assets</id>")
                .contains("${project.build.outputDirectory}/static/assets")
                .contains("<id>copy-frontend</id>");
        assertThat(Files.isRegularFile(WEBUI.resolve("index.html"))).isTrue();
        assertThat(Files.isRegularFile(WEBUI.resolve("src/App.tsx"))).isTrue();
        assertThat(Files.isRegularFile(WEBUI.resolve("package-lock.json"))).isTrue();
        assertThat(Files.isRegularFile(STATIC.resolve("pages/search.js"))).isTrue();
    }

    @Test
    void frontendUsesLocalFontsAndKeepsTheFlatVisualContract() throws IOException {
        String main = Files.readString(WEBUI.resolve("src/main.tsx"));
        String fonts = Files.readString(WEBUI.resolve("src/fonts.css"));
        String components = Files.readString(STATIC.resolve("components.css"));

        assertThat(main).contains("import \"./fonts.css\"");
        assertThat(fonts)
                .contains("hanken-grotesk-latin-wght-normal.woff2")
                .contains("jetbrains-mono-latin-wght-normal.woff2")
                .doesNotContain("http://")
                .doesNotContain("https://");
        assertThat(components)
                .contains("animation: skeleton-pulse")
                .doesNotContain("linear-gradient(")
                .doesNotContain("radial-gradient(")
                .doesNotContain("conic-gradient(")
                .doesNotContain("box-shadow:")
                .doesNotContain("backdrop-filter:");
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
    void applicationShellLoadsOnlyLocalCompiledAssets() throws IOException {
        String index = Files.readString(WEBUI.resolve("index.html"));
        String app = Files.readString(WEBUI.resolve("src/App.tsx"));

        assertThat(index)
                .contains("type=\"module\" src=\"/src/main.tsx\"")
                .contains("href=\"/styles.css?v=ui-style-2\"")
                .contains("href=\"/components.css?v=ui-components-30\"")
                .doesNotContain("http://")
                .doesNotContain("https://");
        assertThat(app)
                .contains("<Routes>")
                .contains("LegacyPage")
                .contains("/pages/insights.js?v=ui-logic-30")
                .contains("/pages/apps.js?v=ui-logic-5")
                .contains("onKeyDown={quickSearchKeyDown}")
                .contains("event.nativeEvent.isComposing")
                .contains("outlet.focus({ preventScroll: true })")
                .contains("tabIndex={-1}");
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
                .contains("APIs and groups")
                .contains("Requests in @")
                .contains("state.groupTools[scope]")
                .contains("requestSlug")
                .contains("event.key === \"ArrowDown\"")
                .contains("role=\"listbox\"");
    }

    @Test
    void searchAutocompleteOpensAboveTheComposer() throws IOException {
        String components = Files.readString(STATIC.resolve("components.css"));

        assertThat(components)
                .contains("inset: auto 0 calc(100% + 5px) 0;")
                .doesNotContain("inset: calc(100% + 5px) 0 auto 0;");
    }

    @Test
    void connectionImportSupportsAnOptionalBaseUrlOverride() throws IOException {
        String connections = Files.readString(STATIC.resolve("pages/connections.js"));

        assertThat(connections)
                .contains("name=\"apiUrlMode\"")
                .contains("value=\"CONNECTION_BASE\"")
                .contains("value=\"SOURCE_URLS\"")
                .contains("Use one base URL")
                .contains("Keep each request's URL")
                .contains("Base URL <small>(optional override)</small>")
                .contains("Leave blank to use the API URL declared by the document")
                .contains("readonly aria-readonly=\"true\"")
                .contains("detected.baseUrl")
                .contains("localhost</span> means the machine running MCP Server")
                .contains("this connection's credentials may be sent to every host")
                .contains("apiUrlMode: data.apiUrlMode")
                .contains("baseUrl: data.baseUrl")
                .contains("connection?.type === \"API_COLLECTION\"")
                .contains("Each request keeps the host declared in the source file");
    }

    @Test
    void importedApiDefinitionsDoNotClaimTheirRemoteTargetsAreConnected() throws IOException {
        String connections = Files.readString(STATIC.resolve("pages/connections.js"));

        assertThat(connections)
                .contains("return \"Imported\"")
                .contains("Definition imported · test the remote target in APIs")
                .contains("href=\"/apps\">View in APIs</a>");
    }

    @Test
    void appsRequestBuilderExposesResolvedTargetsAndRemoteErrors() throws IOException {
        String apps = Files.readString(STATIC.resolve("pages/apps.js"));

        assertThat(apps)
                .contains("if (selectedTool()) schedulePreview();")
                .contains("result.request")
                .contains("Remote API returned HTTP ${result.status} with an empty response body.")
                .contains("successful ? \"\" : \"open\"")
                .doesNotContain("No groups yet. Create one for batch control and an @group search handle.");
    }

    @Test
    void atlassianConnectionsExposeEditableBasicAndPatSettings() throws IOException {
        String connections = Files.readString(STATIC.resolve("pages/connections.js"));

        assertThat(connections)
                .contains("Cloud token / password")
                .contains("Data Center PAT")
                .contains("state.editingAuth === connection.id")
                .contains("id=\"edit-auth-mode\"")
                .contains("name: data.name || undefined")
                .contains("authMode: state.authMode");
        assertThat(connections)
                .doesNotContain("${isApi ? `<button class=\"btn btn-ghost ${state.editingAuth");
    }

    @Test
    void connectionsSurfaceCanRunAndDisplayAHealthCheck() throws IOException {
        String connections = Files.readString(STATIC.resolve("pages/connections.js"));
        String api = Files.readString(STATIC.resolve("api.js"));

        assertThat(connections)
                .contains("Test connection")
                .contains("data-action=\"test-connection\"")
                .contains("lastTestSucceededAt")
                .contains("lastTestFailureCategory")
                .contains("api.testConnection(id)");
        assertThat(api).contains("testConnection: (id) => send(`${CONNECTIONS}/${id}/test`, \"POST\")");
    }

    @Test
    void pluginsPageLeavesOnnxProvisioningToStartup() throws IOException {
        String plugins = Files.readString(STATIC.resolve("pages/plugins.js"));
        String api = Files.readString(STATIC.resolve("api.js"));
        String extractor = Files.readString(Path.of(
                "src/main/java/com/mcpserver/plugins/BundledResourceExtractor.java"));
        String pom = Files.readString(Path.of("pom.xml"));
        String application = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(plugins)
                .doesNotContain("ONNX model files")
                .doesNotContain("skip.models")
                .doesNotContain("upload-model")
                .doesNotContain("data-model-file");
        assertThat(api)
                .doesNotContain("listOnnxModels")
                .doesNotContain("uploadOnnxModel");
        assertThat(extractor)
                .contains("downloadVerified")
                .contains("checksum does not match the pinned resource")
                .contains("Provisioned pinned ONNX resource");
        assertThat(plugins)
                .contains("Install & start")
                .contains("api.setupPlugin");
        assertThat(api).contains("setupPlugin: (id)");
        assertThat(pom)
                .contains("<skip.models>${skip.bundle}</skip.models>")
                .contains("<skip>${skip.models}</skip>");
        assertThat(application)
                .contains("auto-download: true")
                .contains("download-timeout: 10m")
                .contains("max-file-size: 256MB")
                .contains("max-request-size: 300MB");
    }

    @Test
    void controlsDescribeTheirActionsAndRemainKeyboardOperable() throws IOException {
        String files = Files.readString(STATIC.resolve("pages/files.js"));
        String search = Files.readString(STATIC.resolve("pages/search.js"));
        String apps = Files.readString(STATIC.resolve("pages/apps.js"));
        String connections = Files.readString(STATIC.resolve("pages/connections.js"));
        String plugins = Files.readString(STATIC.resolve("pages/plugins.js"));
        String ui = Files.readString(STATIC.resolve("ui.js"));

        assertThat(files)
                .contains("Upload files")
                .contains("Create folder")
                .contains("event.key === \"ArrowRight\"")
                .doesNotContain("data-action=\"noop\"");
        assertThat(search)
                .contains("New search")
                .contains("Delete search session")
                .contains("aria-controls=\"search-guide\"")
                .contains("Submitting…");
        assertThat(apps)
                .contains("aria-label=\"Close ${escapeAttr(tool.displayName || tool.name)}\"")
                .contains("role=\"tablist\" aria-label=\"Request editor sections\"")
                .contains("requestSubmitting")
                .contains("Enter the required ${plural(missing.length, \"field\")}")
                .doesNotContain("role=\"button\" tabindex=\"0\" data-action=\"toggle-app\"");
        assertThat(connections)
                .contains("Refresh content")
                .contains("Import content")
                .contains("as search content")
                .contains("state.submitting");
        assertThat(plugins)
                .contains("Start service")
                .contains("Clear learned preferences")
                .contains("Clear learned ranking preferences?");
        assertThat(ui).contains("ariaLabel = \"\"")
                .contains("headingOffset = 0");
    }

    @Test
    void secondaryTextTokensMeetTheRaisedContrastFloor() throws IOException {
        String styles = Files.readString(STATIC.resolve("styles.css"));

        assertThat(styles)
                .contains("--text-muted: oklch(0.64")
                .contains("--text-faint: oklch(0.56");
    }
}
