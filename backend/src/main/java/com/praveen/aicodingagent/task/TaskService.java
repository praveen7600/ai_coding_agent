package com.praveen.aicodingagent.task;

import com.praveen.aicodingagent.task.dto.CreateTaskRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskService {

    /**
     * Legal forward transitions. Anything not listed here is rejected.
     * Kept as data (not a chain of if/else) so the rules are visible in one
     * place and unit-testable without touching the service logic itself.
     */
    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(TaskStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(TaskStatus.PENDING, EnumSet.of(TaskStatus.RUNNING, TaskStatus.CANCELLED));
        ALLOWED_TRANSITIONS.put(TaskStatus.RUNNING, EnumSet.of(TaskStatus.TOOL_CALLING, TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED));
        ALLOWED_TRANSITIONS.put(TaskStatus.TOOL_CALLING, EnumSet.of(TaskStatus.RUNNING, TaskStatus.FAILED, TaskStatus.CANCELLED));
        ALLOWED_TRANSITIONS.put(TaskStatus.COMPLETED, EnumSet.noneOf(TaskStatus.class));
        ALLOWED_TRANSITIONS.put(TaskStatus.FAILED, EnumSet.noneOf(TaskStatus.class));
        ALLOWED_TRANSITIONS.put(TaskStatus.CANCELLED, EnumSet.noneOf(TaskStatus.class));
    }

    private final TaskRepository taskRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Task createTask(UUID userId, CreateTaskRequest request) {
        Task task = Task.builder()
                .userId(userId)
                .title(request.title())
                .description(request.description())
                .repoUrl(request.repoUrl())
                .status(TaskStatus.PENDING)
                .build();
        return taskRepository.save(task);
    }

    @Transactional(readOnly = true)
    public Task getTaskForUser(UUID taskId, UUID userId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        if (!task.getUserId().equals(userId)) {
            // Deliberately thrown as "not found", not "forbidden" - do not
            // reveal to a caller that a task id belonging to someone else
            // exists.
            throw new TaskNotFoundException(taskId);
        }
        return task;
    }

    @Transactional(readOnly = true)
    public Page<Task> listTasksForUser(UUID userId, Pageable pageable) {
        return taskRepository.findByUserId(userId, pageable);
    }

    @Transactional
    public Task transitionStatus(UUID taskId, UUID userId, TaskStatus newStatus) {
        Task task = getTaskForUser(taskId, userId);
        TaskStatus current = task.getStatus();

        Set<TaskStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(newStatus)) {
            throw new InvalidTaskStateTransitionException(current, newStatus);
        }

        task.setStatus(newStatus);
        eventPublisher.publishEvent(new TaskStatusChangedEvent(taskId, userId, current, newStatus));
        return task; // dirty-checked by JPA within the transaction, no explicit save needed
    }

    @Transactional
    public Task cancel(UUID taskId, UUID userId) {
        return transitionStatus(taskId, userId, TaskStatus.CANCELLED);
    }

    /**
     * Terminal transition used by AgentOrchestrator when the model answers
     * in text instead of calling a tool. Reuses transitionStatus rather than
     * setting the status field directly so COMPLETED still goes through the
     * same ALLOWED_TRANSITIONS check and TaskStatusChangedEvent publish as
     * every other transition - the orchestrator loop gets no special path
     * around the state machine.
     */
    @Transactional
    public Task completeWithResult(UUID taskId, UUID userId, String resultSummary) {
        Task task = transitionStatus(taskId, userId, TaskStatus.COMPLETED);
        task.setResultSummary(resultSummary);
        return task; // dirty-checked alongside the status change, same transaction
    }

    /**
     * Terminal transition used by AgentOrchestrator when the loop exhausts
     * its iteration budget or an unrecoverable error (LLM call, sandbox
     * infra) stops it. Can itself throw InvalidTaskStateTransitionException
     * if the task already reached a terminal state some other way (e.g. a
     * user-initiated cancel raced the loop) - callers in the orchestrator
     * are expected to treat that as "nothing to do", not a new failure.
     */
    @Transactional
    public Task failWithReason(UUID taskId, UUID userId, String reason) {
        Task task = transitionStatus(taskId, userId, TaskStatus.FAILED);
        task.setResultSummary(reason);
        return task;
    }
}
