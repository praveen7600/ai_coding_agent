package com.praveen.aicodingagent.sandbox.runtime;

import java.util.List;

/**
 * Port for "a thing that can run isolated containers". SandboxManager talks
 * to this interface only - it has no import of docker-java anywhere.
 *
 * Why: the business rules that actually matter for an interview (reuse a
 * running container if it's still assigned to the task, otherwise create
 * one, destroy on completion or idle timeout) have nothing to do with
 * Docker specifically. Keeping them behind this port means:
 *   1. SandboxManager is unit-testable with a hand-rolled fake, instead of
 *      mocking docker-java's fluent command-builder chain.
 *   2. Swapping the execution backend later (Kubernetes Jobs, Firecracker
 *      microVMs, gVisor) is a new adapter, not a rewrite of SandboxManager.
 */
public interface ContainerRuntime {

    /** Creates a container from the given spec. Does not start it. */
    String createContainer(ContainerSpec spec);

    void startContainer(String containerId);

    /** Runs a command inside a running container and waits for it to finish. */
    ExecResult exec(String containerId, List<String> command, String workdir);

    void stopContainer(String containerId);

    void removeContainer(String containerId);

    /** True if the container exists and Docker reports it as running. */
    boolean isRunning(String containerId);
}
