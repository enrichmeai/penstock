package com.example.agent.llm;

import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.util.Optional;

/**
 * The {@code Retry-After} header in its delay-seconds form, which is the form LiteLLM sends
 * (captured: {@code retry-after: 60}). The HTTP-date form is not interpreted and reads as
 * absent, exactly as before this class existed.
 */
final class RetryAfter {

    private RetryAfter() {}

    static Optional<Duration> parse(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(value.trim())));
        } catch (NumberFormatException notSeconds) {
            return Optional.empty();
        }
    }
}
