package com.praveen.aicodingagent.task;

import com.praveen.aicodingagent.task.dto.CreateTaskRequest;
import com.praveen.aicodingagent.task.dto.TaskResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/**
 * NOTE ON AUTH: the Auth & User Service milestone hasn't landed yet, so
 * `userId` is taken directly from a request header instead of a JWT
 * principal. Every method already threads userId through TaskService so
 * that swapping the header for `@AuthenticationPrincipal` later is a
 * controller-only change - the service layer doesn't need to know how the
 * caller was identified.
 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @PostMapping
    public ResponseEntity<TaskResponse> createTask(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody CreateTaskRequest request
    ) {
        Task task = taskService.createTask(userId, request);
        return ResponseEntity
                .created(URI.create("/api/tasks/" + task.getId()))
                .body(TaskResponse.from(task));
    }

    @GetMapping("/{id}")
    public TaskResponse getTask(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID id
    ) {
        return TaskResponse.from(taskService.getTaskForUser(id, userId));
    }

    @GetMapping
    public Page<TaskResponse> listTasks(
            @RequestHeader("X-User-Id") UUID userId,
            Pageable pageable
    ) {
        return taskService.listTasksForUser(userId, pageable).map(TaskResponse::from);
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.OK)
    public TaskResponse cancelTask(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID id
    ) {
        return TaskResponse.from(taskService.cancel(id, userId));
    }
}
