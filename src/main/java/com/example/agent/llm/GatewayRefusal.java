package com.example.agent.llm;

import com.fasterxml.jackson.annotation.JsonValue;

import java.time.Duration;
import java.util.Optional;

/**
 * Why a model gateway refused a call, as far as this service can tell from the response.
 *
 * <p>Parsed from the {@code error.type} of a LiteLLM 429 body; the captured responses under
 * {@code src/test/resources/fixtures/litellm/} are the contract: {@code "budget_exceeded"} for
 * an exhausted budget, {@code "throttling_error"} for a rate limit. {@code error.code} is
 * {@code "429"} in both captures and so cannot discriminate; it is kept on the exception for
 * the log, not consulted here. Any other 429 is {@link #UNKNOWN}: still a refusal, still not
 * this service's fault, and retried the way every 429 was before this type existed.
 *
 * <p>The wire form is the {@code reason} a client sees next to {@code code: gateway_refused}.
 */
public enum GatewayRefusal {
    BUDGET_EXCEEDED("budget_exceeded", "budget_exceeded", false, LlmFailureReason.BUDGET_EXCEEDED),
    RATE_LIMITED("rate_limited", "throttling_error", true, LlmFailureReason.RATE_LIMITED),
    UNKNOWN("unknown", null, true, LlmFailureReason.GATEWAY_REFUSED);

    private final String wire;
    private final String upstreamType;
    private final boolean retryable;
    private final LlmFailureReason failureReason;

    GatewayRefusal(String wire, String upstreamType, boolean retryable, LlmFailureReason failureReason) {
        this.wire = wire;
        this.upstreamType = upstreamType;
        this.retryable = retryable;
        this.failureReason = failureReason;
    }

    /** The refusal a LiteLLM {@code error.type} names; {@link #UNKNOWN} for anything else, null included. */
    public static GatewayRefusal fromUpstreamType(String type) {
        for (GatewayRefusal candidate : values()) {
            if (candidate.upstreamType != null && candidate.upstreamType.equals(type)) {
                return candidate;
            }
        }
        return UNKNOWN;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /**
     * Whether waiting and calling again can change the answer. A rate limit clears with time;
     * an exhausted budget does not, so retrying it only delays telling the user.
     */
    public boolean isRetryable() {
        return retryable;
    }

    /** The same refusal as the audit trail records it. */
    public LlmFailureReason failureReason() {
        return failureReason;
    }

    /** The sentence a client sees. Never carries anything from the gateway's own message. */
    public String message(Optional<Duration> retryAfter) {
        return switch (this) {
            case BUDGET_EXCEEDED -> LlmMessage.GATEWAY_REFUSED_BUDGET_EXCEEDED.format();
            case RATE_LIMITED -> retryAfter
                    .map(wait -> LlmMessage.GATEWAY_REFUSED_RATE_LIMITED_RETRY_AFTER.format(wait.toSeconds()))
                    .orElseGet(LlmMessage.GATEWAY_REFUSED_RATE_LIMITED::format);
            case UNKNOWN -> LlmMessage.GATEWAY_REFUSED.format();
        };
    }
}
