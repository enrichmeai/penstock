package com.example.agent.service;

import com.example.agent.model.BearerToken;

import java.util.Optional;

/**
 * What the inbound HTTP request knew that the turn needs later, captured on the request
 * thread and handed explicitly to {@link AgentService}. The agent loop runs on pooled
 * threads where neither the request nor a SecurityContext exists (CLAUDE.md, "Identity on
 * background threads"); identity itself already travels on the {@code Session}, and this
 * carries the rest: the request ID that joins this turn's outbound calls to the caller's
 * request, and the caller's own bearer token, for tools configured to act as the
 * signed-in user.
 *
 * <p>Never persisted — the bearer lives exactly as long as the turn. Safe to log:
 * {@link BearerToken#toString()} is redacted.
 */
public record TurnContext(String requestId, Optional<BearerToken> bearer) {

    public TurnContext {
        requestId = (requestId == null || requestId.isBlank()) ? null : requestId;
        bearer = bearer == null ? Optional.empty() : bearer;
    }

    /** A turn not started by an HTTP request (tests, direct calls): no request ID, no bearer. */
    public static TurnContext none() {
        return new TurnContext(null, Optional.empty());
    }
}
