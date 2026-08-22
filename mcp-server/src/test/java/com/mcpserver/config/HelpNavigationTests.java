package com.mcpserver.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the help affordance and the tutorial route.
 *
 * <p>Source assertions rather than behavioural ones because the browser application deliberately has
 * no Node toolchain (asserted by {@link StaticFrontendTests#browserApplicationHasNoNodeBuildDependency}).
 * The rule these protect is the one a client-side router makes easy to break: every route the SPA
 * can reach also needs a server-side forward, or a direct visit or refresh 404s.
 */
class HelpNavigationTests {

    private static final Path STATIC = Path.of("src/main/resources/static");
    private static final Path ROUTER = STATIC.resolve("app.js");
    private static final Path WEB_MVC =
            Path.of("src/main/java/com/mcpserver/config/WebMvcConfig.java");

    @Test
    void helpIsReachedFromAQuestionMarkButtonRatherThanThePrimaryNav() throws IOException {
        String router = Files.readString(ROUTER);

        assertThat(router).contains("class=\"topbar-help").contains("href=\"/help\"");
        assertThat(router).contains("icon(\"help\"");

        String navBlock = router.substring(router.indexOf("const navItems"), router.indexOf("const HELP_PATHS"));
        assertThat(navBlock)
                .as("help belongs on the ? button, not in the list of places you work")
                .doesNotContain("/help")
                .doesNotContain("/tutorial");
    }

    @Test
    void theHelpButtonIsLabelledForScreenReaders() throws IOException {
        String router = Files.readString(ROUTER);
        int start = router.indexOf("class=\"topbar-help");
        String anchor = router.substring(start, router.indexOf(">", router.indexOf("icon(\"help\"", start)));
        assertThat(anchor).contains("aria-label=");
    }

    /** A client route with no server forward 404s on direct navigation or refresh. */
    @Test
    void everyClientRouteHasAServerSideForward() throws IOException {
        String router = Files.readString(ROUTER);
        String config = Files.readString(WEB_MVC);

        String routesBlock = router.substring(router.indexOf("const routes = {"), router.indexOf("const navItems"));
        Matcher matcher = Pattern.compile("\"(/[a-z-]*)\":").matcher(routesBlock);
        while (matcher.find()) {
            String route = matcher.group(1);
            if (route.equals("/")) continue;
            assertThat(config)
                    .as("%s is routable in the SPA, so it needs a forward in WebMvcConfig", route)
                    .contains("addViewController(\"" + route + "\")");
        }
    }

    /** /guide was the page's earlier name; old links and bookmarks must still land somewhere. */
    @Test
    void theOldGuideRouteStillResolves() throws IOException {
        assertThat(Files.readString(ROUTER)).contains("pathname.startsWith(\"/guide\")");
        assertThat(Files.readString(WEB_MVC)).contains("addViewController(\"/guide\")");
        assertThat(Files.exists(STATIC.resolve("pages/help.js"))).isTrue();
    }

    @Test
    void noPageStillLinksToTheRetiredGuideRoute() throws IOException {
        try (var files = Files.walk(STATIC)) {
            for (Path module : files.filter(path -> path.toString().endsWith(".js")).toList()) {
                assertThat(Files.readString(module))
                        .as("%s should link to /help, not the retired /guide", module)
                        .doesNotContain("href=\"/guide\"");
            }
        }
    }

    @Test
    void theTutorialPageTracksProgressWithoutBreakingWhenStorageIsUnavailable() throws IOException {
        String tutorial = Files.readString(STATIC.resolve("pages/tutorial.js"));

        assertThat(tutorial).contains("mcp.tutorial.progress.v1")
                .contains("function loadProgress(")
                .contains("function saveProgress(");
        for (String function : new String[] { "function loadProgress(", "function saveProgress(" }) {
            int start = tutorial.indexOf(function);
            assertThat(tutorial.substring(start, tutorial.indexOf("\n}", start)))
                    .as("%s must tolerate unavailable storage", function).contains("catch");
        }
    }

    @Test
    void theHelpPageOffersTheTutorials() throws IOException {
        assertThat(Files.readString(STATIC.resolve("pages/help.js")))
                .contains("api.listTutorials(")
                .contains("/tutorial?id=");
    }

    /**
     * The catalogue carries labelled examples with their expected result; a page that renders only
     * the code would drop the half of each example that lets a reader check themselves.
     */
    @Test
    void theTutorialPageRendersEveryPartOfAnExample() throws IOException {
        String tutorial = Files.readString(STATIC.resolve("pages/tutorial.js"));

        assertThat(tutorial)
                .contains("item.examples")
                .contains("tutorial-example-label")
                .contains("tutorial-example-result")
                .contains("item.troubleshooting");
    }

    @Test
    void tutorialExamplesContainWideCodeInsteadOfOverflowingTheMobilePage() throws IOException {
        String styles = Files.readString(STATIC.resolve("components.css"));

        assertThat(styles)
                .contains(".tutorial-step-body,\n.tutorial-examples,\n.tutorial-example { min-width: 0; max-width: 100%; }")
                .contains(".tutorial-code {\n  max-width: 100%;\n  overflow-x: auto;")
                .contains(".tutorial-example figcaption {\n  min-width: 0;")
                .contains("flex-wrap: wrap;");
    }

    /** The page was renamed from Guide to Help; its classes and API calls should not still say guide. */
    @Test
    void nothingInTheHelpSurfaceStillCallsItselfAGuide() throws IOException {
        for (String module : new String[] { "pages/help.js", "pages/tutorial.js" }) {
            assertThat(Files.readString(STATIC.resolve(module)))
                    .as("%s should use the help naming", module)
                    .doesNotContain("guide-")
                    .doesNotContain("api/guides");
        }
    }
}
