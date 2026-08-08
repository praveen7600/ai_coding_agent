package com.praveen.aicodingagent.sandbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SandboxRepository extends JpaRepository<SandboxContainer, UUID> {

    Optional<SandboxContainer> findByTaskIdAndStatusIn(UUID taskId, List<SandboxStatus> statuses);

    List<SandboxContainer> findByStatusAndLastActivityAtBefore(SandboxStatus status, Instant cutoff);
}
