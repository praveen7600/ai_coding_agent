package com.praveen.aicodingagent.sandbox.runtime;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.praveen.aicodingagent.sandbox.SandboxException;
import com.praveen.aicodingagent.sandbox.SandboxProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class DockerContainerRuntime implements ContainerRuntime {

    /**
     * Standard shell convention (same code GNU `timeout` uses) for "ran out
     * of time", not a real process exit code - lets the model distinguish
     * this from a normal command failure and react (narrow the command's
     * scope, run something in the background, split the work up) instead
     * of the whole task just dying, which is what throwing used to do here.
     */
    private static final int TIMEOUT_EXIT_CODE = 124;

    private final DockerClient dockerClient;
    private final SandboxProperties properties;

    public DockerContainerRuntime(DockerClient dockerClient, SandboxProperties properties) {
        this.dockerClient = dockerClient;
        this.properties = properties;
    }

    @Override
    public String createContainer(ContainerSpec spec) {
        try {
            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withMemory(spec.memoryBytes())
                    .withMemorySwap(spec.memoryBytes())     // disable swap beyond the memory cap
                    .withNanoCPUs(spec.cpuNanos())
                    .withPidsLimit(spec.pidsLimit())
                    .withNetworkMode(spec.networkMode())
                    .withCapDrop(Capability.ALL)             // no raw sockets, no ptrace, etc.
                    .withSecurityOpts(List.of("no-new-privileges:true"))
                    .withAutoRemove(false);                  // we remove explicitly, on our own schedule

            var cmd = dockerClient.createContainerCmd(spec.image())
                    .withName(spec.containerName())
                    .withHostConfig(hostConfig)
                    .withEnv(toEnvList(spec.env()))
                    .withLabels(spec.labels())
                    .withWorkingDir(spec.workingDir())
                    .withTty(true)
                    .withStdinOpen(true);

            if (spec.entrypoint() != null && !spec.entrypoint().isEmpty()) {
                cmd = cmd.withEntrypoint(spec.entrypoint());
            }

            return cmd.exec().getId();
        } catch (Exception e) {
            throw new SandboxException("Failed to create container from image " + spec.image(), e);
        }
    }

    @Override
    public void startContainer(String containerId) {
        try {
            dockerClient.startContainerCmd(containerId).exec();
        } catch (Exception e) {
            throw new SandboxException("Failed to start container " + containerId, e);
        }
    }

    @Override
    public ExecResult exec(String containerId, List<String> command, String workdir) {
        // No-op listener: same code path as execStreaming, just nobody
        // watching the chunks as they arrive.
        return execStreaming(containerId, command, workdir, (streamType, chunk) -> { });
    }

    @Override
    public ExecResult execStreaming(String containerId, List<String> command, String workdir, ExecOutputListener listener) {
        try {
            ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(containerId)
                    .withCmd(command.toArray(new String[0]))
                    .withWorkingDir(workdir)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();

            // ExecStartResultCallback (used previously) only buffers into two
            // streams and hands them back once the exec finishes - fine for
            // a synchronous exec() but useless for live output. A raw
            // ResultCallback.Adapter<Frame> gets onNext() called per chunk as
            // Docker sends it, which is what actually makes exec-streaming
            // possible; it still fills the same two buffers so the final
            // ExecResult looks identical to the old exec() for callers that
            // don't care about live output.
            ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Frame frame) {
                    byte[] payload = frame.getPayload();
                    if (payload == null || payload.length == 0) {
                        return;
                    }
                    String chunk = new String(payload, StandardCharsets.UTF_8);
                    boolean isStderr = frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDERR;
                    if (isStderr) {
                        stderr.writeBytes(payload);
                        listener.onChunk(StreamType.STDERR, chunk);
                    } else {
                        stdout.writeBytes(payload);
                        listener.onChunk(StreamType.STDOUT, chunk);
                    }
                    super.onNext(frame);
                }
            };

            int timeoutSeconds = properties.effectiveExecTimeoutSeconds();
            boolean completed = dockerClient.execStartCmd(execCreate.getId())
                    .exec(callback)
                    .awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);

            if (!completed) {
                // Deliberately NOT a thrown SandboxException anymore - that
                // used to propagate out of ToolExecutor.execute() straight
                // into AgentOrchestrator's catch(SandboxException), which
                // fails the WHOLE task over one slow command. A build or
                // test command exceeding the timeout is exactly the kind
                // of thing large tasks hit and small tasks don't; the model
                // should get a chance to see "this timed out" and adapt
                // (narrow scope, background it, skip it) the same way it
                // already reacts to a non-zero exit code, rather than the
                // task dying outright. Genuine infra failure (Docker itself
                // unreachable) still throws, in the catch block below.
                //
                // callback.close() detaches our listener from the exec
                // stream; it does NOT stop the process inside the
                // container - docker-java's exec API has no direct
                // "kill this exec" call. The process may keep running
                // server-side until the container itself is torn down
                // (task completion or the idle reaper). Documented
                // trade-off, not an oversight: the alternative (docker top
                // + kill -9 <pid> by parsing ps output) is a lot of
                // fragile complexity for what free-tier/dev usage needs.
                callback.close();
                String timeoutNote = "\n[Command timed out after " + timeoutSeconds
                        + "s and was abandoned - it may still be running in the sandbox. "
                        + "Consider a narrower command, running it in the background with &, "
                        + "or breaking the task into smaller steps.]";
                return new ExecResult(
                        TIMEOUT_EXIT_CODE,
                        stdout.toString(StandardCharsets.UTF_8),
                        stderr.toString(StandardCharsets.UTF_8) + timeoutNote
                );
            }

            Long exitCodeLong = dockerClient.inspectExecCmd(execCreate.getId()).exec().getExitCodeLong();
            int exitCode = exitCodeLong == null ? -1 : exitCodeLong.intValue();

            return new ExecResult(exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
        } catch (SandboxException e) {
            throw e;
        } catch (Exception e) {
            throw new SandboxException("Failed to exec in container " + containerId + ": " + command, e);
        }
    }

    @Override
    public void stopContainer(String containerId) {
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(10).exec();
        } catch (NotFoundException ignored) {
            // already gone - stopping a non-existent container is not an error for our purposes
        } catch (Exception e) {
            throw new SandboxException("Failed to stop container " + containerId, e);
        }
    }

    @Override
    public void removeContainer(String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
        } catch (NotFoundException ignored) {
            // already removed
        } catch (Exception e) {
            throw new SandboxException("Failed to remove container " + containerId, e);
        }
    }

    @Override
    public boolean isRunning(String containerId) {
        try {
            InspectContainerResponse response = dockerClient.inspectContainerCmd(containerId).exec();
            return response.getState() != null && Boolean.TRUE.equals(response.getState().getRunning());
        } catch (NotFoundException e) {
            return false;
        } catch (Exception e) {
            throw new SandboxException("Failed to inspect container " + containerId, e);
        }
    }

    private static List<String> toEnvList(Map<String, String> env) {
        if (env == null) return List.of();
        return env.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .toList();
    }
}
