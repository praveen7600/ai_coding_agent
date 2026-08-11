package com.praveen.aicodingagent.streaming;

import com.praveen.aicodingagent.task.TaskStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Bridges TaskStatusChangedEvent (domain) to TaskEvent (streaming wire
 * format). This is the only class that imports both packages.
 *
 * @TransactionalEventListener(AFTER_COMMIT) rather than a plain
 * @EventListener deliberately: TaskService.transitionStatus() publishes
 * inside an open @Transactional method. Firing on AFTER_COMMIT means a
 * client only ever sees a status event for a transition that actually
 * persisted - if the surrounding transaction rolls back for any reason, no
 * misleading event goes out. A plain @EventListener would fire synchronously
 * mid-transaction, before Spring even knows whether it will commit.
 */
@Component
@RequiredArgsConstructor
public class TaskStatusStreamListener {

    private final SseEmitterManager sseEmitterManager;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskStatusChanged(TaskStatusChangedEvent event) {
        TaskEvent taskEvent = TaskEvent.of(
                TaskEventType.TASK_STATUS,
                event.taskId(),
                Map.of(
                        "previousStatus", event.previousStatus().name(),
                        "newStatus", event.newStatus().name()
                )
        );
        sseEmitterManager.publish(taskEvent);

        if (event.newStatus().isTerminal()) {
            sseEmitterManager.complete(event.taskId());
        }
    }
}
