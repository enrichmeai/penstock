package com.example.agent.llm;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;
import java.net.URI;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The audit trail's reason for a failed llm_call, derived from what the call threw. */
class LlmFailureReasonTest {

    private static final String PROVIDER = "openai";

    private static WebClientResponseException status(int status, String body) {
        return WebClientResponseException.create(status, "", new HttpHeaders(), body.getBytes(UTF_8), UTF_8);
    }

    @Test
    void eachRefusalNamesItself() throws Exception {
        assertEquals(LlmFailureReason.BUDGET_EXCEEDED, LlmFailureReason.of(GatewayRefusedException.from(PROVIDER,
                LiteLlmFixtures.load(LiteLlmFixtures.BUDGET_EXCEEDED).asException())));
        assertEquals(LlmFailureReason.RATE_LIMITED, LlmFailureReason.of(GatewayRefusedException.from(PROVIDER,
                LiteLlmFixtures.load(LiteLlmFixtures.RATE_LIMITED).asException())));
        assertEquals(LlmFailureReason.GATEWAY_REFUSED, LlmFailureReason.of(GatewayRefusedException.from(PROVIDER,
                status(429, "not even json"))));
    }

    @Test
    void otherFailuresAreClassifiedByType() {
        assertEquals(LlmFailureReason.PROVIDER_ERROR,
                LlmFailureReason.of(new ProviderErrorException(PROVIDER, status(502, "bad gateway"))));
        assertEquals(LlmFailureReason.PROVIDER_ERROR,
                LlmFailureReason.of(status(500, "raw, from a path that did not translate")));
        WebClientRequestException network = new WebClientRequestException(new ConnectException("refused"),
                HttpMethod.POST, URI.create("http://litellm:4000/v1/chat/completions"), new HttpHeaders());
        assertEquals(LlmFailureReason.UNREACHABLE, LlmFailureReason.of(new ProviderUnreachableException(PROVIDER, network)));
        assertEquals(LlmFailureReason.UNREACHABLE, LlmFailureReason.of(network));
        assertEquals(LlmFailureReason.UNEXPECTED, LlmFailureReason.of(new IllegalStateException("no credential")));
    }

    @Test
    void wireFormIsSnakeCase() {
        assertEquals("budget_exceeded", LlmFailureReason.BUDGET_EXCEEDED.wire());
        assertEquals("gateway_refused", LlmFailureReason.GATEWAY_REFUSED.wire());
        assertEquals("unexpected", LlmFailureReason.UNEXPECTED.wire());
    }
}
