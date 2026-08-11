package com.praveen.aicodingagent.streaming;

import com.praveen.aicodingagent.auth.UserPrincipal;
import com.praveen.aicodingagent.task.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Lives under /api/tasks, not a separate top-level path, because a stream is
 * a view onto one task's lifecycle - same auth and ownership rules as every
 * other /api/tasks/{id} endpoint apply here too.
 *
 * Ownership is checked explicitly via taskService.getTaskForUser() before
 * subscribing, same "throw as 404, not 403" rule as the rest of
 * TaskController - a stranger's taskId should look nonexistent, not
 * forbidden, whether they're calling GET or opening an EventSource.
 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class StreamController {

    private final TaskService taskService;
    private final SseEmitterManager sseEmitterManager;

    @GetMapping(value = "/{id}/stream", produces = "text/event-stream")
    public SseEmitter stream(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id
    ) {
        // Throws TaskNotFoundException (-> 404) if the task doesn't exist or
        // isn't owned by this user - same guard as every other task endpoint.
        taskService.getTaskForUser(id, principal.getId());
        return sseEmitterManager.subscribe(id);
    }
}
