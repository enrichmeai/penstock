package com.example.agent.llm;

import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * The provider answered with an error status that is not a refusal: a 5xx after the retries
 * ran out, or a 4xx that retrying cannot fix. Message includes the upstream body for the log;
 * the REST layer never exposes it (no {@code @SafeMessage}).
 */
public final class ProviderErrorException extends LlmProviderException {

    private final int status;

    ProviderErrorException(String provider, WebClientResponseException upstream) {
        super(provider,
                LlmMessage.PROVIDER_ERROR.format(provider, upstream.getStatusCode().value(),
                        upstream.getResponseBodyAsString()),
                upstream);
        this.status = upstream.getStatusCode().value();
    }

    public int status() {
        return status;
    }
}
