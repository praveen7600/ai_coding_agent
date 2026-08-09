package com.praveen.aicodingagent.sandbox.runtime;

/**
 * Receives output chunks as an exec runs, instead of waiting for it to
 * finish. A functional interface so callers (SandboxManager) can pass a
 * lambda without a new class per call site.
 */
@FunctionalInterface
public interface ExecOutputListener {
    void onChunk(StreamType streamType, String chunk);
}
