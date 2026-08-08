package com.praveen.aicodingagent.task;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A unit of work handed to the agent, e.g. "Add pagination to the /orders
 * endpoint". This is the row the REST Controller, Agent Orchestrator and
 * Sandbox Manager all reference by id.
 *
 * `context` is stored as JSONB rather than a normalized table. This is the
 * concrete case for the Postgres-over-MySQL call in ADR-0001: the shape of
 * "agent context" (repo info, plan, running notes) changes as the
 * orchestrator evolves, and we don't want a migration for every new field
 * the LLM loop decides to track. JSONB still lets us index/query into it
 * later if a field earns that.
 */
@Entity
@Table(name = "tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "repo_url", nullable = false)
    private String repoUrl;

    /** Owning user - FK enforced once Auth & User Service lands. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TaskStatus status = TaskStatus.PENDING;

    /** Free-form agent context: repo snapshot, plan, scratch notes. JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> context;

    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = TaskStatus.PENDING;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
