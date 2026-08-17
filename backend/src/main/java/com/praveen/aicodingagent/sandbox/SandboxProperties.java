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
    /**
     * 300s (5 min) default, bumped up from the value DockerContainerRuntime
     * previously hardcoded (120s) and ignored this property entirely for.
     * Build/test commands on a real repo - the kind large tasks actually
     * run - routinely exceed 120s; a raw `int` field defaulting to 0 when
     * unset in application.yml would make every exec time out immediately,
     * so this follows the same non-positive-falls-back-to-default pattern
     * as GeminiProperties.maxRequestsPerMinute().
     */
    private static final int DEFAULT_EXEC_TIMEOUT_SECONDS = 300;

    public long memoryBytes() {
        return memoryMb * 1024 * 1024;
    }

    public long cpuNanos() {
        return (long) (cpuCores * 1_000_000_000L);
    }

    public int effectiveExecTimeoutSeconds() {
        return execTimeoutSeconds > 0 ? execTimeoutSeconds : DEFAULT_EXEC_TIMEOUT_SECONDS;
    }
}
