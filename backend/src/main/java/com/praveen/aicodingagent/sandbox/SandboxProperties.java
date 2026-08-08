package com.praveen.aicodingagent.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sandbox")
public record SandboxProperties(
        String dockerHost,
        String image,
        String workspaceDir,
        long memoryMb,
        double cpuCores,
        long pidsLimit,
        String networkMode,
        int execTimeoutSeconds,
        int idleTimeoutMinutes
) {
    public long memoryBytes() {
        return memoryMb * 1024 * 1024;
    }

    public long cpuNanos() {
        return (long) (cpuCores * 1_000_000_000L);
    }
}
