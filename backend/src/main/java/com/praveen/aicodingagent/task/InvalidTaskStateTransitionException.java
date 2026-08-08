package com.praveen.aicodingagent.task;

public class InvalidTaskStateTransitionException extends RuntimeException {
    public InvalidTaskStateTransitionException(TaskStatus from, TaskStatus to) {
        super("Cannot transition task from " + from + " to " + to);
    }
}
