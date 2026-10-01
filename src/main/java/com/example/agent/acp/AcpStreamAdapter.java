package com.example.agent.acp;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.Role;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolResult;
import com.example.agent.service.AgentService;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Bridges one {@code AgentService.chatStreaming} turn onto ACP {@code session/update}
 * notifications: {@code onToken} becomes {@code agent_message_chunk}, an assistant message
 * with tool calls becomes one {@code tool_call} per call, and the following TOOL message
 * becomes one {@code tool_call_update} per result (CLAUDE.md "Architecture" — tool kinds:
 * {@code read_file}/{@code list_dir} to {@code read}, {@code glob}/{@code grep} to
 * {@code search}, {@code pod} to {@code fetch}).
 *
 * <p>{@code stopReason()} is classified from the sentinel prefixes {@code AgentService}
 * appends to its own budget/turn-cap messages, rather than re-deriving the limits here —
 * see {@link AgentService#SESSION_BUDGET_EXCEEDED_PREFIX} and neighbours.
 */
final class AcpStreamAdapter {

    private final SyncPromptContext ctx;
    private final String sessionId;
    private final BooleanSupplier cancelled;
    private volatile AcpSchema.StopReason stopReason = AcpSchema.StopReason.END_TURN;

    AcpStreamAdapter(SyncPromptContext ctx, String sessionId, BooleanSupplier cancelled) {
        this.ctx = ctx;
        this.sessionId = sessionId;
        this.cancelled = cancelled;
    }

    AcpSchema.StopReason stopReason() {
        return stopReason;
    }

    void onToken(String token) {
        checkCancelled();
        if (token == null || token.isEmpty()) return;
        ctx.sendUpdate(sessionId, new AcpSchema.AgentMessageChunk("agent_message_chunk", new AcpSchema.TextContent(token)));
    }

    void onMessage(ChatMessage message) {
        checkCancelled();
        if (message.role() == Role.ASSISTANT) {
            stopReason = classify(message.text());
            for (ToolCall call : message.toolCalls()) {
                ctx.sendUpdate(sessionId, new AcpSchema.ToolCall(
                        "tool_call", call.id(), call.name(), kindOf(call.name(), call.arguments()),
                        AcpSchema.ToolCallStatus.IN_PROGRESS, List.of(), List.of(),
                        call.arguments(), null, null));
            }
        } else if (message.role() == Role.TOOL) {
            for (ToolResult result : message.toolResults()) {
                ctx.sendUpdate(sessionId, new AcpSchema.ToolCallUpdateNotification(
                        "tool_call_update", result.callId(), null, null,
                        result.isError() ? AcpSchema.ToolCallStatus.FAILED : AcpSchema.ToolCallStatus.COMPLETED,
                        List.of(new AcpSchema.ToolCallContentBlock("content", new AcpSchema.TextContent(result.content()))),
                        List.of(), null, result.content(), null));
            }
        }
    }

    private void checkCancelled() {
        if (cancelled.getAsBoolean()) {
            throw new AcpCancelledException();
        }
    }

    /**
     * Matches the model's final text against {@code AgentService}'s own sentinel prefixes
     * rather than a typed signal, since the loop returns none today. A model asked to echo
     * one of these exact prefixes verbatim would be misclassified; low-probability (they're
     * distinctive synthetic strings, not something a prompt would normally provoke) but a
     * real gap, flagged rather than silently accepted.
     */
    static AcpSchema.StopReason classify(String text) {
        if (text != null) {
            if (text.startsWith(AgentService.MAX_TURNS_REACHED_PREFIX)) {
                return AcpSchema.StopReason.MAX_TURN_REQUESTS;
            }
            if (text.startsWith(AgentService.REQUEST_BUDGET_REACHED_PREFIX)
                    || text.startsWith(AgentService.SESSION_BUDGET_EXCEEDED_PREFIX)) {
                return AcpSchema.StopReason.MAX_TOKENS;
            }
        }
        return AcpSchema.StopReason.END_TURN;
    }

    /**
     * {@code pod} is a single tool whose {@code type} argument picks the operation (CLAUDE.md
     * issue #62 "Surface") — only {@code write} is a mutation, so its kind depends on the call's
     * arguments and not just its name, unlike every other tool here.
     */
    static AcpSchema.ToolKind kindOf(String toolName, java.util.Map<String, Object> arguments) {
        return switch (toolName) {
            case "read_file", "list_dir" -> AcpSchema.ToolKind.READ;
            case "glob", "grep" -> AcpSchema.ToolKind.SEARCH;
            case "write_file", "edit_file" -> AcpSchema.ToolKind.EDIT;
            case "shell", "git" -> AcpSchema.ToolKind.EXECUTE;
            case "pod" -> "write".equals(arguments == null ? null : String.valueOf(arguments.get("type")))
                    ? AcpSchema.ToolKind.EDIT : AcpSchema.ToolKind.FETCH;
            default -> AcpSchema.ToolKind.OTHER;
        };
    }
}
