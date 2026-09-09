package com.example.agent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RequestIdFilter.
 * Tests MDC population, request/response header handling, and session ID extraction.
 */
class RequestIdFilterTest {

    private RequestIdFilter filter;
    private CurrentUserStub currentUserStub;

    @BeforeEach
    void setUp() {
        currentUserStub = new CurrentUserStub();
        filter = new RequestIdFilter(currentUserStub);
        MDC.clear();
    }

    @Test
    void providingXRequestIdHeaderFlowsThroughAndIsEchoed() throws ServletException, IOException {
        String providedId = "req-abc-123";

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        request.addHeader("X-Request-Id", providedId);

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = captureAndPassThroughChain();

        filter.doFilterInternal(request, response, chain);

        // Response header should echo the provided ID
        assertEquals(providedId, response.getHeader("X-Request-Id"),
                "Response should echo X-Request-Id header");

        // MDC should have been populated during the chain execution
        // (after the filter completes, MDC is cleared, so we verify via the captureChain)
        assertNull(MDC.get("requestId"), "MDC should be cleared after filter completes");
    }

    @Test
    void noHeaderGeneratesNonEmptyIdAndSetsOnBothMdcAndResponseHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        // No X-Request-Id header

        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] capturedRequestId = new String[1];
        FilterChain chain = new FilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                // Capture MDC value during chain execution (before clear)
                capturedRequestId[0] = MDC.get("requestId");
            }
        };

        filter.doFilterInternal(request, response, chain);

        // Verify generated ID is non-empty
        assertNotNull(capturedRequestId[0], "Generated request ID should not be null");
        assertFalse(capturedRequestId[0].isBlank(), "Generated request ID should not be blank");
        assertTrue(capturedRequestId[0].length() > 0, "Generated request ID should have content");

        // Verify response header is set with the generated ID
        String responseHeaderId = response.getHeader("X-Request-Id");
        assertNotNull(responseHeaderId, "Response should have X-Request-Id header");
        assertEquals(capturedRequestId[0], responseHeaderId, "Response header should match generated ID");

        // MDC should be cleared after filter completes
        assertNull(MDC.get("requestId"), "MDC should be cleared after filter completes");
    }

    @Test
    void sessionIdIsExtractedFromPathAndPopulatedInMdc() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sessions/abc-123/usage");
        // No X-Request-Id header; will be generated

        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] capturedSessionId = new String[1];
        FilterChain chain = new FilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                capturedSessionId[0] = MDC.get("sessionId");
            }
        };

        filter.doFilterInternal(request, response, chain);

        // Verify session ID was extracted and populated in MDC
        assertEquals("abc-123", capturedSessionId[0], "Session ID should be extracted from path");

        // MDC should be cleared after filter completes
        assertNull(MDC.get("sessionId"), "MDC should be cleared after filter completes");
    }

    @Test
    void sessionIdNotExtractedFromNonSessionPath() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/send");
        // No X-Request-Id header; will be generated

        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] capturedSessionId = new String[1];
        FilterChain chain = new FilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                capturedSessionId[0] = MDC.get("sessionId");
            }
        };

        filter.doFilterInternal(request, response, chain);

        // Session ID should not be set for non-session paths
        assertNull(capturedSessionId[0], "Session ID should not be set for non-session paths");
    }

    @Test
    void userIdIsPopulatedInMdcFromCurrentUser() throws ServletException, IOException {
        currentUserStub.setUserId("alice");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] capturedUserId = new String[1];
        FilterChain chain = new FilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                capturedUserId[0] = MDC.get("userId");
            }
        };

        filter.doFilterInternal(request, response, chain);

        assertEquals("alice", capturedUserId[0], "User ID should be populated from CurrentUser");

        // MDC should be cleared after filter completes
        assertNull(MDC.get("userId"), "MDC should be cleared after filter completes");
    }

    @Test
    void mdcIsClearedAfterFilterCompletes() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sessions/xyz-789/messages");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = captureAndPassThroughChain();

        filter.doFilterInternal(request, response, chain);

        // After the filter completes, MDC should be empty
        assertNull(MDC.get("requestId"), "MDC requestId should be cleared");
        assertNull(MDC.get("userId"), "MDC userId should be cleared");
        assertNull(MDC.get("sessionId"), "MDC sessionId should be cleared");
    }

    @Test
    void generatedRequestIdIs12CharsOrLonger() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] capturedRequestId = new String[1];
        FilterChain chain = new FilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                capturedRequestId[0] = MDC.get("requestId");
            }
        };

        filter.doFilterInternal(request, response, chain);

        assertNotNull(capturedRequestId[0], "Generated request ID should not be null");
        assertTrue(capturedRequestId[0].length() >= 12,
                "Generated request ID should be at least 12 characters long");
    }

    @Test
    void chainExecutionWithMultipleSessionIdPatterns() throws ServletException, IOException {
        // Test various session ID patterns
        String[] paths = {
                "/api/sessions/sess-123/usage",
                "/api/sessions/abc123def456/messages",
                "/api/sessions/session-with-dashes/settings"
        };

        for (String path : paths) {
            String expectedSessionId = extractExpectedSessionId(path);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            String[] capturedSessionId = new String[1];
            FilterChain chain = new FilterChain() {
                @Override
                public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                    capturedSessionId[0] = MDC.get("sessionId");
                }
            };

            filter.doFilterInternal(request, response, chain);

            assertEquals(expectedSessionId, capturedSessionId[0],
                    "Session ID should be correctly extracted from " + path);
        }
    }

    // Helper methods

    /**
     * Creates a simple filter chain that just marks response as 200.
     */
    private FilterChain captureAndPassThroughChain() {
        return new FilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                ((HttpServletResponse) response).setStatus(200);
            }
        };
    }

    /**
     * Simple CurrentUser stub for testing.
     */
    private static class CurrentUserStub extends CurrentUser {
        private String userId = "alice";

        @Override
        public String name() {
            return userId;
        }

        void setUserId(String userId) {
            this.userId = userId;
        }
    }

    /**
     * Extract the expected session ID from a path pattern.
     * Assumes pattern is /api/sessions/{id}/...
     */
    private String extractExpectedSessionId(String path) {
        String[] parts = path.split("/");
        if (parts.length >= 4 && "api".equals(parts[1]) && "sessions".equals(parts[2])) {
            return parts[3];
        }
        return null;
    }

    @Test
    void requestIdIsExposedAsARequestAttributeForTheRestOfTheRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "req-attr-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenInChain = new String[1];
        FilterChain chain = (req, res) ->
                seenInChain[0] = (String) req.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);

        filter.doFilterInternal(request, response, chain);

        assertEquals("req-attr-1", seenInChain[0],
                "a controller reads the ID from the request, not from thread-local MDC");
    }
}
