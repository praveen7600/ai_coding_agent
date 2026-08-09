package com.praveen.aicodingagent.sandbox;

import com.praveen.aicodingagent.sandbox.runtime.ExecResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Manual test surface for the Sandbox Manager, ahead of the Agent
 * Orchestrator existing to drive it. Not meant to survive to production as
 * public API - the Orchestrator will call SandboxManager directly, in
 * process, once it lands. Kept under /internal as a signal of that.
 *
 * Requires authentication (any valid user, via the global JWT filter chain)
 * but does not yet check that the caller owns the given taskId - that
 * ownership check belongs in the Orchestrator layer, which will already
 * have looked the task up via TaskService (and thus already knows the
 * owner) before it ever touches the sandbox for it.
 */
@RestController
@RequestMapping("/internal/sandboxes")
@RequiredArgsConstructor
public class SandboxController {

    private final SandboxManager sandboxManager;

    @PostMapping("/{taskId}/ensure")
    public SandboxContainer ensure(@PathVariable UUID taskId) {
        return sandboxManager.getOrCreateSandbox(taskId);
    }

    @PostMapping("/{taskId}/exec")
    public ExecResult exec(@PathVariable UUID taskId, @RequestBody ExecRequest request) {
        return sandboxManager.execute(taskId, request.command());
    }

    /**
     * Same command execution as /exec, but output chunks are published as
     * LOG_LINE events on the task's SSE stream (/api/tasks/{taskId}/stream)
     * as they arrive, instead of only being visible in this response once
     * the command finishes. This response still carries the final
     * ExecResult - subscribe to the stream separately (e.g. curl -N) before
     * calling this to see output live rather than all at once at the end.
     */
    @PostMapping("/{taskId}/exec-stream")
    public ExecResult execStream(@PathVariable UUID taskId, @RequestBody ExecRequest request) {
        return sandboxManager.executeStreaming(taskId, request.command());
    }

    @DeleteMapping("/{taskId}")
    public void destroy(@PathVariable UUID taskId) {
        sandboxManager.destroy(taskId);
    }

    public record ExecRequest(List<String> command) {
    }
}
