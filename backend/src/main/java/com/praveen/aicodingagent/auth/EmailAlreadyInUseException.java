package com.praveen.aicodingagent.auth;

public class EmailAlreadyInUseException extends RuntimeException {
    public EmailAlreadyInUseException(String email) {
        super("Email already registered: " + email);
    }
}
