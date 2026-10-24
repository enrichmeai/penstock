package com.example.agent.acp;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.Role;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolResult;
import com.example.agent.service.AgentService;

import com.example.agent.model.PlanItem;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

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
    private final Supplier<List<PlanItem>> plan;
    /** Ids of {@code plan} tool calls in flight, so their results also send the editor a {@code plan} update. */
    private final Set<String> planCalls = ConcurrentHashMap.newKeySet();

    AcpStreamAdapter(SyncPromptContext ctx, String sessionId, BooleanSupplier cancelled) {
        this(ctx, sessionId, cancelled, List::of);
    }

    AcpStreamAdapter(SyncPromptContext ctx, String sessionId, BooleanSupplier cancelled, Supplier<List<PlanItem>> plan) {
        this.ctx = ctx;
        this.sessionId = sessionId;
        this.cancelled = cancelled;
        this.plan = plan;
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
                // A plan change (set or update) refreshes the editor's plan; a read changes nothing.
                if ("plan".equals(call.name()) && call.arguments() != null
                        && !"read".equals(String.valueOf(call.arguments().get("operation")))) {
                    planCalls.add(call.id());
                }
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
                if (planCalls.remove(result.callId()) && !result.isError()) {
                    sendPlan();
                }
            }
        }
    }

    /** The session's plan (#174) as ACP's own {@code plan} update, which editors show as a checklist. */
    private void sendPlan() {
        List<AcpSchema.PlanEntry> entries = plan.get().stream()
                .map(i -> new AcpSchema.PlanEntry(i.text(), AcpSchema.PlanEntryPriority.MEDIUM, switch (i.status()) {
                    case PlanItem.DONE -> AcpSchema.PlanEntryStatus.COMPLETED;
                    case PlanItem.IN_PROGRESS -> AcpSchema.PlanEntryStatus.IN_PROGRESS;
                    default -> AcpSchema.PlanEntryStatus.PENDING;
                }))
                .toList();
        ctx.sendUpdate(sessionId, new AcpSchema.Plan("plan", entries));
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
            case "read_file", "list_dir", "test_results" -> AcpSchema.ToolKind.READ;
            case "glob", "grep" -> AcpSchema.ToolKind.SEARCH;
            case "write_file", "edit_file" -> AcpSchema.ToolKind.EDIT;
            case "shell", "git" -> AcpSchema.ToolKind.EXECUTE;
            case "pod" -> "write".equals(arguments == null ? null : String.valueOf(arguments.get("type")))
                    ? AcpSchema.ToolKind.EDIT : AcpSchema.ToolKind.FETCH;
            default -> AcpSchema.ToolKind.OTHER;
        };
    }
}
