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

    @DeleteMapping("/{taskId}")
    public void destroy(@PathVariable UUID taskId) {
        sandboxManager.destroy(taskId);
    }

    public record ExecRequest(List<String> command) {
    }
}
