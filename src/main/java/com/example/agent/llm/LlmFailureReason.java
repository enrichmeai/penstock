package com.example.agent.llm;

import com.fasterxml.jackson.annotation.JsonValue;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Why an {@code llm_call} produced no completion, for the audit trail. A closed set: the audit
 * row carries the wire form, and nothing downstream has to parse an exception message to learn
 * what happened.
 */
public enum LlmFailureReason {
    /** The gateway refused: a budget is exhausted. */
    BUDGET_EXCEEDED("budget_exceeded"),
    /** The gateway refused: a rate limit is reached. */
    RATE_LIMITED("rate_limited"),
    /** The gateway refused with a 429 this service could not classify further. */
    GATEWAY_REFUSED("gateway_refused"),
    /** The provider answered with an error status other than a refusal. */
    PROVIDER_ERROR("provider_error"),
    /** No answer at all: connection refused, reset, timed out. */
    UNREACHABLE("unreachable"),
    /** Anything else — a bug, or a path that threw an untyped exception. */
    UNEXPECTED("unexpected");

    private final String wire;

    LlmFailureReason(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /**
     * Classifies what a model call threw. Typed provider exceptions decide directly; the raw
     * WebClient exceptions are accepted too, for any path that did not translate them.
     */
    public static LlmFailureReason of(Throwable failure) {
        if (failure instanceof GatewayRefusedException refused) {
            return refused.refusal().failureReason();
        }
        if (failure instanceof ProviderErrorException) {
            return PROVIDER_ERROR;
        }
        if (failure instanceof WebClientResponseException response) {
            return response.getStatusCode().value() == 429 ? GATEWAY_REFUSED : PROVIDER_ERROR;
        }
        if (failure instanceof ProviderUnreachableException || failure instanceof WebClientRequestException) {
            return UNREACHABLE;
        }
        return UNEXPECTED;
    }
}
