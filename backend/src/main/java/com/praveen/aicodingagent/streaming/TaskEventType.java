package com.praveen.aicodingagent.streaming;

/**
 * Closed set of event types a client can receive on the task SSE stream.
 * Kept deliberately small - this is the contract the frontend and, later,
 * the Agent Orchestrator's tool-calling loop code against, so it should
 * change rarely and on purpose.
 *
 * TOOL_CALL is reserved now (not yet emitted anywhere) because the
 * Orchestrator will need it for "agent invoked tool X with args Y" events,
 * and it's cheaper to have the contract stable today than to add a new SSE
 * event name once a frontend already exists.
 */
public enum TaskEventType {
    TASK_STATUS,
    LOG_LINE,
    TOOL_CALL,
    ERROR,
    DONE
}
