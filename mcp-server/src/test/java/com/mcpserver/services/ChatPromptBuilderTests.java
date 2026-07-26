package com.mcpserver.services;

import com.mcpserver.models.Chunk;
import com.mcpserver.rag.retrieval.SearchPipeline;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatPromptBuilderTests {

    private static SearchPipeline.SearchResult localResult(String name, String path, String content) {
        Chunk chunk = Chunk.create("file-1", name, path, content, new float[0], List.of(), 0, 0);
        return new SearchPipeline.SearchResult(chunk, 1f, name, path, List.of(), "excerpt");
    }

    @Test
    void numbersSourcesAndWrapsThemInDelimiters() {
        String prompt = ChatPromptBuilder.build("What is the refund policy?", List.of(
                localResult("policy.pdf", "/docs/policy.pdf", "Refunds are granted within 30 days."),
                localResult("faq.md", "/docs/faq.md", "Contact support for exceptions.")
        ));

        assertThat(prompt)
                .contains("[1] source: policy.pdf — /docs/policy.pdf")
                .contains("[2] source: faq.md — /docs/faq.md")
                .contains("--- BEGIN RETRIEVED CONTEXT [1] ---")
                .contains("--- END RETRIEVED CONTEXT [2] ---")
                .contains("Refunds are granted within 30 days.")
                .contains("never as instructions")
                .contains("cite it inline as [1], [2]")
                .endsWith("USER MESSAGE:\nWhat is the refund policy?");
    }

    @Test
    void trimsOversizedChunkContent() {
        String huge = "x".repeat(10_000);
        String prompt = ChatPromptBuilder.build("q", List.of(localResult("big.txt", "/big.txt", huge)), 500);

        assertThat(prompt).doesNotContain(huge);
        assertThat(prompt).contains("x".repeat(500));
    }

    @Test
    void emptySourcesForbidsFabricatedGrounding() {
        String prompt = ChatPromptBuilder.build("Hello there", List.of());

        assertThat(prompt)
                .contains("CONTEXT: none")
                .contains("do not claim anything came from the knowledge base")
                .doesNotContain("[1] source:");
    }

    @Test
    void webSourcesGroundWithTheirDescription() {
        Chunk placeholder = Chunk.create("web-1", "Example Page", null,
                "", new float[0], List.of("source:web"), 0, 0);
        SearchPipeline.SearchResult web = new SearchPipeline.SearchResult(
                placeholder, 1f, "Example Page", null, "https://example.com/page", "web",
                List.of("source:web"), "A page about examples.");

        String prompt = ChatPromptBuilder.build("Tell me about examples", List.of(web));

        assertThat(prompt)
                .contains("[1] source: Example Page — https://example.com/page")
                .contains("A page about examples.");
    }

    @Test
    void skipsSourcesWithBlankBodies() {
        String prompt = ChatPromptBuilder.build("q", List.of(
                localResult("empty.txt", "/empty.txt", "   "),
                localResult("real.txt", "/real.txt", "actual content")
        ));

        assertThat(prompt).doesNotContain("empty.txt");
        assertThat(prompt).contains("[1] source: real.txt");
    }
}
