package com.praveen.aicodingagent.orchestrator.llm;

/**
 * What LlmClient.generate() can hand back: either the model answered in
 * text, or it wants a tool called. Implemented by ConversationTurn.ModelText
 * and ConversationTurn.ModelFunctionCall (not by UserMessage/FunctionResult -
 * those are things we send, never something the model returns).
 *
 * The orchestrator loop (a later step) will pattern-match on this with a
 * switch expression over ModelText vs ModelFunctionCall to decide whether
 * the task is done or a tool needs to run.
 */
public sealed interface ModelTurn permits ConversationTurn.ModelText, ConversationTurn.ModelFunctionCall {
}
