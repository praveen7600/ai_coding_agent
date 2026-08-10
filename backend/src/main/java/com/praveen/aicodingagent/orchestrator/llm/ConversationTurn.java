package com.praveen.aicodingagent.orchestrator.llm;

import java.util.Map;

/**
 * Everything that can appear in a conversation's history, sent as `contents`
 * to Gemini. Sealed rather than a single class with a "type" enum field so
 * the compiler enforces exhaustive handling everywhere a ConversationTurn is
 * matched (GeminiClient's request serializer, and later the orchestrator
 * loop's response branch) - if a fifth turn type is ever added, every switch
 * over it fails to compile until handled, instead of silently doing nothing
 * at runtime.
 *
 * ModelText and ModelFunctionCall also implement ModelTurn (see that file) -
 * this lets generate()'s return value be appended straight into a
 * List<ConversationTurn> for the next call without a conversion step.
 */
public sealed interface ConversationTurn
        permits ConversationTurn.UserMessage, ConversationTurn.ModelText,
        ConversationTurn.ModelFunctionCall, ConversationTurn.FunctionResult {

    /** A message from the user/task prompt. Gemini role: "user". */
    record UserMessage(String text) implements ConversationTurn {
    }

    /** The model's final text answer for this turn. Gemini role: "model". */
    record ModelText(String text) implements ConversationTurn, ModelTurn {
    }

    /** The model deciding to call a tool instead of answering. Gemini role: "model". */
    record ModelFunctionCall(String name, Map<String, Object> args) implements ConversationTurn, ModelTurn {
    }

    /**
     * The result of a tool we executed, sent back so the model can react to
     * it. Gemini role: "user" (Gemini has no dedicated "tool" role in
     * generateContent - function results ride in as a user turn with a
     * functionResponse part, which trips people up the first time).
     */
    record FunctionResult(String name, Map<String, Object> result) implements ConversationTurn {
    }
}
