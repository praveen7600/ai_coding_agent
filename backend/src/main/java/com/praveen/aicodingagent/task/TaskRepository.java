package com.praveen.aicodingagent.task;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    Page<Task> findByUserId(UUID userId, Pageable pageable);

    Page<Task> findByUserIdAndStatus(UUID userId, TaskStatus status, Pageable pageable);
}
