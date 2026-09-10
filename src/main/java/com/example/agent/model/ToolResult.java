package com.example.agent.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/**
 * Result of executing a {@link ToolCall}.
 *
 * <p>On the wire ({@code toolResults[]} in REST responses and SSE messages, the stored
 * message payload, the exported transcript) this is {@code callId}, {@code content},
 * {@code outcome} and — kept for clients written before outcomes existed — {@code isError},
 * which is {@code outcome == ERROR}. A payload with no {@code outcome} is read by its
 * {@code isError}, so what was stored before this field existed still loads.
 *
 * @param callId   The id of the originating tool call.
 * @param content  The textual content to return to the model.
 * @param outcome  How the call ended: {@link ToolOutcome#OK}, {@link ToolOutcome#REFUSED}
 *                 or {@link ToolOutcome#ERROR}.
 */
@JsonPropertyOrder({"callId", "content", "outcome", "isError"})
public record ToolResult(String callId, String content, ToolOutcome outcome) {

    public ToolResult {
        Objects.requireNonNull(outcome, "outcome");
    }

    public static ToolResult ok(String callId, String content) {
        return new ToolResult(callId, content, ToolOutcome.OK);
    }

    /** The far system said no. {@code content} tells the model so, and what not to do about it. */
    public static ToolResult refused(String callId, String content) {
        return new ToolResult(callId, content, ToolOutcome.REFUSED);
    }

    public static ToolResult error(String callId, String message) {
        return new ToolResult(callId, message, ToolOutcome.ERROR);
    }

    /** Derived; on the wire as {@code isError} for compatibility. A refusal is not an error. */
    @JsonProperty("isError")
    public boolean isError() {
        return outcome == ToolOutcome.ERROR;
    }

    /** Reads today's payload, and the one written before {@code outcome} existed. */
    @JsonCreator
    static ToolResult fromJson(@JsonProperty("callId") String callId,
                               @JsonProperty("content") String content,
                               @JsonProperty("outcome") ToolOutcome outcome,
                               @JsonProperty("isError") Boolean isError) {
        ToolOutcome resolved = outcome != null ? outcome
                : Boolean.TRUE.equals(isError) ? ToolOutcome.ERROR : ToolOutcome.OK;
        return new ToolResult(callId, content, resolved);
    }
}
