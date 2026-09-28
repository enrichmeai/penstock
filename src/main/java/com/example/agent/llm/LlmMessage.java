package com.example.agent.llm;

/**
 * Message catalogue for the llm package. Every sentence a client, a log line or an exception
 * can carry lives here as a {@link String#format} template; a call site references a constant
 * and never assembles text itself. Entries whose text a client sees are the ones the UI
 * shows verbatim, so the wording is a contract, not decoration.
 */
public enum LlmMessage {
    /** A gateway refused the call because a budget is exhausted; a retry cannot clear it. */
    GATEWAY_REFUSED_BUDGET_EXCEEDED("The AI gateway refused this call: budget exhausted."),
    /** A gateway refused the call because a rate limit is reached; no Retry-After was given. */
    GATEWAY_REFUSED_RATE_LIMITED("The AI gateway refused this call: rate limit reached."),
    /** {@code %d} seconds, from the gateway's Retry-After. */
    GATEWAY_REFUSED_RATE_LIMITED_RETRY_AFTER(
            "The AI gateway refused this call: rate limit reached; try again in %d seconds."),
    /** A 429 whose body this service could not classify. */
    GATEWAY_REFUSED("The AI gateway refused this call."),
    /** {@code %s} provider, {@code %d} upstream status, {@code %s} upstream body. */
    PROVIDER_ERROR("%s API error %d: %s"),
    /** {@code %s} provider, {@code %s} cause. */
    PROVIDER_UNREACHABLE("%s network error: %s"),
    /** {@code %s} provider, {@code %d} attempts. */
    RETRY_EXHAUSTED("%s: retry exhausted after %d attempts"),
    /** {@code %s} provider, {@code %d} status, {@code %d} attempt, {@code %d} max, {@code %d} backoff ms. */
    RETRYING_AFTER_STATUS("%s API %d (attempt %d/%d), sleeping %dms"),
    /** {@code %s} provider, {@code %d} attempt, {@code %d} max, {@code %s} cause. */
    RETRYING_AFTER_NETWORK_ERROR("%s network error (attempt %d/%d): %s");

    private final String template;

    LlmMessage(String template) {
        this.template = template;
    }

    public String format(Object... args) {
        return String.format(template, args);
    }
}
