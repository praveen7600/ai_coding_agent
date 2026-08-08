package com.praveen.aicodingagent.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SandboxManagerTest {

    @Mock
    private SandboxRepository sandboxRepository;

    private FakeContainerRuntime containerRuntime;
    private SandboxManager sandboxManager;

    private static final SandboxProperties PROPERTIES = new SandboxProperties(
            "unix:///var/run/docker.sock",
            "ai-coding-agent-sandbox:latest",
            "/workspace",
            1024,
            1.0,
            256,
            "bridge",
            120,
            30
    );

    @BeforeEach
    void setUp() {
        containerRuntime = new FakeContainerRuntime();
        sandboxManager = new SandboxManager(sandboxRepository, containerRuntime, PROPERTIES);
    }

    private SandboxContainer activeSandbox(UUID taskId, String containerId, SandboxStatus status) {
        return SandboxContainer.builder()
                .id(UUID.randomUUID())
                .taskId(taskId)
                .containerId(containerId)
                .image(PROPERTIES.image())
                .status(status)
                .createdAt(Instant.now())
                .lastActivityAt(Instant.now())
                .build();
    }

    @Test
    void createsNewSandboxWhenNoneExists() {
        UUID taskId = UUID.randomUUID();
        when(sandboxRepository.findByTaskIdAndStatusIn(eq(taskId), any())).thenReturn(Optional.empty());
        when(sandboxRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sandboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SandboxContainer result = sandboxManager.getOrCreateSandbox(taskId);

        assertThat(result.getStatus()).isEqualTo(SandboxStatus.RUNNING);
        assertThat(result.getContainerId()).isNotNull();
        assertThat(containerRuntime.isRunning(result.getContainerId())).isTrue();
    }

    @Test
    void reusesSandboxStillRunningInDocker() {
        UUID taskId = UUID.randomUUID();
        String containerId = containerRuntime.createContainer(null);
        containerRuntime.startContainer(containerId);
        SandboxContainer existing = activeSandbox(taskId, containerId, SandboxStatus.RUNNING);

        when(sandboxRepository.findByTaskIdAndStatusIn(eq(taskId), any())).thenReturn(Optional.of(existing));
        when(sandboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SandboxContainer result = sandboxManager.getOrCreateSandbox(taskId);

        assertThat(result.getContainerId()).isEqualTo(containerId);
        // reused, not recreated
        verify(sandboxRepository, never()).saveAndFlush(any());
    }

    @Test
    void reprovisionsWhenDbSaysRunningButDockerDisagrees() {
        UUID taskId = UUID.randomUUID();
        String deadContainerId = "fake-container-dead";
        SandboxContainer existing = activeSandbox(taskId, deadContainerId, SandboxStatus.RUNNING);
        // Note: never started in containerRuntime, so isRunning() is false - simulates drift.

        when(sandboxRepository.findByTaskIdAndStatusIn(eq(taskId), any())).thenReturn(Optional.of(existing));
        when(sandboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sandboxRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        SandboxContainer result = sandboxManager.getOrCreateSandbox(taskId);

        assertThat(result.getContainerId()).isNotEqualTo(deadContainerId);
        assertThat(result.getStatus()).isEqualTo(SandboxStatus.RUNNING);
        assertThat(containerRuntime.isRunning(result.getContainerId())).isTrue();
    }

    @Test
    void destroyStopsAndRemovesContainerAndMarksDestroyed() {
        UUID taskId = UUID.randomUUID();
        String containerId = containerRuntime.createContainer(null);
        containerRuntime.startContainer(containerId);
        SandboxContainer existing = activeSandbox(taskId, containerId, SandboxStatus.RUNNING);

        when(sandboxRepository.findByTaskIdAndStatusIn(eq(taskId), any())).thenReturn(Optional.of(existing));
        when(sandboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sandboxManager.destroy(taskId);

        assertThat(existing.getStatus()).isEqualTo(SandboxStatus.DESTROYED);
        assertThat(existing.getDestroyedAt()).isNotNull();
        assertThat(containerRuntime.isRunning(containerId)).isFalse();
    }

    @Test
    void reapsIdleRunningSandboxes() {
        UUID taskId = UUID.randomUUID();
        String containerId = containerRuntime.createContainer(null);
        containerRuntime.startContainer(containerId);
        SandboxContainer idle = activeSandbox(taskId, containerId, SandboxStatus.RUNNING);

        when(sandboxRepository.findByStatusAndLastActivityAtBefore(eq(SandboxStatus.RUNNING), any()))
                .thenReturn(List.of(idle));
        when(sandboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sandboxManager.reapIdleSandboxes();

        assertThat(idle.getStatus()).isEqualTo(SandboxStatus.DESTROYED);
        assertThat(containerRuntime.isRunning(containerId)).isFalse();
    }
}
