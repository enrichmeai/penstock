package com.example.agent.controller;

import com.example.agent.config.SafeMessage;

/**
 * Thrown when an audit query is requested but there is no {@code AuditEventRepository} —
 * memory storage mode, which has no audit table at all (see {@code AuditLogger}).
 */
@SafeMessage
public class AuditStoreUnavailableException extends RuntimeException {
    public AuditStoreUnavailableException(String message) {
        super(message);
    }
}
