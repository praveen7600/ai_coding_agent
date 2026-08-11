package com.praveen.aicodingagent.task;

import java.util.UUID;

/**
 * Published by TaskService.transitionStatus() on every legal transition.
 *
 * TaskService has no dependency on SseEmitterManager or anything in the
 * streaming package - it publishes this via Spring's ApplicationEventPublisher
 * and doesn't know or care whether anything is listening. Same reasoning as
 * ContainerRuntime being a port SandboxManager depends on rather than
 * importing docker-java directly (ADR-0003): the task state machine is core
 * domain logic, streaming is a delivery mechanism for observing it, and the
 * two shouldn't be compiled together. Deleting the streaming package
 * entirely would leave TaskService compiling and working exactly as before.
 */
public record TaskStatusChangedEvent(
        UUID taskId,
        UUID userId,
        TaskStatus previousStatus,
        TaskStatus newStatus
) {
}
