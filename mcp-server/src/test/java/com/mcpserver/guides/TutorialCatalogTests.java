package com.mcpserver.guides;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tutorial is only useful if a reader can follow it with the app open, so the properties worth
 * pinning are structural: every step says where it happens and how to tell it worked.
 */
class TutorialCatalogTests {

    private static final List<String> ROUTES =
            List.of("/", "/files", "/plugins", "/connections", "/apps", "/insights", "/help", "/tutorial");

    private final TutorialCatalog catalog = new TutorialCatalog();

    @Test
    void exposesTheWalkthroughsWithTheirStepCounts() {
        assertThat(catalog.summaries()).extracting(TutorialCatalog.TutorialSummary::id)
                .containsExactly("first-answer", "first-insight");
        assertThat(catalog.summaries()).allSatisfy(summary -> {
            assertThat(summary.title()).isNotBlank();
            assertThat(summary.outcome()).as("a walkthrough must state what you end up with").isNotBlank();
            assertThat(summary.duration()).isNotBlank();
            assertThat(summary.steps()).isPositive();
        });
    }

    @Test
    void aSummaryStepCountMatchesTheArticleItDescribes() {
        for (TutorialCatalog.TutorialSummary summary : catalog.summaries()) {
            TutorialCatalog.Tutorial tutorial = catalog.find(summary.id()).orElseThrow();
            assertThat(tutorial.steps()).hasSize(summary.steps());
        }
    }

    /** A step that does not say where to go, or how to know it worked, cannot be followed. */
    @Test
    void everyStepNamesItsRouteAndHowToVerifyIt() {
        for (TutorialCatalog.TutorialSummary summary : catalog.summaries()) {
            TutorialCatalog.Tutorial tutorial = catalog.find(summary.id()).orElseThrow();
            assertThat(tutorial.steps()).allSatisfy(step -> {
                assertThat(step.title()).isNotBlank();
                assertThat(step.body()).isNotBlank();
                assertThat(step.actions()).isNotEmpty();
                assertThat(step.verify()).as("step '%s' must be verifiable", step.title()).isNotBlank();
                assertThat(step.route()).as("step '%s' must name where it happens", step.title()).isNotBlank();
            });
        }
    }

    /** A link into a route the SPA does not serve would dead-end the reader. */
    @Test
    void everyStepRouteIsARealPage() {
        for (TutorialCatalog.TutorialSummary summary : catalog.summaries()) {
            TutorialCatalog.Tutorial tutorial = catalog.find(summary.id()).orElseThrow();
            assertThat(tutorial.steps()).extracting(TutorialCatalog.TutorialStep::route)
                    .allSatisfy(route -> assertThat(ROUTES).contains(route));
        }
    }

    @Test
    void anUnknownTutorialIsAbsentRatherThanFabricated() {
        assertThat(catalog.find("does-not-exist")).isEmpty();
    }
}
