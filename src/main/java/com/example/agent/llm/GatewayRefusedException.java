package com.example.agent.llm;

import com.example.agent.config.SafeMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Optional;

/**
 * The model gateway refused this call — a budget or a rate limit said no before any model
 * ran. Not an internal error: nothing in this service failed, and the user is owed the
 * gateway's decision, not a stack trace.
 *
 * <p>Built from the gateway's actual 429 response: {@link #refusal()} is parsed from the body's
 * {@code error.type}, {@link #retryAfter()} from the header when the gateway sent one, and the
 * upstream error object is kept whole for the log. The message is from the catalogue and is
 * safe for a client; the gateway's own text can carry a key hash and stays in {@link #upstream()}.
 */
@SafeMessage
public final class GatewayRefusedException extends LlmProviderException {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ERROR_FIELD = "error";
    private static final String TYPE_FIELD = "type";
    private static final String CODE_FIELD = "code";
    private static final String MESSAGE_FIELD = "message";

    private final GatewayRefusal refusal;
    private final int upstreamStatus;
    private final Optional<Duration> retryAfter;
    private final UpstreamError upstream;

    private GatewayRefusedException(String provider, GatewayRefusal refusal, int upstreamStatus,
                                    Optional<Duration> retryAfter, UpstreamError upstream,
                                    WebClientResponseException cause) {
        super(provider, refusal.message(retryAfter), cause);
        this.refusal = refusal;
        this.upstreamStatus = upstreamStatus;
        this.retryAfter = retryAfter;
        this.upstream = upstream;
    }

    /** Reads the refusal off the gateway's response. The caller has established that it is a 429. */
    public static GatewayRefusedException from(String provider, WebClientResponseException upstream) {
        UpstreamError body = UpstreamError.parse(upstream.getResponseBodyAsString());
        return new GatewayRefusedException(provider,
                GatewayRefusal.fromUpstreamType(body.type()),
                upstream.getStatusCode().value(),
                RetryAfter.parse(upstream.getHeaders()),
                body,
                upstream);
    }

    public GatewayRefusal refusal() {
        return refusal;
    }

    public int upstreamStatus() {
        return upstreamStatus;
    }

    /** The gateway's Retry-After, when it sent one (LiteLLM does for a rate limit, not for a budget). */
    public Optional<Duration> retryAfter() {
        return retryAfter;
    }

    /** The gateway's own error object — for the log, never for a client. */
    public UpstreamError upstream() {
        return upstream;
    }

    /**
     * The {@code error} object of a LiteLLM error body: {@code type}, {@code code}, {@code message}.
     * Any field the body lacks is null; a body that is not JSON at all yields three nulls.
     */
    public record UpstreamError(String type, String code, String message) {

        static UpstreamError parse(String body) {
            if (body == null || body.isBlank()) {
                return new UpstreamError(null, null, null);
            }
            try {
                JsonNode error = JSON.readTree(body).path(ERROR_FIELD);
                return new UpstreamError(text(error, TYPE_FIELD), text(error, CODE_FIELD), text(error, MESSAGE_FIELD));
            } catch (JsonProcessingException notJson) {
                return new UpstreamError(null, null, null);
            }
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.path(field);
            return value.isTextual() ? value.asText() : null;
        }
    }
}
