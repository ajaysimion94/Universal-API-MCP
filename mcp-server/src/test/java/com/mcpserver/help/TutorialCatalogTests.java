package com.mcpserver.help;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tutorial is only useful if a reader can follow it with the app open, so the properties worth
 * pinning are structural: every step says where it happens and how to tell it worked, and every
 * example says what it is and what should come back.
 */
class TutorialCatalogTests {

    private static final List<String> ROUTES =
            List.of("/", "/files", "/plugins", "/connections", "/apps", "/insights", "/help", "/tutorial");

    private final TutorialCatalog catalog = new TutorialCatalog();

    @Test
    void exposesTheWalkthroughsWithTheirStepAndExampleCounts() {
        assertThat(catalog.summaries()).extracting(TutorialCatalog.TutorialSummary::id)
                .containsExactly("first-answer", "query-bar", "connect-sources", "api-tools", "first-insight",
                        "rql-queries", "mcp-client");
        assertThat(catalog.summaries()).allSatisfy(summary -> {
            assertThat(summary.title()).isNotBlank();
            assertThat(summary.outcome()).as("a walkthrough must state what you end up with").isNotBlank();
            assertThat(summary.duration()).isNotBlank();
            assertThat(summary.level()).isNotBlank();
            assertThat(summary.steps()).isPositive();
        });
    }

    @Test
    void aSummaryStepAndExampleCountMatchesTheWalkthroughItDescribes() {
        for (TutorialCatalog.TutorialSummary summary : catalog.summaries()) {
            TutorialCatalog.Tutorial tutorial = catalog.find(summary.id()).orElseThrow();
            assertThat(tutorial.steps()).hasSize(summary.steps());
            assertThat(tutorial.steps().stream().mapToInt(step -> step.examples().size()).sum())
                    .as("%s advertises its example count", summary.id())
                    .isEqualTo(summary.examples());
        }
    }

    /** A step that does not say where to go, or how to know it worked, cannot be followed. */
    @Test
    void everyStepNamesItsRouteAndHowToVerifyIt() {
        forEachStep(step -> {
            assertThat(step.title()).isNotBlank();
            assertThat(step.body()).isNotBlank();
            assertThat(step.actions()).isNotEmpty();
            assertThat(step.verify()).as("step '%s' must be verifiable", step.title()).isNotBlank();
            assertThat(step.route()).as("step '%s' must name where it happens", step.title()).isNotBlank();
        });
    }

    /** A link into a route the SPA does not serve would dead-end the reader. */
    @Test
    void everyStepRouteIsARealPage() {
        forEachStep(step -> assertThat(ROUTES)
                .as("step '%s' links to %s", step.title(), step.route())
                .contains(step.route()));
    }

    /** A snippet with no label and no expected result is something to copy, not something to check. */
    @Test
    void everyExampleSaysWhatItIsAndWhatShouldComeBack() {
        forEachStep(step -> assertThat(step.examples()).allSatisfy(example -> {
            assertThat(example.label()).as("an example in '%s' must be labelled", step.title()).isNotBlank();
            assertThat(example.code()).isNotBlank();
            assertThat(example.language()).isNotBlank();
            assertThat(example.result())
                    .as("example '%s' must say what the app does in response", example.label()).isNotBlank();
        }));
    }

    /** A fix with no symptom cannot be found by the reader who has the symptom. */
    @Test
    void everyTroubleshootingEntryPairsASymptomWithAFix() {
        forEachStep(step -> assertThat(step.troubleshooting()).allSatisfy(entry -> {
            assertThat(entry.symptom()).isNotBlank();
            assertThat(entry.fix()).as("symptom '%s' must have a fix", entry.symptom()).isNotBlank();
        }));
    }

    /** The walkthroughs are the reason the Help page exists, so they must actually carry examples. */
    @Test
    void theCatalogueIsWorkedThroughRatherThanOutlined() {
        int examples = catalog.summaries().stream().mapToInt(TutorialCatalog.TutorialSummary::examples).sum();
        assertThat(examples).as("the tutorial page is example-led").isGreaterThanOrEqualTo(30);
        assertThat(catalog.summaries()).allSatisfy(summary ->
                assertThat(summary.steps()).as("%s is a walkthrough, not a note", summary.id())
                        .isGreaterThanOrEqualTo(3));
    }

    /** Cross-links are navigation: one pointing at a tutorial that does not exist is a dead end. */
    @Test
    void everyCrossLinkedTutorialExists() {
        for (TutorialCatalog.TutorialSummary summary : catalog.summaries()) {
            TutorialCatalog.Tutorial tutorial = catalog.find(summary.id()).orElseThrow();
            assertThat(tutorial.nextTutorials()).allSatisfy(id -> {
                assertThat(catalog.find(id)).as("%s links to %s", summary.id(), id).isPresent();
                assertThat(id).as("%s should not link to itself", summary.id()).isNotEqualTo(summary.id());
            });
        }
    }

    @Test
    void anUnknownTutorialIsAbsentRatherThanFabricated() {
        assertThat(catalog.find("does-not-exist")).isEmpty();
    }

    private void forEachStep(StepAssertion assertion) {
        for (TutorialCatalog.TutorialSummary summary : catalog.summaries()) {
            TutorialCatalog.Tutorial tutorial = catalog.find(summary.id()).orElseThrow();
            tutorial.steps().forEach(assertion::check);
        }
    }

    @FunctionalInterface
    private interface StepAssertion {
        void check(TutorialCatalog.TutorialStep step);
    }
}
