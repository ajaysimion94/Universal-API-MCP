package com.mcpserver.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotProtocolTests {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sendFrameCarriesPromptVerbatimEvenWithNastyCharacters() throws Exception {
        String prompt = "quote \" backslash \\ newline \n tab \t unicode é漢字 — and {json: true}";

        JsonNode frame = mapper.readTree(CopilotProtocol.sendFrame("conv-1", prompt, null));

        assertThat(frame.path("event").asText()).isEqualTo("send");
        assertThat(frame.path("conversationId").asText()).isEqualTo("conv-1");
        assertThat(frame.path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(frame.path("content").get(0).path("text").asText()).isEqualTo(prompt);
        assertThat(frame.path("mode").asText()).isEqualTo(CopilotProtocol.DEFAULT_MODE);
        assertThat(frame.has("context")).isTrue();
    }

    @Test
    void sendFrameRespectsCustomMode() throws Exception {
        JsonNode frame = mapper.readTree(CopilotProtocol.sendFrame("conv-1", "hi", "reasoning"));
        assertThat(frame.path("mode").asText()).isEqualTo("reasoning");
    }

    @Test
    void challengeResponseMatchesRealClientFrame() throws Exception {
        JsonNode frame = mapper.readTree(CopilotProtocol.challengeResponse("tok\"en", "hashcash"));

        assertThat(frame.path("event").asText()).isEqualTo("challengeResponse");
        assertThat(frame.path("token").asText()).isEqualTo("tok\"en");
        assertThat(frame.path("method").asText()).isEqualTo("hashcash");
        // The real client sends exactly {event, token, method} — no id field.
        assertThat(frame.has("id")).isFalse();
    }

    @Test
    void setOptionsFrameDeclaresFeaturesAndUiComponents() throws Exception {
        JsonNode frame = mapper.readTree(CopilotProtocol.setOptionsFrame());

        assertThat(frame.path("event").asText()).isEqualTo("setOptions");
        assertThat(frame.path("supportedFeatures").isArray()).isTrue();
        assertThat(frame.path("supportedFeatures").size()).isGreaterThan(0);
        assertThat(frame.path("supportedCards").size()).isGreaterThan(0);
        assertThat(frame.path("supportedUIComponents").path("Markdown").asText()).isEqualTo("1.2");
    }

    @Test
    void consentsFrameIsValidJson() throws Exception {
        JsonNode frame = mapper.readTree(CopilotProtocol.consentsFrame());
        assertThat(frame.path("event").asText()).isEqualTo("reportLocalConsents");
        assertThat(frame.path("grantedConsents").isArray()).isTrue();
    }
}
