package com.example.agent.service;

import java.time.Instant;
import java.util.Map;

/**
 * One tool call waiting for the user's answer under {@code agent.tools.policy.<tool>=ask} (#179):
 * sent on the chat stream as an {@code approval} event and listed by
 * {@code GET /api/sessions/{id}/approvals}. Answered with
 * {@code POST /api/sessions/{id}/approvals/{callId}}; after {@code expiresAt} it counts as denied.
 */
public record PendingApproval(String sessionId, String callId, String tool,
                              Map<String, Object> arguments, Instant expiresAt) {
}
