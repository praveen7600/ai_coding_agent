package com.praveen.aicodingagent.sandbox;

import com.praveen.aicodingagent.sandbox.runtime.StreamType;

import java.util.UUID;

/**
 * Published once per output chunk during SandboxManager.executeStreaming().
 * Same decoupling reasoning as TaskStatusChangedEvent: SandboxManager
 * publishes this without knowing or caring that SandboxLogStreamListener (in
 * the streaming package) is the thing forwarding it to an SSE client.
 *
 * Deliberately NOT @Transactional-published like TaskStatusChangedEvent -
 * exec output isn't tied to a DB transaction, and log lines need to reach
 * the client as they happen, not batched up and released on commit.
 */
public record SandboxLogLineEvent(
        UUID taskId,
        StreamType streamType,
        String chunk
) {
}
