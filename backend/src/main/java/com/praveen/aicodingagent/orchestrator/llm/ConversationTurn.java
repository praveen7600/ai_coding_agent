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

    /**
     * The model deciding to call a tool instead of answering. Gemini role:
     * "model".
     *
     * thoughtSignature is nullable and opaque - Gemini's thinking models
     * (2.5/3.x series) attach it to a functionCall part and require it to
     * be echoed back byte-for-byte in the next request's history, or the
     * follow-up call fails with a 400 ("Function call is missing a
     * thought_signature"). Non-thinking models simply never send one, so
     * null here just means "nothing to round-trip" - see
     * GeminiClient#toContentNode and #parseResponse for where it's read
     * and written. The 2-arg constructor exists for callers (tests, mainly)
     * that build a ModelFunctionCall directly rather than parsing one out
     * of a real Gemini response and have no signature to preserve.
     *
     * Only the first functionCall part of a response carries a signature
     * when the model returns several in parallel - GeminiClient only reads
     * candidates[0].content.parts[0] today, so parallel function calls
     * aren't handled at all yet, signature or not. Out of scope for this
     * fix.
     */
    record ModelFunctionCall(String name, Map<String, Object> args, String thoughtSignature)
            implements ConversationTurn, ModelTurn {
        public ModelFunctionCall(String name, Map<String, Object> args) {
            this(name, args, null);
        }
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
