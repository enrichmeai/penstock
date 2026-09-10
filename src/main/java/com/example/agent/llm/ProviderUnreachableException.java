package com.example.agent.llm;

import org.springframework.web.reactive.function.client.WebClientRequestException;

/** No answer from the provider at all — connection refused, reset or timed out — after the retries ran out. */
public final class ProviderUnreachableException extends LlmProviderException {

    ProviderUnreachableException(String provider, WebClientRequestException cause) {
        super(provider, LlmMessage.PROVIDER_UNREACHABLE.format(provider, cause.getMessage()), cause);
    }
}
