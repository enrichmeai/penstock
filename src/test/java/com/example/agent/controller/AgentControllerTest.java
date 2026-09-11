package com.example.agent.controller;

import com.example.agent.config.SafeMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentControllerTest {

    @Test
    void clientSafeMessageSanitizesUnsafeExceptions() {
        assertEquals("Internal error.", AgentController.clientSafeMessage(
                new IllegalStateException("upstream detail should not leak"), "Internal error."));
    }

    @Test
    void clientSafeMessageKeepsSafeMessages() {
        assertEquals("safe for client", AgentController.clientSafeMessage(
                new SafeTestException("safe for client"), "Internal error."));
    }

    @SafeMessage
    private static final class SafeTestException extends RuntimeException {
        SafeTestException(String message) {
            super(message);
        }
    }
}
