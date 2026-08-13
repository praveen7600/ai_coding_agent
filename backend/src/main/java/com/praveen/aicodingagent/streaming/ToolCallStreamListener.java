package com.praveen.aicodingagent.streaming;

import com.praveen.aicodingagent.orchestrator.ToolCallEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Bridges ToolCallEvent (domain) to TaskEvent (streaming wire format) -
 * the payoff of TaskEventType.TOOL_CALL having been reserved back in
 * Milestone 4: no new SSE event name needed now that the orchestrator loop
 * actually calls tools, just a new listener wiring the existing type up.
 *
 * Plain @EventListener, not @TransactionalEventListener, for the same
 * reason as SandboxLogStreamListener: ToolCallEvent isn't published inside
 * a DB transaction, so there's no commit to wait for.
 */
@Component
@RequiredArgsConstructor
public class ToolCallStreamListener {

    private final SseEmitterManager sseEmitterManager;

    @EventListener
    public void onToolCall(ToolCallEvent event) {
        TaskEvent taskEvent = TaskEvent.of(
                TaskEventType.TOOL_CALL,
                event.taskId(),
                Map.of(
                        "toolName", event.toolName(),
                        "args", event.args()
                )
        );
        sseEmitterManager.publish(taskEvent);
    }
}
