package com.praveen.aicodingagent.task;

/**
 * Lifecycle states for an agent task.
 *
 * State machine (see docs/architecture/ADR-0002-task-state-machine.md):
 *
 *   PENDING --> RUNNING --> TOOL_CALLING --> RUNNING (loop) --> COMPLETED
 *                  |              |
 *                  v              v
 *               FAILED         FAILED
 *
 *   RUNNING / TOOL_CALLING --> CANCELLED (user-initiated)
 *
 * Only forward transitions (plus CANCELLED from any non-terminal state) are
 * legal. Illegal transitions are rejected in TaskService rather than relying
 * on callers to behave - this is enforced centrally so the Agent
 * Orchestrator can't corrupt task state via a buggy tool-calling loop.
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    TOOL_CALLING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
