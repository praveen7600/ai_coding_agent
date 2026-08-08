package com.praveen.aicodingagent.sandbox;

import com.praveen.aicodingagent.sandbox.runtime.ContainerRuntime;
import com.praveen.aicodingagent.sandbox.runtime.ContainerSpec;
import com.praveen.aicodingagent.sandbox.runtime.ExecResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory fake standing in for Docker. This is the payoff of putting
 * ContainerRuntime behind an interface: SandboxManager's reuse/race/reap
 * logic gets tested against real behavior (a container that is or isn't
 * running) instead of a mock returning canned values for a dozen chained
 * docker-java builder calls.
 */
public class FakeContainerRuntime implements ContainerRuntime {

    private final AtomicInteger counter = new AtomicInteger();
    private final Set<String> runningContainers = new HashSet<>();

    @Override
    public String createContainer(ContainerSpec spec) {
        String id = "fake-container-" + counter.incrementAndGet();
        return id;
    }

    @Override
    public void startContainer(String containerId) {
        runningContainers.add(containerId);
    }

    @Override
    public ExecResult exec(String containerId, List<String> command, String workdir) {
        if (!runningContainers.contains(containerId)) {
            throw new SandboxException("exec on non-running container " + containerId);
        }
        return new ExecResult(0, "ok", "");
    }

    @Override
    public void stopContainer(String containerId) {
        runningContainers.remove(containerId);
    }

    @Override
    public void removeContainer(String containerId) {
        runningContainers.remove(containerId);
    }

    @Override
    public boolean isRunning(String containerId) {
        return runningContainers.contains(containerId);
    }

    /** Test hook: simulate a container dying without SandboxManager being told. */
    public void killOutOfBand(String containerId) {
        runningContainers.remove(containerId);
    }
}
