package com.praveen.aicodingagent.sandbox.runtime;

public record ExecResult(
        int exitCode,
        String stdout,
        String stderr
) {
    public boolean isSuccess() {
        return exitCode == 0;
    }
}
