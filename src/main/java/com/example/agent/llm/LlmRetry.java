package com.example.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Simple retry-with-exponential-backoff for LLM calls. Retries on:
 *   - HTTP 429 (rate-limited)
 *   - HTTP 5xx (server errors)
 *   - Network-level IO errors (connect reset, etc)
 * Does NOT retry on 4xx-other (bad request, auth, etc) since those are programmatic errors.
 */
public final class LlmRetry {

    private static final Logger log = LoggerFactory.getLogger(LlmRetry.class);

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMillis(500);
    private static final int BACKOFF_MULTIPLIER = 2;

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
        RuntimeException last = null;
        Duration delay = initialDelay;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (WebClientResponseException ex) {
                int status = ex.getStatusCode().value();
                boolean retryable = status == 429 || status >= 500;
                if (!retryable || attempt == maxAttempts) {
                    throw new RuntimeException(providerName + " API error " + status
                            + ": " + ex.getResponseBodyAsString(), ex);
                }
                // Honor Retry-After header when present
                Duration backoff = RetryAfter.parse(ex.getHeaders()).orElse(delay);
                log.warn("{} API {} (attempt {}/{}), sleeping {}ms",
                        providerName, status, attempt, maxAttempts, backoff.toMillis());
                sleeper.accept(backoff);
                delay = delay.multipliedBy(BACKOFF_MULTIPLIER);
                last = ex;
            } catch (WebClientRequestException ex) {
                if (attempt == maxAttempts) {
                    throw new RuntimeException(providerName + " network error: " + ex.getMessage(), ex);
                }
                log.warn("{} network error (attempt {}/{}): {}", providerName, attempt, maxAttempts, ex.getMessage());
                sleeper.accept(delay);
                delay = delay.multipliedBy(BACKOFF_MULTIPLIER);
                last = ex;
            }
        }
        throw last == null ? new IllegalStateException("retry exhausted") : last;
    }

    private static void sleep(Duration d) {
        try { Thread.sleep(d.toMillis()); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
