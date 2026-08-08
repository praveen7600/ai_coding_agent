# ADR-0003: Sandbox Manager - container runtime strategy

## Status
Accepted

## Context
Every agent task needs an isolated place to run shell commands, git
operations, and language toolchains that an LLM decided to invoke. This is
by definition untrusted, dynamically-decided code execution, so the design
has to answer four separate questions: how the backend talks to Docker, how
containers get resource-limited, how concurrent requests for the same task
avoid creating duplicate containers, and how abandoned containers get
cleaned up.

## Decision 1: docker-java client behind a `ContainerRuntime` port, not a CLI shell-out

Two ways to control Docker from the backend: shell out to the `docker` CLI
via `ProcessBuilder`, or talk to the Docker Engine API directly.

Went with the Engine API, via the `docker-java` library, wrapped in a
`ContainerRuntime` interface that `SandboxManager` depends on instead of
depending on `docker-java` types directly:

- **CLI shell-out** means parsing stdout text to know what happened
  (fragile), no typed error handling (a `docker: Error response from
  daemon: ...` string vs. a real exception), and command-injection risk
  wherever container names/args get interpolated into a shell string.
- **Engine API via docker-java** gives typed requests/responses and real
  exceptions (`NotFoundException`, etc.), and - because it's wrapped behind
  `ContainerRuntime` - `SandboxManager`'s business rules (reuse, race
  handling, idle reaping) can be unit tested against a fake runtime instead
  of a live Docker daemon or a mocked fluent builder chain.

`DockerContainerRuntime` is the only class in the codebase that imports
`docker-java`. Swapping to Kubernetes Jobs or Firecracker later is a new
adapter, not a `SandboxManager` rewrite.

## Decision 2: the Docker socket is a real risk, and V1 accepts it consciously

Mounting `/var/run/docker.sock` into the backend so it can create
containers is, functionally, giving the backend root on the host - anyone
who can reach that socket can start a privileged container and escape.

For V1 this is accepted with mitigations, not solved outright:
- Every sandbox container is created with `--cap-drop=ALL`,
  `no-new-privileges`, a `pids-limit`, and explicit memory/CPU ceilings
  (`HostConfig` in `ContainerSpec`) - so even if the *agent's* shell command
  is malicious, the container it runs in can't do much to the host.
- `network-mode` is configurable and defaults to a dedicated bridge
  network, never `host`.
- The backend process itself is the trust boundary that matters: it must
  never let unsanitized user input reach `ContainerSpec` fields that map to
  privileged Docker options (e.g. no user-controlled `capAdd`).

**Known gap, not yet solved:** the backend having socket access at all
means a bug in the backend (not just in agent-generated commands) is a
host-compromise path. The real fix is running the backend without direct
socket access - e.g. a small privileged sidecar that's the only thing
touching the socket, communicating with the backend over a narrow internal
API - or rootless Docker. Flagged here deliberately rather than silently
deferred, because "why does your app server have `/var/run/docker.sock`
mounted" is exactly the kind of question this ADR should have already
answered in an interview.

## Decision 3: race condition on "does this task already have a sandbox"

Two near-simultaneous requests for the same task (e.g. a retried HTTP call)
must not create two containers. Considered:

- **In-memory `synchronized` per-task lock** - works for a single backend
  instance, silently breaks the moment there's more than one instance
  behind a load balancer.
- **Redis distributed lock** - correct at scale, but Redis isn't in the
  stack yet (planned for queues/caching later) and pulling it in just for
  this is premature.
- **Postgres partial unique index** (chosen): `sandboxes(task_id) WHERE
  status IN ('CREATING','RUNNING')`. Two inserts for the same task can't
  both succeed - the database itself is the lock. The loser catches
  `DataIntegrityViolationException` and polls for the winner's row to
  finish provisioning.

This survives multiple backend instances for free, because the constraint
lives in Postgres, not in process memory - the same property a Redis lock
would need Redis for, without adding Redis.

## Decision 4: idle reaping via a scheduled sweep, not container-side timeouts

A `@Scheduled` job (`SandboxManager.reapIdleSandboxes`) destroys any
`RUNNING` sandbox whose `last_activity_at` is older than
`sandbox.idle-timeout-minutes`. Simpler than a per-container timer and
works even if the process that created a sandbox crashed before it could
clean up after itself - the DB row and `last_activity_at` are what's
authoritative, not in-memory state.

## Consequences
- `SandboxManager` has zero Docker-specific imports; its tests use a fake
  `ContainerRuntime`.
- Adding a second backend instance doesn't change the concurrency story -
  no code changes needed, the DB constraint already covers it.
- The socket-exposure gap in Decision 2 is real and should be the first
  thing addressed before this ever runs somewhere that isn't a personal
  project or a fully trusted network.
