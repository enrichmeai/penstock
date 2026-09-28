package com.example.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Retry-with-exponential-backoff for LLM calls, and the one place an upstream failure gets
 * its type. Retries:
 * <ul>
 *   <li>HTTP 429 when the gateway's refusal can clear with time — a rate limit, or a 429 this
 *       service cannot classify — honouring {@code Retry-After};</li>
 *   <li>HTTP 5xx;</li>
 *   <li>network-level errors (connect refused, reset).</li>
 * </ul>
 * Does not retry a 429 the gateway marks as an exhausted budget: backoff cannot clear it and
 * only delays telling the user. Does not retry other 4xx, which are programmatic errors.
 *
 * <p>What escapes is always an {@link LlmProviderException}: {@link GatewayRefusedException}
 * for a 429, {@link ProviderErrorException} for any other status, and
 * {@link ProviderUnreachableException} when no response came at all. Streaming paths cannot
 * retry without re-emitting delivered tokens, so they do not go through {@link #call}; they
 * type what they catch with {@link #failure} and {@link #unreachable} instead, so a 429 is the
 * same refusal whichever path carried it.
 */
public final class LlmRetry {

    private static final Logger log = LoggerFactory.getLogger(LlmRetry.class);

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMillis(500);
    private static final int BACKOFF_MULTIPLIER = 2;
    private static final int TOO_MANY_REQUESTS = 429;

    private LlmRetry() {}

    public static <T> T call(String providerName, Supplier<T> action) {
        return call(providerName, action, DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_DELAY);
    }

    public static <T> T call(String providerName, Supplier<T> action,
                             int maxAttempts, Duration initialDelay) {
        return call(providerName, action, maxAttempts, initialDelay, LlmRetry::sleep);
    }

    /**
     * @param sleeper how to wait between attempts — {@link Thread#sleep} in production; a test
     *                passes a recorder so a gateway's {@code Retry-After: 60} is asserted, not slept
     */
    public static <T> T call(String providerName, Supplier<T> action,
                             int maxAttempts, Duration initialDelay, Consumer<Duration> sleeper) {
        Duration delay = initialDelay;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (WebClientResponseException ex) {
                LlmProviderException failure = failure(providerName, ex);
                if (!retryable(failure, ex) || attempt == maxAttempts) {
                    throw failure;
                }
                Duration backoff = RetryAfter.parse(ex.getHeaders()).orElse(delay);
                log.warn(LlmMessage.RETRYING_AFTER_STATUS.format(
                        providerName, ex.getStatusCode().value(), attempt, maxAttempts, backoff.toMillis()));
                sleeper.accept(backoff);
                delay = delay.multipliedBy(BACKOFF_MULTIPLIER);
            } catch (WebClientRequestException ex) {
                if (attempt == maxAttempts) {
                    throw unreachable(providerName, ex);
                }
                log.warn(LlmMessage.RETRYING_AFTER_NETWORK_ERROR.format(
                        providerName, attempt, maxAttempts, ex.getMessage()));
                sleeper.accept(delay);
                delay = delay.multipliedBy(BACKOFF_MULTIPLIER);
            }
        }
        throw new IllegalStateException(LlmMessage.RETRY_EXHAUSTED.format(providerName, maxAttempts));
    }

    /**
     * Types a response the provider answered with: a 429 is the gateway's refusal, read off the
     * body; any other error status is a provider error.
     */
    public static LlmProviderException failure(String providerName, WebClientResponseException ex) {
        return ex.getStatusCode().value() == TOO_MANY_REQUESTS
                ? GatewayRefusedException.from(providerName, ex)
                : new ProviderErrorException(providerName, ex);
    }

    /** Types the absence of a response. */
    public static LlmProviderException unreachable(String providerName, WebClientRequestException ex) {
        return new ProviderUnreachableException(providerName, ex);
    }

    private static boolean retryable(LlmProviderException failure, WebClientResponseException ex) {
        if (failure instanceof GatewayRefusedException refused) {
            return refused.refusal().isRetryable();
        }
        return ex.getStatusCode().is5xxServerError();
    }

    private static void sleep(Duration d) {
        try { Thread.sleep(d.toMillis()); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
