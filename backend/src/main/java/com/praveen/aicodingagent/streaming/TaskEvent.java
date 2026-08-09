package com.praveen.aicodingagent.streaming;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A single message pushed down the SSE stream for a task. `data` is a small
 * free-form map rather than a typed class per event type - the SSE wire
 * format is already JSON text, and a single shape keeps SseEmitterManager
 * and StreamController from needing to know about every event's internal
 * structure. Producers (TaskStatusStreamListener, SandboxLogStreamListener)
 * own what goes in `data` for the event type they emit.
 */
public record TaskEvent(
        TaskEventType type,
        UUID taskId,
        Instant timestamp,
        Map<String, Object> data
) {
    public static TaskEvent of(TaskEventType type, UUID taskId, Map<String, Object> data) {
        return new TaskEvent(type, taskId, Instant.now(), data);
    }
}
