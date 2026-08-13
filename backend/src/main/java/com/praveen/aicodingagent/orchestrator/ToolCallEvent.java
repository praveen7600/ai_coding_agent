package com.praveen.aicodingagent.orchestrator;

import java.util.Map;
import java.util.UUID;

/**
 * Published by AgentOrchestrator right before a tool call is handed to
 * ToolExecutor. Same decoupling reasoning as TaskStatusChangedEvent and
 * SandboxLogLineEvent: AgentOrchestrator has no dependency on
 * SseEmitterManager or anything in the streaming package, it just publishes
 * this and moves on. The streaming package owns turning it into the
 * TaskEventType.TOOL_CALL wire event that's been reserved (unused) since
 * Milestone 4.
 *
 * Deliberately NOT @Transactional-published like TaskStatusChangedEvent -
 * "the agent decided to call a tool" isn't itself a DB write, and a client
 * watching the stream should see it the moment it happens, not after
 * whatever transaction happens to be open next commits.
 */
public record ToolCallEvent(
        UUID taskId,
        String toolName,
        Map<String, Object> args
) {
}
