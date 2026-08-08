package com.praveen.aicodingagent.task.dto;

import com.praveen.aicodingagent.task.Task;
import com.praveen.aicodingagent.task.TaskStatus;

import java.time.Instant;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        String title,
        String description,
        String repoUrl,
        TaskStatus status,
        String resultSummary,
        Instant createdAt,
        Instant updatedAt
) {
    public static TaskResponse from(Task task) {
        return new TaskResponse(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getRepoUrl(),
                task.getStatus(),
                task.getResultSummary(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
