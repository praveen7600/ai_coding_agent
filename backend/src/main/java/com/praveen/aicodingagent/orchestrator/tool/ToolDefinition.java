package com.praveen.aicodingagent.orchestrator.tool;

import java.util.Map;

/**
 * One tool the agent can call. `parameters` is a JSON-Schema-shaped map
 * (type/properties/required) matching Gemini's functionDeclarations.parameters
 * field directly - GeminiClient serializes it as-is rather than a typed
 * schema class, since the schema shape is Gemini's contract, not ours, and a
 * typed wrapper here would just be a JSON-Schema reimplementation for no
 * benefit.
 *
 * Only the shape needed for step 2 (GeminiClient can serialize this into a
 * request) - ToolExecutor, which maps a tool name to an actual
 * SandboxManager call, is a later step.
 */
public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> parameters
) {
}
