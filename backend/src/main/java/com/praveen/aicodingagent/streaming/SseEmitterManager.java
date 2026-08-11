package com.praveen.aicodingagent.streaming;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns every open SseEmitter, keyed by taskId. This is the single place in
 * the codebase that touches SseEmitter directly - mirroring the same
 * "one port, many callers" shape as ContainerRuntime (see ADR-0003):
 * TaskStatusStreamListener and SandboxLogStreamListener both publish
 * through here, neither one manages emitters itself.
 *
 * Multiple emitters per taskId is deliberate, not an edge case to special
 * case away - the same task can be watched from more than one browser tab
 * or a curl session run alongside a UI.
 */
@Component
@Slf4j
public class SseEmitterManager {

    /**
     * No task should legitimately stream longer than this without the
     * client reconnecting - bounds a leaked emitter to a few hours instead
     * of forever, in case a completion/error callback is ever missed.
     */
    private static final long EMITTER_TIMEOUT_MS = 4 * 60 * 60 * 1000L; // 4 hours

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID taskId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        CopyOnWriteArrayList<SseEmitter> list = emitters.computeIfAbsent(taskId, id -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> {
            log.debug("SSE emitter for task {} timed out", taskId);
            remove(taskId, emitter);
        });
        emitter.onError(ex -> {
            log.debug("SSE emitter for task {} errored: {}", taskId, ex.toString());
            remove(taskId, emitter);
        });

        // First frame on connect. EventSource treats plain data with no
        // event: name as a generic "message" event on the client, useful as
        // an immediate ack that the stream is live before anything else
        // happens.
        try {
            emitter.send(SseEmitter.event().name("connected").data("subscribed"));
        } catch (IOException e) {
            remove(taskId, emitter);
        }

        return emitter;
    }

    /** Sends an event to every currently-subscribed emitter for a task. */
    public void publish(TaskEvent event) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(event.taskId());
        if (list == null || list.isEmpty()) {
            return;
        }
        // Snapshot avoids mutating the list we're iterating - remove() below
        // does its own CopyOnWriteArrayList.remove(), which is safe against
        // this iteration precisely because it's copy-on-write.
        for (SseEmitter emitter : List.copyOf(list)) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.type().name())
                        .data(event));
            } catch (IOException | IllegalStateException e) {
                // Client disconnected or emitter already completed - stop
                // tracking it rather than retry, the client will reconnect
                // and re-subscribe if it's still interested.
                remove(event.taskId(), emitter);
            }
        }
    }

    /**
     * Sends a final DONE event and closes every emitter for a task. Called
     * once a task reaches a terminal state - after this, no further events
     * for the task are expected, so holding the connection open serves no
     * purpose.
     */
    public void complete(UUID taskId) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(taskId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : List.copyOf(list)) {
            try {
                emitter.send(SseEmitter.event().name(TaskEventType.DONE.name()).data("done"));
            } catch (IOException | IllegalStateException ignored) {
                // already gone - completing it below is still safe/idempotent
            }
            emitter.complete();
        }
        emitters.remove(taskId);
    }

    /** All taskIds with at least one open emitter - used by the heartbeat scheduler. */
    public Set<UUID> activeTaskIds() {
        return Set.copyOf(emitters.keySet());
    }

    /** Sends a comment-only heartbeat frame, invisible to EventSource's onmessage, to keep proxies from timing out an idle connection. */
    public void heartbeat(UUID taskId) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(taskId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : List.copyOf(list)) {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException e) {
                remove(taskId, emitter);
            }
        }
    }

    private void remove(UUID taskId, SseEmitter emitter) {
        emitters.computeIfPresent(taskId, (id, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}
