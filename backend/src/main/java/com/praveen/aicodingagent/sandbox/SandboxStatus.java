package com.praveen.aicodingagent.sandbox;

public enum SandboxStatus {
    CREATING,
    RUNNING,
    STOPPED,
    DESTROYED,
    FAILED;

    public boolean isActive() {
        return this == CREATING || this == RUNNING;
    }
}
