package com.example.agent.config;

import com.example.agent.llm.GatewayRefusal;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Standard error response body for all REST API error responses, and the payload of the SSE
 * {@code error} event, so a client reads one shape everywhere.
 *
 * @param reason present only with {@link ApiErrorCode#GATEWAY_REFUSED}: which refusal
 *               ({@link GatewayRefusal#wire()}); absent otherwise
 */
public record ApiError(String error, ApiErrorCode code, String requestId, Instant timestamp,
                       Map<String, String> fieldErrors,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String reason) {
    /**
     * Create a simple error with no field-level details.
     */
    public static ApiError of(String error, ApiErrorCode code, String requestId) {
        return new ApiError(error, code, requestId, Instant.now(), null, null);
    }

    /**
     * Create a validation error with field-level details.
     */
    public static ApiError validation(String error, String requestId, Map<String, String> fields) {
        return new ApiError(error, ApiErrorCode.VALIDATION_FAILED, requestId, Instant.now(), fields, null);
    }

    /**
     * The model gateway refused the call. {@code error} is the catalogue sentence a user may
     * see; {@code reason} says which refusal, so a client can tell a budget from a rate limit.
     */
    public static ApiError gatewayRefused(String error, GatewayRefusal reason, String requestId) {
        return new ApiError(error, ApiErrorCode.GATEWAY_REFUSED, requestId, Instant.now(), null, reason.wire());
    }
}
