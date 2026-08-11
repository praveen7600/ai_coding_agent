package com.praveen.aicodingagent.orchestrator.llm;

import com.praveen.aicodingagent.orchestrator.tool.ToolDefinition;

import java.util.List;

/**
 * Port the orchestrator loop depends on - same shape as ContainerRuntime
 * (ADR-0003): the loop knows nothing about Gemini, HTTP, or JSON, only this
 * interface. GeminiClient is the one adapter today; swapping providers later,
 * or writing a FakeLlmClient for the orchestrator loop's own unit tests,
 * means implementing this interface and nothing else.
 */
public interface LlmClient {

    /**
     * Sends the full conversation so far plus the tools the model is allowed
     * to call, and returns what the model wants to do next: answer in text,
     * or call one tool.
     */
    ModelTurn generate(List<ConversationTurn> history, List<ToolDefinition> tools);
}
