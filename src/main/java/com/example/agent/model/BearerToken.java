package com.example.agent.model;

import java.util.Optional;

/**
 * A bearer credential on its way to an outbound call — either the one the signed-in
 * caller presented on the inbound request, or one provisioned in configuration.
 *
 * <p>The type exists so the secret cannot leak by accident. {@link #toString()} is
 * redacted, so a {@code ToolContext} or {@code TurnContext} carrying one can appear in a
 * log line or an exception message without revealing it; only {@link #secret()} and
 * {@link #authorizationHeaderValue()} do, and both belong at the point of sending and
 * nowhere else. Never log either.
 */
public record BearerToken(String secret) {

    private static final String SCHEME = "Bearer ";
    private static final String REDACTED = "BearerToken[redacted]";

    public BearerToken {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("A bearer token must not be blank");
        }
    }

    /** {@code Optional.empty()} for null or blank input, so callers can chain fallbacks. */
    public static Optional<BearerToken> of(String secret) {
        return (secret == null || secret.isBlank()) ? Optional.empty() : Optional.of(new BearerToken(secret));
    }

    /** The {@code Authorization} header value, {@code Bearer <secret>}. */
    public String authorizationHeaderValue() {
        return SCHEME + secret;
    }

    @Override
    public String toString() {
        return REDACTED;
    }
}
