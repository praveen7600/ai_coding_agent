package com.praveen.aicodingagent.streaming;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Without this, an idle SSE connection (a task sitting in PENDING, or one
 * between log lines) gets silently killed by most load balancers and some
 * browsers after 30-60s of no bytes on the wire - and the failure looks
 * like nothing happened, not like an error, which makes it miserable to
 * debug. A comment-only frame every 20s keeps bytes flowing without the
 * client's onmessage handler ever seeing it.
 */
@Component
@RequiredArgsConstructor
public class SseHeartbeatScheduler {

    private final SseEmitterManager sseEmitterManager;

    @Scheduled(fixedRateString = "${streaming.heartbeat-interval-ms:20000}")
    public void sendHeartbeats() {
        sseEmitterManager.activeTaskIds().forEach(sseEmitterManager::heartbeat);
    }
}
