# ADR-0002: Centralized task state machine

## Status
Accepted

## Context
A Task moves through PENDING -> RUNNING -> TOOL_CALLING -> (RUNNING loop) ->
COMPLETED/FAILED, with CANCELLED reachable from any non-terminal state. This
transition logic will eventually be triggered from several places: the REST
API (user cancels), the Agent Orchestrator (loop advances the task), and the
Sandbox Manager (a tool execution fails).

## Decision
All transitions go through `TaskService.transitionStatus`, validated against
a static `Map<TaskStatus, Set<TaskStatus>>` of legal moves. Callers never set
`task.setStatus(...)` directly outside the service.

## Alternatives considered
- **Spring State Machine library**: more powerful (guards, actions, side
  effects as first-class citizens) but heavyweight for a state machine this
  small, and it's harder to explain/defend in an interview than 20 lines of
  Java. Revisit if the orchestrator's states grow substantially (e.g.
  sub-states per tool type).
- **Scattered validation** (each caller checks before setting status): ruled
  out - this is exactly the kind of bug class where a Sandbox Manager retry
  path could silently push a COMPLETED task back to RUNNING.

## Consequences
- Adding a new status requires updating one map, not hunting through
  callers.
- The map is trivially unit-testable in isolation from HTTP/DB concerns.
- If transition logic grows conditional side effects (e.g. "on
  COMPLETED, also destroy the sandbox container"), that becomes an
  explicit next decision rather than something bolted onto the map.
