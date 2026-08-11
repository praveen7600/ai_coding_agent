package com.praveen.aicodingagent.orchestrator.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.util.List;

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
}
