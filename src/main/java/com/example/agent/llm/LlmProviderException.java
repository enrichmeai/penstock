package com.example.agent.llm;

/**
 * A model call that produced no completion, typed by what went wrong. Every subtype names the
 * provider and keeps the upstream exception as its cause, so a log line has the whole story
 * while the message on the exception is the part a caller may act on.
 */
public abstract sealed class LlmProviderException extends RuntimeException
        permits GatewayRefusedException, ProviderErrorException, ProviderUnreachableException {

    private final String provider;

    protected LlmProviderException(String provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
    }

    /** The provider name as {@link LlmProvider#name()} reports it. */
    public String provider() {
        return provider;
    }
}
