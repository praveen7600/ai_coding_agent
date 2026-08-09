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
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class DockerContainerRuntime implements ContainerRuntime {

    /** Commands longer than this are killed rather than left to hang the exec loop forever. */
    private static final int DEFAULT_EXEC_TIMEOUT_SECONDS = 120;

    private final DockerClient dockerClient;

    public DockerContainerRuntime(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
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

            boolean completed = dockerClient.execStartCmd(execCreate.getId())
                    .exec(callback)
                    .awaitCompletion(DEFAULT_EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!completed) {
                throw new SandboxException("Command timed out after " + DEFAULT_EXEC_TIMEOUT_SECONDS + "s: " + command);
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
