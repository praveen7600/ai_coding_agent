package com.praveen.aicodingagent.sandbox;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks which Docker container (if any) is currently assigned to a task.
 * The DB row is the source of truth for "does this task have a live
 * sandbox" - we never trust in-memory state for this, because the backend
 * itself may be one of several instances behind a load balancer eventually
 * (Redis-backed distributed lock is the natural next step if that happens;
 * see ADR-0003).
 */
@Entity
@Table(name = "sandboxes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SandboxContainer {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    /** Docker's container ID. Null while status = CREATING, before the create call returns. */
    @Column(name = "container_id")
    private String containerId;

    @Column(nullable = false)
    private String image;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SandboxStatus status = SandboxStatus.CREATING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Column(name = "destroyed_at")
    private Instant destroyedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        lastActivityAt = now;
    }

    public void touch() {
        this.lastActivityAt = Instant.now();
    }
}
