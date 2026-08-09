package com.praveen.aicodingagent.streaming;

import com.praveen.aicodingagent.sandbox.SandboxLogLineEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Bridges SandboxLogLineEvent (domain) to TaskEvent (streaming wire format).
 *
 * Plain @EventListener, not @TransactionalEventListener - unlike task status
 * changes, exec output isn't published inside a DB transaction (see
 * SandboxLogLineEvent's javadoc), so there's no commit to wait for. Waiting
 * would just add latency to something that's supposed to be live.
 */
@Component
@RequiredArgsConstructor
public class SandboxLogStreamListener {

    private final SseEmitterManager sseEmitterManager;

    @EventListener
    public void onSandboxLogLine(SandboxLogLineEvent event) {
        TaskEvent taskEvent = TaskEvent.of(
                TaskEventType.LOG_LINE,
                event.taskId(),
                Map.of(
                        "stream", event.streamType().name(),
                        "chunk", event.chunk()
                )
        );
        sseEmitterManager.publish(taskEvent);
    }
}
