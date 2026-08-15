package com.praveen.aicodingagent.orchestrator.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.praveen.aicodingagent.orchestrator.tool.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This is the "does Gemini even respond" checkpoint from the build plan -
 * a real network call to the real API, not a fake. Deliberately NOT part of
 * the normal build: @EnabledIfEnvironmentVariable means this silently skips
 * (not fails) in CI or on anyone else's machine where GEMINI_API_KEY isn't
 * set, so `./mvnw test` stays fast and free everywhere except when you're
 * deliberately checking this by hand.
 *
 * Run it with:
 *   GEMINI_API_KEY=your-key ./mvnw test -Dtest=GeminiClientManualTest
 *
 * No Spring context needed - GeminiClient's three dependencies
 * (RestClient.Builder, GeminiProperties, ObjectMapper) are all plain objects
 * you can construct by hand, which is exactly the "complete isolation" step
 * 2 asked for.
 */
class GeminiClientManualTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
    void realGeminiCallReturnsText() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        String model = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-2.0-flash");

        GeminiProperties properties = new GeminiProperties(apiKey, model, 60);
        GeminiClient client = new GeminiClient(RestClient.builder(), properties, new ObjectMapper());

        ModelTurn result = client.generate(
                List.of(new ConversationTurn.UserMessage("Reply with exactly the words: hello world")),
                List.of() // no tools - this call alone proves the request/response wiring works
        );

        System.out.println("Gemini raw ModelTurn: " + result);

        assertThat(result).isInstanceOf(ConversationTurn.ModelText.class);
        String text = ((ConversationTurn.ModelText) result).text();
        assertThat(text).isNotBlank();
        System.out.println("Gemini said: " + text);
    }

    /**
     * This is the exact round trip that broke in practice: model returns a
     * functionCall, we feed a FunctionResult back, and the follow-up call
     * failed with "Function call is missing a thought_signature in
     * functionCall parts" because the signature Gemini attached to the
     * first call was being silently dropped instead of round-tripped.
     * Runs against the real API rather than a fake specifically because a
     * fake can't tell you whether Gemini's actual response shape and
     * validation rules are being honored - that's the whole reason this
     * bug shipped past the FakeLlmClient-based unit tests unnoticed.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
    void realGeminiFunctionCallRoundTripDoesNotFailOnTheFollowUpCall() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        String model = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-2.0-flash");

        GeminiProperties properties = new GeminiProperties(apiKey, model, 60);
        GeminiClient client = new GeminiClient(RestClient.builder(), properties, new ObjectMapper());

        ToolDefinition echoTool = new ToolDefinition(
                "echo",
                "Echoes back whatever text is given to it.",
                Map.of(
                        "type", "object",
                        "properties", Map.of("text", Map.of("type", "string")),
                        "required", List.of("text")
                )
        );

        List<ConversationTurn> history = new ArrayList<>();
        history.add(new ConversationTurn.UserMessage(
                "Call the echo tool with text set to exactly: pineapple. Then tell me what it returned."));

        ModelTurn firstTurn = client.generate(history, List.of(echoTool));
        System.out.println("First turn: " + firstTurn);
        assertThat(firstTurn).isInstanceOf(ConversationTurn.ModelFunctionCall.class);
        history.add((ConversationTurn) firstTurn);

        ConversationTurn.ModelFunctionCall call = (ConversationTurn.ModelFunctionCall) firstTurn;
        history.add(new ConversationTurn.FunctionResult(call.name(), Map.of("result", "pineapple")));

        // This is the call that used to throw LlmClientException with a 400
        // before thoughtSignature round-tripping was fixed.
        ModelTurn secondTurn = client.generate(history, List.of(echoTool));
        System.out.println("Second turn: " + secondTurn);
        assertThat(secondTurn).isInstanceOf(ConversationTurn.ModelText.class);
    }
}
