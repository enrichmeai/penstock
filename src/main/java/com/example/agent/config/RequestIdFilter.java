package com.example.agent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RequestIdFilter propagates request IDs and user context through MDC (Mapped Diagnostic Context)
 * for structured logging and tracing.
 *
 * On each request:
 * - Reads X-Request-Id header (or generates a 12-char UUID suffix).
 * - Puts requestId into MDC, and exposes it as the {@link #REQUEST_ID_ATTRIBUTE} request
 *   attribute so a controller can carry it into work that leaves the request thread.
 * - Puts authenticated user name into MDC userId.
 * - Extracts sessionId from /api/sessions/{id}... paths.
 * - Sets X-Request-Id response header.
 * - Clears MDC after the request completes.
 *
 * Runs BEFORE Spring Security and RateLimitFilter via HIGHEST_PRECEDENCE.
 *
 * <p>This class owns the request-ID vocabulary: the header name, read here and sent on
 * every outbound call the turn makes (the pod tool, the model gateway), so the far
 * system's record and this one's name the same request; and the MDC keys.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    /** Inbound and outbound header carrying the request ID. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    /** Request attribute holding the resolved request ID for the rest of the request. */
    public static final String REQUEST_ID_ATTRIBUTE = "com.example.agent.config.RequestIdFilter.requestId";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_USER_ID = "userId";
    public static final String MDC_SESSION_ID = "sessionId";

    private static final int GENERATED_ID_LENGTH = 12;

    /**
     * Regex to extract session ID from paths like /api/sessions/{id}... or /api/sessions/{id}/action
     */
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^/api/sessions/([^/]+)(?:/.*)?$");

    private final CurrentUser currentUser;

    public RequestIdFilter(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            // 1. Resolve or generate request ID
            String requestId = resolveRequestId(request);
            MDC.put(MDC_REQUEST_ID, requestId);
            request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);

            // 2. Resolve user ID and put into MDC
            String userId = currentUser.name();
            MDC.put(MDC_USER_ID, userId);

            // 3. Extract session ID if applicable
            String sessionId = extractSessionId(request);
            if (sessionId != null) {
                MDC.put(MDC_SESSION_ID, sessionId);
            }

            // 4. Set response header with request ID
            response.setHeader(REQUEST_ID_HEADER, requestId);

            // 5. Proceed with the filter chain
            chain.doFilter(request, response);
        } finally {
            // 6. Clear MDC after the request completes
            MDC.clear();
        }
    }

    /**
     * Reads X-Request-Id header. If present and non-blank, uses it.
     * Otherwise generates a 12-char UUID-suffix string.
     */
    private String resolveRequestId(HttpServletRequest request) {
        String headerValue = request.getHeader(REQUEST_ID_HEADER);
        if (headerValue != null && !headerValue.isBlank()) {
            return headerValue;
        }
        // Generate: take the last 12 chars of a UUID (no hyphens there, so it stays compact)
        String uuid = UUID.randomUUID().toString();
        return uuid.substring(uuid.length() - GENERATED_ID_LENGTH);
    }

    /**
     * Extracts session ID from /api/sessions/{id}... paths.
     * Returns null if the path doesn't match the pattern.
     */
    private String extractSessionId(HttpServletRequest request) {
        String path = request.getRequestURI();
        Matcher matcher = SESSION_ID_PATTERN.matcher(path);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return null;
    }
}
