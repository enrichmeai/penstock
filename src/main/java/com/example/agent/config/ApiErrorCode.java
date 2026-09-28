package com.example.agent.config;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The closed set of {@code code} values an {@link ApiError} can carry. Clients branch on the
 * snake_case wire form; code references the constant, never the string.
 */
public enum ApiErrorCode {
    BAD_REQUEST("bad_request"),
    BAD_STATE("bad_state"),
    FORBIDDEN("forbidden"),
    /** The model gateway refused the call (budget, rate limit); see {@code reason}. HTTP 429. */
    GATEWAY_REFUSED("gateway_refused"),
    INTERNAL_ERROR("internal_error"),
    NOT_FOUND("not_found"),
    UNAUTHENTICATED("unauthenticated"),
    VALIDATION_FAILED("validation_failed");

    private final String wire;

    ApiErrorCode(String wire) {
        this.wire = wire;
    }

    /** The value on the wire, e.g. {@code "internal_error"}. */
    @JsonValue
    public String wire() {
        return wire;
    }
}
