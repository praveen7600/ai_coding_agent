package com.praveen.aicodingagent.task;

import com.praveen.aicodingagent.auth.UserPrincipal;
import com.praveen.aicodingagent.orchestrator.AgentOrchestrator;
import com.praveen.aicodingagent.task.dto.CreateTaskRequest;
import com.praveen.aicodingagent.task.dto.TaskResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/**
 * userId now comes from the JWT-authenticated principal (Auth & User
 * Service milestone), not a client-supplied header. TaskService's method
 * signatures didn't change - they already took userId as a parameter, so
 * this ended up being a controller-only change, exactly as planned in
 * Milestone 1.
 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final AgentOrchestrator agentOrchestrator;

    @PostMapping
    public ResponseEntity<TaskResponse> createTask(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateTaskRequest request
    ) {
        Task task = taskService.createTask(principal.getId(), request);
        return ResponseEntity
                .created(URI.create("/api/tasks/" + task.getId()))
                .body(TaskResponse.from(task));
    }

    @GetMapping("/{id}")
    public TaskResponse getTask(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id
    ) {
        return TaskResponse.from(taskService.getTaskForUser(id, principal.getId()));
    }

    @GetMapping
    public Page<TaskResponse> listTasks(
            @AuthenticationPrincipal UserPrincipal principal,
            Pageable pageable
    ) {
        return taskService.listTasksForUser(principal.getId(), pageable).map(TaskResponse::from);
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.OK)
    public TaskResponse cancelTask(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id
    ) {
        return TaskResponse.from(taskService.cancel(id, principal.getId()));
    }

    /**
     * Starts the agent loop for a task. The PENDING -> RUNNING transition
     * happens synchronously here, deliberately, before AgentOrchestrator.run()
     * is handed off to its own thread pool (see AsyncConfig): if the task is
     * already running, done, or doesn't belong to this user, the caller gets
     * that error back on this request instead of it being silently lost
     * inside a fire-and-forget @Async void method. Everything past this
     * point in the task's lifecycle - TOOL_CALLING/RUNNING cycles,
     * COMPLETED/FAILED - is watched over the SSE stream (GET
     * /api/tasks/{id}/stream), not this response.
     */
    @PostMapping("/{id}/execute")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskResponse executeTask(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id
    ) {
        Task task = taskService.transitionStatus(id, principal.getId(), TaskStatus.RUNNING);
        agentOrchestrator.run(id);
        return TaskResponse.from(task);
    }
}
