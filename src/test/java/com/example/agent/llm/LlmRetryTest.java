package com.example.agent.llm;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What {@link LlmRetry} does with each kind of upstream failure, against the gateway's real
 * responses. The sleeper is recorded rather than slept, so the captured {@code Retry-After: 60}
 * is asserted and never waited for.
 */
class LlmRetryTest {

    private static final String PROVIDER = "openai";
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration INITIAL_DELAY = Duration.ofMillis(1);
    private static final Duration CAPTURED_RETRY_AFTER = Duration.ofSeconds(60);
    private static final String UPSTREAM_BODY_DETAIL = "Team=support";
    private static final String UPSTREAM_KEY_HASH_MARKER = "api_key";

    private final List<Duration> sleeps = new ArrayList<>();
    private final AtomicInteger attempts = new AtomicInteger();

    private <T> T call(Supplier<T> action) {
        return LlmRetry.call(PROVIDER, () -> {
            attempts.incrementAndGet();
            return action.get();
        }, MAX_ATTEMPTS, INITIAL_DELAY, sleeps::add);
    }

    private static Supplier<String> failingWith(RuntimeException failure) {
        return () -> { throw failure; };
    }

    private static WebClientResponseException status(int status, String reason, String body) {
        return WebClientResponseException.create(status, reason, new HttpHeaders(), body.getBytes(UTF_8), UTF_8);
    }

    @Test
    void aBudgetRefusalIsTypedAndNotRetried() throws Exception {
        WebClientResponseException upstream = LiteLlmFixtures.load(LiteLlmFixtures.BUDGET_EXCEEDED).asException();

        GatewayRefusedException refused = assertThrows(GatewayRefusedException.class,
                () -> call(failingWith(upstream)));

        assertEquals(GatewayRefusal.BUDGET_EXCEEDED, refused.refusal());
        assertEquals(1, attempts.get(), "backoff cannot clear an exhausted budget; retrying only delays the answer");
        assertTrue(sleeps.isEmpty(), "no backoff at all");
        assertEquals(PROVIDER, refused.provider());
        assertEquals(429, refused.upstreamStatus());
        assertEquals(Optional.empty(), refused.retryAfter(), "the captured budget refusal carries no Retry-After");
        assertEquals("budget_exceeded", refused.upstream().type());
        assertEquals("429", refused.upstream().code());
        assertEquals(LlmMessage.GATEWAY_REFUSED_BUDGET_EXCEEDED.format(), refused.getMessage());
        assertFalse(refused.getMessage().contains(UPSTREAM_BODY_DETAIL), "the gateway's own text stays out of the client-facing message");
        assertSame(upstream, refused.getCause(), "the upstream response is kept for the log");
    }

    @Test
    void aRateLimitIsRetriedOnTheGatewaysScheduleThenTyped() throws Exception {
        WebClientResponseException upstream = LiteLlmFixtures.load(LiteLlmFixtures.RATE_LIMITED).asException();

        GatewayRefusedException refused = assertThrows(GatewayRefusedException.class,
                () -> call(failingWith(upstream)));

        assertEquals(GatewayRefusal.RATE_LIMITED, refused.refusal());
        assertEquals(MAX_ATTEMPTS, attempts.get(), "today's retry policy for a rate limit is kept");
        assertEquals(List.of(CAPTURED_RETRY_AFTER, CAPTURED_RETRY_AFTER), sleeps,
                "the gateway's Retry-After wins over the exponential schedule");
        assertEquals(Optional.of(CAPTURED_RETRY_AFTER), refused.retryAfter());
        assertEquals("throttling_error", refused.upstream().type());
        assertEquals(LlmMessage.GATEWAY_REFUSED_RATE_LIMITED_RETRY_AFTER.format(CAPTURED_RETRY_AFTER.toSeconds()),
                refused.getMessage());
        assertFalse(refused.getMessage().contains(UPSTREAM_KEY_HASH_MARKER),
                "the key hash in the gateway's message never reaches a client");
    }

    @Test
    void aRateLimitThatClearsReturnsTheResult() throws Exception {
        WebClientResponseException upstream = LiteLlmFixtures.load(LiteLlmFixtures.RATE_LIMITED).asException();
        AtomicInteger remainingRefusals = new AtomicInteger(1);

        String result = call(() -> {
            if (remainingRefusals.getAndDecrement() > 0) throw upstream;
            return "completion";
        });

        assertEquals("completion", result);
        assertEquals(2, attempts.get());
        assertEquals(List.of(CAPTURED_RETRY_AFTER), sleeps);
    }

    @Test
    void anUnclassifiable429IsARefusalOfUnknownReasonAndStillRetried() {
        WebClientResponseException upstream = status(429, "Too Many Requests", "{\"error\":{\"type\":\"something_new\"}}");

        GatewayRefusedException refused = assertThrows(GatewayRefusedException.class,
                () -> call(failingWith(upstream)));

        assertEquals(GatewayRefusal.UNKNOWN, refused.refusal());
        assertEquals(MAX_ATTEMPTS, attempts.get());
        assertEquals(List.of(INITIAL_DELAY, INITIAL_DELAY.multipliedBy(2)), sleeps, "no Retry-After: the exponential schedule");
        assertEquals(LlmMessage.GATEWAY_REFUSED.format(), refused.getMessage());
    }

    @Test
    void aServerErrorIsRetriedThenTypedAsAProviderError() {
        WebClientResponseException upstream = status(503, "Service Unavailable", "upstream down");

        ProviderErrorException failure = assertThrows(ProviderErrorException.class,
                () -> call(failingWith(upstream)));

        assertEquals(503, failure.status());
        assertEquals(MAX_ATTEMPTS, attempts.get());
        assertEquals(List.of(INITIAL_DELAY, INITIAL_DELAY.multipliedBy(2)), sleeps);
        assertEquals(LlmMessage.PROVIDER_ERROR.format(PROVIDER, 503, "upstream down"), failure.getMessage());
        assertSame(upstream, failure.getCause());
    }

    @Test
    void aClientErrorIsNotRetried() {
        WebClientResponseException upstream = status(401, "Unauthorized", "{\"error\":\"bad key\"}");

        ProviderErrorException failure = assertThrows(ProviderErrorException.class,
                () -> call(failingWith(upstream)));

        assertEquals(401, failure.status());
        assertEquals(1, attempts.get());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void aNetworkErrorIsRetriedThenTypedAsUnreachable() {
        WebClientRequestException upstream = new WebClientRequestException(new ConnectException("Connection refused"),
                HttpMethod.POST, URI.create("http://litellm:4000/v1/chat/completions"), new HttpHeaders());

        ProviderUnreachableException failure = assertThrows(ProviderUnreachableException.class,
                () -> call(failingWith(upstream)));

        assertEquals(PROVIDER, failure.provider());
        assertEquals(MAX_ATTEMPTS, attempts.get());
        assertEquals(List.of(INITIAL_DELAY, INITIAL_DELAY.multipliedBy(2)), sleeps);
        assertSame(upstream, failure.getCause());
    }
}
