package com.praveen.aicodingagent.streaming;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SseEmitterManagerTest {

    @Test
    void publishDeliversWithoutThrowingToAllSubscribersOfSameTask() {
        SseEmitterManager manager = new SseEmitterManager();
        UUID taskId = UUID.randomUUID();

        manager.subscribe(taskId);
        manager.subscribe(taskId);

        manager.publish(TaskEvent.of(TaskEventType.TASK_STATUS, taskId, Map.of("newStatus", "RUNNING")));

        assertThat(manager.activeTaskIds()).contains(taskId);
    }

    @Test
    void publishToUnknownTaskIsANoOp() {
        SseEmitterManager manager = new SseEmitterManager();
        // No subscriber ever registered for this taskId - publish must not throw.
        manager.publish(TaskEvent.of(TaskEventType.TASK_STATUS, UUID.randomUUID(), Map.of()));
    }

    @Test
    void completeClosesEmitterAndRemovesTaskFromActiveSet() {
        SseEmitterManager manager = new SseEmitterManager();
        UUID taskId = UUID.randomUUID();
        manager.subscribe(taskId);

        assertThat(manager.activeTaskIds()).contains(taskId);

        manager.complete(taskId);

        assertThat(manager.activeTaskIds()).doesNotContain(taskId);
    }

    @Test
    void heartbeatOnTaskWithNoSubscribersIsANoOp() {
        SseEmitterManager manager = new SseEmitterManager();
        manager.heartbeat(UUID.randomUUID());
    }
}
