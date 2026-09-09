package com.example.agent.llm;

import com.example.agent.tools.ToolContext;

/**
 * Who a model call is for. Resolved on the request thread and passed explicitly — the
 * agent loop runs on pooled threads with no SecurityContext — so a provider can choose a
 * gateway credential provisioned for the user and tag the outbound request with the
 * caller's request ID.
 *
 * <p>Deliberately narrower than {@link ToolContext}: the caller's own inbound bearer is not
 * here. A model gateway is authenticated with a key provisioned for the user, never with
 * the token the user presented to this service.
 */
public record LlmCallContext(String userId, String sessionId, String requestId) {

    public LlmCallContext {
        userId = (userId == null || userId.isBlank()) ? ToolContext.ANONYMOUS : userId;
        requestId = (requestId == null || requestId.isBlank()) ? null : requestId;
    }

    /** For callers that know only the session — the pre-existing provider entry points. */
    public static LlmCallContext forSession(String sessionId) {
        return new LlmCallContext(null, sessionId, null);
    }
}
