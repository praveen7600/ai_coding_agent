package com.praveen.aicodingagent.sandbox.runtime;

/** Which stream an exec output chunk came from. Kept separate from docker-java's own StreamType so ContainerRuntime callers never need that import. */
public enum StreamType {
    STDOUT,
    STDERR
}
