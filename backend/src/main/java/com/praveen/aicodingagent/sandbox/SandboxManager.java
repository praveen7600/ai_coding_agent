package com.praveen.aicodingagent.sandbox;

import com.praveen.aicodingagent.sandbox.runtime.ContainerRuntime;
import com.praveen.aicodingagent.sandbox.runtime.ContainerSpec;
import com.praveen.aicodingagent.sandbox.runtime.ExecResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the "create a new container / reuse an existing one / destroy on
 * completion or timeout" rules from the architecture diagram. Talks only to
 * the ContainerRuntime port, never to docker-java directly (see
 * ADR-0003).
 */
@Service
@Slf4j
public class SandboxManager {

    private static final List<SandboxStatus> ACTIVE_STATUSES = List.of(SandboxStatus.CREATING, SandboxStatus.RUNNING);

    private final SandboxRepository sandboxRepository;
    private final ContainerRuntime containerRuntime;
    private final SandboxProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    public SandboxManager(SandboxRepository sandboxRepository, ContainerRuntime containerRuntime,
                           SandboxProperties properties, ApplicationEventPublisher eventPublisher) {
        this.sandboxRepository = sandboxRepository;
        this.containerRuntime = containerRuntime;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Returns a running sandbox assigned to this task, reusing one if it's
     * still alive, otherwise provisioning a fresh container.
     *
     * Race handling: two near-simultaneous calls for the same task both try
     * to insert a CREATING row. The partial unique index on
     * sandboxes(task_id) WHERE status IN ('CREATING','RUNNING') lets exactly
     * one insert succeed; the loser catches DataIntegrityViolationException
     * and polls for the winner's row to finish provisioning instead of
     * creating a second container.
     */
    public SandboxContainer getOrCreateSandbox(UUID taskId) {
        return sandboxRepository.findByTaskIdAndStatusIn(taskId, ACTIVE_STATUSES)
                .map(existing -> reuseIfHealthy(existing, taskId))
                .orElseGet(() -> provisionNewSandbox(taskId));
    }

    private SandboxContainer reuseIfHealthy(SandboxContainer existing, UUID taskId) {
        if (existing.getStatus() == SandboxStatus.CREATING) {
            // Another request is provisioning it right now - wait rather than race it.
            return waitForProvisioning(taskId);
        }
        if (containerRuntime.isRunning(existing.getContainerId())) {
            existing.touch();
            return sandboxRepository.save(existing);
        }
        // DB says RUNNING but Docker disagrees (container died/was killed out-of-band).
        // Mark it dead and provision a replacement rather than handing back a broken sandbox.
        log.warn("Sandbox {} for task {} marked RUNNING but Docker reports it is not - reprovisioning", existing.getId(), taskId);
        existing.setStatus(SandboxStatus.FAILED);
        existing.setDestroyedAt(Instant.now());
        sandboxRepository.save(existing);
        return provisionNewSandbox(taskId);
    }

    /**
     * Deliberately NOT @Transactional. Two reasons:
     *  1. It's called from getOrCreateSandbox() within the same class - a
     *     Spring AOP proxy can't intercept that self-invocation, so the
     *     annotation would silently do nothing anyway (a classic gotcha
     *     worth knowing, not just avoiding by accident).
     *  2. Even if it could, we don't want one transaction spanning the DB
     *     insert AND the slow Docker create/start calls - the insert commits
     *     immediately (saveAndFlush) so the unique-index race guard is live
     *     before we ever talk to Docker.
     */
    private SandboxContainer provisionNewSandbox(UUID taskId) {
        SandboxContainer record = SandboxContainer.builder()
                .taskId(taskId)
                .image(properties.image())
                .status(SandboxStatus.CREATING)
                .build();

        try {
            record = sandboxRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException raceLost) {
            // Someone else's insert won the unique index race in the gap
            // between our findBy... check and this insert.
            return waitForProvisioning(taskId);
        }

        try {
            ContainerSpec spec = ContainerSpec.builder()
                    .image(properties.image())
                    .containerName("sandbox-" + taskId)
                    .memoryBytes(properties.memoryBytes())
                    .cpuNanos(properties.cpuNanos())
                    .pidsLimit(properties.pidsLimit())
                    .networkMode(properties.networkMode())
                    .workingDir(properties.workspaceDir())
                    .env(Map.of("TASK_ID", taskId.toString()))
                    .labels(Map.of("app", "ai-coding-agent", "task-id", taskId.toString()))
                    .entrypoint(List.of("tail", "-f", "/dev/null")) // keep container alive between exec calls
                    .build();

            String containerId = containerRuntime.createContainer(spec);
            containerRuntime.startContainer(containerId);

            record.setContainerId(containerId);
            record.setStatus(SandboxStatus.RUNNING);
            return sandboxRepository.save(record);
        } catch (Exception e) {
            record.setStatus(SandboxStatus.FAILED);
            record.setDestroyedAt(Instant.now());
            sandboxRepository.save(record);
            throw new SandboxException("Failed to provision sandbox for task " + taskId, e);
        }
    }

    /**
     * Polls the DB for a concurrently-created sandbox to leave CREATING.
     * Simple bounded poll rather than a notification mechanism - acceptable
     * because this only fires on the (rare) exact-same-instant race, not on
     * the common path.
     */
    private SandboxContainer waitForProvisioning(UUID taskId) {
        int attempts = 0;
        while (attempts < 20) { // ~10s ceiling at 500ms intervals
            SandboxContainer current = sandboxRepository.findByTaskIdAndStatusIn(taskId, ACTIVE_STATUSES)
                    .orElseThrow(() -> new SandboxException("Concurrent sandbox provisioning for task " + taskId + " disappeared"));
            if (current.getStatus() == SandboxStatus.RUNNING) {
                return current;
            }
            sleep();
            attempts++;
        }
        throw new SandboxException("Timed out waiting for concurrent sandbox provisioning for task " + taskId);
    }

    private void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxException("Interrupted while waiting for sandbox provisioning", e);
        }
    }

    public ExecResult execute(UUID taskId, List<String> command) {
        SandboxContainer sandbox = getOrCreateSandbox(taskId);
        ExecResult result = containerRuntime.exec(sandbox.getContainerId(), command, properties.workspaceDir());
        sandbox.touch();
        sandboxRepository.save(sandbox);
        return result;
    }

    /**
     * Same as execute(), but publishes a SandboxLogLineEvent per output
     * chunk as the command runs, instead of only returning the aggregated
     * result at the end. Still returns the full ExecResult on completion -
     * this is what the /internal/sandboxes/{taskId}/exec-stream endpoint
     * calls, so the HTTP response carries the final exit code/output while
     * anyone subscribed to /api/tasks/{taskId}/stream sees it arrive live.
     */
    public ExecResult executeStreaming(UUID taskId, List<String> command) {
        SandboxContainer sandbox = getOrCreateSandbox(taskId);
        ExecResult result = containerRuntime.execStreaming(
                sandbox.getContainerId(),
                command,
                properties.workspaceDir(),
                (streamType, chunk) -> eventPublisher.publishEvent(new SandboxLogLineEvent(taskId, streamType, chunk))
        );
        sandbox.touch();
        sandboxRepository.save(sandbox);
        return result;
    }

    @Transactional
    public void destroy(UUID taskId) {
        sandboxRepository.findByTaskIdAndStatusIn(taskId, ACTIVE_STATUSES)
                .ifPresent(this::destroySandbox);
    }

    private void destroySandbox(SandboxContainer sandbox) {
        if (sandbox.getContainerId() != null) {
            containerRuntime.stopContainer(sandbox.getContainerId());
            containerRuntime.removeContainer(sandbox.getContainerId());
        }
        sandbox.setStatus(SandboxStatus.DESTROYED);
        sandbox.setDestroyedAt(Instant.now());
        sandboxRepository.save(sandbox);
        log.info("Destroyed sandbox {} for task {}", sandbox.getId(), sandbox.getTaskId());
    }

    /**
     * Reaps sandboxes nobody has touched in idleTimeoutMinutes. This is
     * what keeps a crashed/abandoned Agent Orchestrator loop from leaking
     * containers on the host indefinitely.
     */
    @Scheduled(fixedDelayString = "${sandbox.reaper-interval-ms:60000}")
    @Transactional
    public void reapIdleSandboxes() {
        Instant cutoff = Instant.now().minus(properties.idleTimeoutMinutes(), ChronoUnit.MINUTES);
        List<SandboxContainer> stale = sandboxRepository.findByStatusAndLastActivityAtBefore(SandboxStatus.RUNNING, cutoff);
        if (stale.isEmpty()) {
            return;
        }
        log.info("Reaping {} idle sandbox(es) older than {} minutes", stale.size(), properties.idleTimeoutMinutes());
        stale.forEach(this::destroySandbox);
    }
}
