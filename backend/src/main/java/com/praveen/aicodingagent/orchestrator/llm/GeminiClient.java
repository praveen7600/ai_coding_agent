package com.praveen.aicodingagent.orchestrator.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.praveen.aicodingagent.orchestrator.tool.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deliberately raw: builds the request JSON tree by hand with Jackson
 * instead of typed request/response DTOs for every one of Gemini's part
 * shapes. The tradeoff is explicit - typed DTOs would catch some mistakes
 * at compile time, but Gemini's `parts` array is a real union type
 * (text | functionCall | functionResponse) that doesn't map cleanly onto
 * Java records without either a sealed-interface-per-part-type (more
 * ceremony than this adapter is worth) or Jackson polymorphic
 * deserialization config (more magic than "raw REST calls" was supposed to
 * buy us). Building the tree directly keeps every byte of the wire format
 * visible in one method, which is the entire point of not using the
 * official SDK.
 */
@Component
@Slf4j
@EnableConfigurationProperties(GeminiProperties.class)
public class GeminiClient implements LlmClient {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models";

    /**
     * Free-tier 429s are the normal case under load, not an infrastructure
     * failure - the API even tells us how long to wait via retryDelay in
     * the error body. Retrying a couple of times with that delay (falling
     * back to a fixed wait if the body doesn't have one) turns a transient
     * quota blip into one extra second of latency instead of an
     * "Agent failed unexpectedly" for the whole task. Bounded at 3 attempts
     * so a genuinely exhausted quota still fails fast rather than hanging
     * the orchestrator loop.
     */
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(20);
    private static final Pattern RETRY_DELAY_PATTERN =
            Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)s\"");

    private final RestClient restClient;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    public GeminiClient(RestClient.Builder restClientBuilder, GeminiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder
                .baseUrl(BASE_URL)
                .requestFactory(clientRequestFactory(properties.requestTimeoutSeconds()))
                .build();
    }

    @Override
    public ModelTurn generate(List<ConversationTurn> history, List<ToolDefinition> tools) {
        ObjectNode requestBody = buildRequestBody(history, tools);

        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                JsonNode response = restClient.post()
                        .uri("/{model}:generateContent", properties.model())
                        .header("x-goog-api-key", properties.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .body(JsonNode.class);
                return parseResponse(response);
            } catch (RestClientResponseException e) {
                boolean isLastAttempt = attempt == MAX_RETRY_ATTEMPTS;
                if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS && !isLastAttempt) {
                    Duration delay = retryDelayFrom(e.getResponseBodyAsString());
                    log.warn("Gemini rate limited (attempt {}/{}), retrying in {}",
                            attempt, MAX_RETRY_ATTEMPTS, delay);
                    sleep(delay);
                    continue;
                }
                // Gemini returns a JSON error body with useful detail (invalid
                // key, quota exceeded, malformed schema) - surface it instead of
                // just the HTTP status, since that body is usually the fastest
                // way to diagnose a bad request while building this out.
                throw new LlmClientException(
                        "Gemini API call failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
            }
        }
        // Unreachable: the loop above always either returns or throws.
        throw new LlmClientException("Gemini API call failed after " + MAX_RETRY_ATTEMPTS + " attempts");
    }

    private Duration retryDelayFrom(String responseBody) {
        if (responseBody == null) {
            return DEFAULT_RETRY_DELAY;
        }
        Matcher matcher = RETRY_DELAY_PATTERN.matcher(responseBody);
        if (matcher.find()) {
            return Duration.ofSeconds(Long.parseLong(matcher.group(1)));
        }
        return DEFAULT_RETRY_DELAY;
    }

    private void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmClientException("Interrupted while waiting to retry Gemini call", e);
        }
    }

    private ObjectNode buildRequestBody(List<ConversationTurn> history, List<ToolDefinition> tools) {
        ObjectNode root = objectMapper.createObjectNode();

        ArrayNode contents = root.putArray("contents");
        for (ConversationTurn turn : history) {
            contents.add(toContentNode(turn));
        }

        if (!tools.isEmpty()) {
            ArrayNode toolsArray = root.putArray("tools");
            ObjectNode toolEntry = toolsArray.addObject();
            ArrayNode functionDeclarations = toolEntry.putArray("functionDeclarations");
            for (ToolDefinition tool : tools) {
                ObjectNode decl = functionDeclarations.addObject();
                decl.put("name", tool.name());
                decl.put("description", tool.description());
                decl.set("parameters", objectMapper.valueToTree(tool.parameters()));
            }
        }

        return root;
    }

    private ObjectNode toContentNode(ConversationTurn turn) {
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode parts = objectMapper.createArrayNode();

        if (turn instanceof ConversationTurn.UserMessage userMessage) {
            content.put("role", "user");
            parts.addObject().put("text", userMessage.text());
        } else if (turn instanceof ConversationTurn.ModelText modelText) {
            content.put("role", "model");
            parts.addObject().put("text", modelText.text());
        } else if (turn instanceof ConversationTurn.ModelFunctionCall functionCall) {
            content.put("role", "model");
            ObjectNode functionCallNode = parts.addObject().putObject("functionCall");
            functionCallNode.put("name", functionCall.name());
            functionCallNode.set("args", objectMapper.valueToTree(functionCall.args()));
        } else if (turn instanceof ConversationTurn.FunctionResult functionResult) {
            // Gemini has no dedicated "tool" role in generateContent -
            // function results ride back in as a user turn. See
            // ConversationTurn.FunctionResult's javadoc.
            content.put("role", "user");
            ObjectNode functionResponse = parts.addObject().putObject("functionResponse");
            functionResponse.put("name", functionResult.name());
            functionResponse.set("response", objectMapper.valueToTree(functionResult.result()));
        } else {
            // Unreachable: ConversationTurn is sealed and permits exactly
            // the four types handled above. A compile error above (not this
            // line) is what protects against a fifth type going unhandled.
            throw new IllegalStateException("Unhandled ConversationTurn type: " + turn.getClass());
        }

        content.set("parts", parts);
        return content;
    }

    private ModelTurn parseResponse(JsonNode response) {
        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new LlmClientException("Gemini response had no candidates: " + response);
        }

        JsonNode firstPart = candidates.get(0).path("content").path("parts").path(0);

        if (firstPart.has("functionCall")) {
            JsonNode functionCall = firstPart.get("functionCall");
            String name = functionCall.path("name").asText();
            JsonNode argsNode = functionCall.path("args");
            Map<String, Object> args = argsNode.isMissingNode() || argsNode.isNull()
                    ? Map.of()
                    : objectMapper.convertValue(argsNode, new TypeReference<Map<String, Object>>() { });
            return new ConversationTurn.ModelFunctionCall(name, args);
        }

        if (firstPart.has("text")) {
            return new ConversationTurn.ModelText(firstPart.get("text").asText());
        }

        throw new LlmClientException("Gemini response part had neither text nor functionCall: " + firstPart);
    }

    private static ClientHttpRequestFactory clientRequestFactory(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (int) Duration.ofSeconds(timeoutSeconds).toMillis();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return factory;
    }
}
