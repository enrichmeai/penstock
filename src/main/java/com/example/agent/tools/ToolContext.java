package com.example.agent.tools;

import com.example.agent.model.BearerToken;

import java.util.Optional;

/**
 * Who a tool call is acting for, and what the request that started the turn carried.
 *
 * Resolved once on the request thread (see CLAUDE.md, "Identity on background
 * threads") and passed explicitly down the call chain — tools must NEVER read
 * thread-local security state, because the agent loop runs on pooled threads
 * where the SecurityContext is absent.
 *
 * <ul>
 *   <li>{@link #userId()} — the acting user, for per-user credentials (see
 *       {@code CredentialResolver}); never null, "anonymous" when there is none.</li>
 *   <li>{@link #requestId()} — the inbound {@code X-Request-Id}, so an outbound call can
 *       carry the same ID and the far system's record joins this one's; null when the
 *       turn was not started by an HTTP request.</li>
 *   <li>{@link #bearer()} — the bearer token the signed-in user presented, present only
 *       when they authenticated with one. A tool configured to act as the signed-in user
 *       forwards it; every other tool ignores it. {@link BearerToken#toString()} is
 *       redacted, so this record is safe to log.</li>
 * </ul>
 */
public record ToolContext(String userId, String sessionId, String requestId, Optional<BearerToken> bearer) {

    public static final String ANONYMOUS = "anonymous";

    public ToolContext {
        userId = (userId == null || userId.isBlank()) ? ANONYMOUS : userId;
        requestId = (requestId == null || requestId.isBlank()) ? null : requestId;
        bearer = bearer == null ? Optional.empty() : bearer;
    }

    /** Identity only — no request ID and no inbound bearer. */
    public ToolContext(String userId, String sessionId) {
        this(userId, sessionId, null, Optional.empty());
    }

    /** For callers with no authenticated user (tests, direct invocations). */
    public static ToolContext anonymous() {
        return new ToolContext(ANONYMOUS, null);
    }
}
