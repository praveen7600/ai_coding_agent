# Sandbox image the Agent Orchestrator executes tool calls inside.
# Pre-built with everything an agent task might need so no per-task
# `apt-get install` is required (that would be slow and, worse, would need
# outbound access to package mirrors from every sandbox).

FROM ubuntu:24.04

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    wget \
    git \
    unzip \
    build-essential \
    ca-certificates \
    gnupg \
    grep \
    findutils \
    && rm -rf /var/lib/apt/lists/*

# Java 17 + Maven
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk \
    maven \
    && rm -rf /var/lib/apt/lists/*

# Node.js 20 + npm
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# Python 3 + pip
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    python3-venv \
    && rm -rf /var/lib/apt/lists/*

# Non-root user - the agent's shell commands never run as root inside the
# container, even though the container itself is already capability-dropped
# and resource-limited by SandboxManager (defense in depth).
#
# Gotcha worth knowing: WORKDIR creates the directory as root even after a
# USER instruction if it doesn't already exist, so it must be created and
# chown'd explicitly *before* switching users, or the agent can't write to
# its own workspace.
RUN useradd -m -s /bin/bash agent \
    && mkdir -p /workspace \
    && chown agent:agent /workspace
USER agent
WORKDIR /workspace

# Kept alive by SandboxManager's `tail -f /dev/null` entrypoint override;
# real commands arrive via `docker exec`, not CMD.
CMD ["sleep", "infinity"]
