package com.mcpserver.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Wire protocol for the Copilot chat WebSocket: endpoint URLs and the JSON frames the
 * client sends. All frames are built with Jackson so arbitrary prompt text (quotes,
 * newlines, unicode) is escaped correctly.
 */
public class CopilotProtocol {

    public static final String BASE_URL = "https://copilot.microsoft.com";
    public static final String CHAT_WEBSOCKET_URL = "wss://copilot.microsoft.com/c/api/chat?api-version=2";
    public static final String CONVERSATIONS_URL = BASE_URL + "/c/api/conversations";
    public static final String ATTACHMENTS_URL = BASE_URL + "/c/api/attachments";

    /** Default conversation mode when none is configured. */
    public static final String DEFAULT_MODE = "smart";

    private static final ObjectMapper mapper = new ObjectMapper();

    public static String setOptionsFrame() {
        ObjectNode root = mapper.createObjectNode();
        root.put("event", "setOptions");

        ArrayNode features = root.putArray("supportedFeatures");
        features.add("partial-generated-images");
        features.add("composer-prefill-conversation-action");
        features.add("composer-send-conversation-action-v2");
        features.add("side-by-side-comparison");
        features.add("session-duration-nudge");
        features.add("compose-email-html");

        ArrayNode cards = root.putArray("supportedCards");
        String[] cardValues = {
            "weather", "local", "image", "sports", "video", "healthcareEntity",
            "healthcareInfo", "healthRecordsConnectNewProvider", "healthRecordsUpdate",
            "suggestHealth", "chart", "ads", "safetyHelpline", "quiz", "finance",
            "recipe", "personalArtifacts", "flashcard", "navigation", "person",
            "powerPointCreator", "consentV2", "composeEmail", "createCalendarEvent",
            "modifyCalendarEvent", "deleteCalendarEvent", "practiceTest", "tapToReveal"
        };
        for (String c : cardValues) cards.add(c);

        ObjectNode ui = root.putObject("supportedUIComponents");
        String[] uiKeys = {"Badge","Basic","Box","Button","Card","Caption","Chart","Checkbox","Col","DatePicker","Divider","Form","Icon","Image","Label","ListView","ListViewItem","Map","Markdown","Pressable","RadioGroup","Row","Select","Spacer","Table","Table.Cell","Table.Row","Text","Textarea","Title","Transition"};
        for (String k : uiKeys) ui.put(k, "1.2");
        ui.put("DatePicker", "1.3");
        ui.put("Map", "1.3");
        ui.put("Pressable", "1.3");
        ui.put("RadioGroup", "1.3");
        ui.put("Select", "1.3");
        ui.put("Textarea", "1.3");

        return root.toString();
    }

    public static String consentsFrame() {
        ObjectNode root = mapper.createObjectNode();
        root.put("event", "reportLocalConsents");
        root.putArray("grantedConsents");
        return root.toString();
    }

    public static String sendFrame(String conversationId, String prompt, String mode) {
        ObjectNode root = mapper.createObjectNode();
        root.put("event", "send");
        root.put("conversationId", conversationId);
        ArrayNode content = root.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", prompt);
        root.put("mode", (mode == null || mode.isBlank()) ? DEFAULT_MODE : mode);
        root.putObject("context");
        return root.toString();
    }

    public static String challengeResponse(String token, String method, String id) {
        ObjectNode root = mapper.createObjectNode();
        root.put("event", "challengeResponse");
        root.put("token", token);
        root.put("method", method);
        root.put("id", id == null ? "" : id);
        return root.toString();
    }

    private CopilotProtocol() {}
}
