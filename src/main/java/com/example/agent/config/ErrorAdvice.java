package com.example.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import com.example.agent.llm.GatewayRefusedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.example.agent.controller.SessionNotFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Global REST error handler. Normalizes all exceptions to ApiError responses
 * with consistent HTTP status codes, error codes, and request IDs.
 * Sanitizes error messages to prevent information leakage.
 */
@RestControllerAdvice
class ErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(ErrorAdvice.class);

    /**
     * 403 Forbidden: user lacks permission
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        String requestId = getRequestId();
        log.warn("[{}] Access denied", requestId, ex);
        ApiError error = ApiError.of("Access denied.", ApiErrorCode.FORBIDDEN, requestId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    /**
     * 401 Unauthenticated: missing or invalid credentials
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthenticationException(AuthenticationException ex) {
        String requestId = getRequestId();
        log.warn("[{}] Authentication required", requestId, ex);
        ApiError error = ApiError.of("Authentication required.", ApiErrorCode.UNAUTHENTICATED, requestId);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    /**
     * 400 Bad Request: request body validation failed
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationException(MethodArgumentNotValidException ex) {
        String requestId = getRequestId();
        log.warn("[{}] Validation failed", requestId, ex);

        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fieldErrors.put(fe.getField(), fe.getDefaultMessage()));

        ApiError error = ApiError.validation("Validation failed.", requestId, fieldErrors);
        return ResponseEntity.badRequest().body(error);
    }

    /**
     * 400 Bad Request: missing required request parameter
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex) {
        String requestId = getRequestId();
        log.warn("[{}] Bad request: {}", requestId, ex.getMessage(), ex);
        ApiError error = ApiError.of(ex.getMessage(), ApiErrorCode.BAD_REQUEST, requestId);
        return ResponseEntity.badRequest().body(error);
    }

    /**
     * 404 Not Found: session is unknown OR the caller doesn't own it.
     * We deliberately return the same response in both cases to avoid leaking existence.
     */
    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ApiError> handleSessionNotFound(SessionNotFoundException ex) {
        String requestId = getRequestId();
        log.debug("[{}] {}", requestId, ex.getMessage());
        ApiError error = ApiError.of(ex.getMessage(), ApiErrorCode.NOT_FOUND, requestId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * 400 Bad Request: illegal argument
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        String requestId = getRequestId();
        log.warn("[{}] Bad request: {}", requestId, ex.getMessage(), ex);
        // Message is already safe (e.g., "Session not found: id123")
        ApiError error = ApiError.of(ex.getMessage(), ApiErrorCode.BAD_REQUEST, requestId);
        return ResponseEntity.badRequest().body(error);
    }

    /**
     * 400 Bad Request: illegal state (service not ready)
     * If the exception class is marked with @SafeMessage, the message is safe to expose.
     * Otherwise, a generic message is used.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex) {
        String requestId = getRequestId();

        boolean isSafe = ex.getClass().isAnnotationPresent(SafeMessage.class);
        String message = isSafe ? ex.getMessage() : "Service is not ready.";

        log.warn("[{}] Bad state: {}", requestId, ex.getMessage(), ex);
        ApiError error = ApiError.of(message, ApiErrorCode.BAD_STATE, requestId);
        return ResponseEntity.badRequest().body(error);
    }

    /**
     * 429 Too Many Requests: the model gateway refused the call — a budget or a rate limit,
     * decided upstream before any model ran. Not an internal error: nothing here failed, and
     * the caller is owed the decision and its reason. The gateway's Retry-After is passed
     * through when it sent one. The message is the catalogue's ({@link GatewayRefusedException}
     * is {@link SafeMessage}); the gateway's own text stays in the log.
     */
    @ExceptionHandler(GatewayRefusedException.class)
    public ResponseEntity<ApiError> handleGatewayRefused(GatewayRefusedException ex) {
        String requestId = getRequestId();
        log.warn("[{}] Gateway refused the model call: {} (upstream {} {})",
                requestId, ex.refusal(), ex.upstreamStatus(), ex.upstream());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
        ex.retryAfter().ifPresent(wait -> response.header(HttpHeaders.RETRY_AFTER, Long.toString(wait.toSeconds())));
        return response.body(ApiError.gatewayRefused(ex.getMessage(), ex.refusal(), requestId));
    }

    /**
     * 500 Internal Server Error: unexpected exception
     * Message is always sanitized.
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiError> handleThrowable(Throwable ex) {
        String requestId = getRequestId();
        log.error("[{}] Internal error", requestId, ex);
        ApiError error = ApiError.of("Internal error.", ApiErrorCode.INTERNAL_ERROR, requestId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * Get the request ID from MDC, or generate a new one.
     */
    private String getRequestId() {
        String id = MDC.get("requestId");
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString().substring(0, 8);
        }
        return id;
    }
}
