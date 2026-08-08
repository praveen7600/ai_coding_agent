package com.praveen.aicodingagent.sandbox.runtime;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Everything needed to create a sandbox container. Resource limits live
 * here rather than as Docker defaults - an agent task is untrusted code
 * execution by definition (it's running LLM-planned shell commands), so
 * every container gets a hard ceiling.
 */
@Builder
public record ContainerSpec(
        String image,
        String containerName,
        long memoryBytes,
        long cpuNanos,          // e.g. 2_000_000_000L = 2 CPUs, passed as NanoCPUs
        long pidsLimit,         // caps fork-bombs
        String networkMode,     // "none" or a dedicated bridge network - never "host"
        String workingDir,
        Map<String, String> env,
        Map<String, String> labels,
        List<String> entrypoint
) {
}
