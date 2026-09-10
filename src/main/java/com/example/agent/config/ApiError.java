package com.example.agent.config;

import java.time.Instant;
import java.util.Map;

/**
 * Standard error response body for all REST API error responses.
 */
public record ApiError(String error, ApiErrorCode code, String requestId, Instant timestamp,
                       Map<String, String> fieldErrors) {
    /**
     * Create a simple error with no field-level details.
     */
    public static ApiError of(String error, ApiErrorCode code, String requestId) {
        return new ApiError(error, code, requestId, Instant.now(), null);
    }

    /**
     * Create a validation error with field-level details.
     */
    public static ApiError validation(String error, String requestId, Map<String, String> fields) {
        return new ApiError(error, ApiErrorCode.VALIDATION_FAILED, requestId, Instant.now(), fields);
    }
}
