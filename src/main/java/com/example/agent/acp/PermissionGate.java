package com.example.agent.acp;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consulted by the ACP-only tool wrappers ({@code AcpToolPermissionConfig}) before a mutating
 * tool call runs: sends {@code session/request_permission} to the client and turns the answer
 * into either "proceed" ({@code Optional.empty()}) or a {@code ToolResult} the model sees
 * instead of the tool running at all.
 *
 * <p>{@code allow_always}/{@code reject_always} are remembered per (sessionId, toolName) for
 * the life of the session — never across sessions, and never persisted (ACP sessions are
 * in-memory only; see {@link AcpSessionBridge}).
 *
 * <p>A client that cancels the turn while a permission request is pending (ACP's {@code
 * session/cancel}) resolves the pending {@code session/request_permission} itself with outcome
 * {@code cancelled} (the protocol's own mechanism for unblocking the agent); this is treated as
 * a refusal for this one call, and the independent cancel flag {@link AcpSessionBridge} already
 * tracks stops the turn itself on the next {@code session/update} — see {@code
 * AcpStreamAdapter#checkCancelled}. No new cancellation plumbing is needed here.
 */
@Component
@Profile("acp")
public class PermissionGate {

    private final AcpSessionBridge sessions;
    private final Set<AcpSchema.ToolKind> requiredKinds;

    private final Map<String, Set<String>> alwaysAllowed = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> alwaysRejected = new ConcurrentHashMap<>();
    /**
     * Calls the user just approved through {@link #ask}, so {@link #check} does not ask twice.
     * Held only while that call runs: {@link #forget} clears it afterwards, whether or not the
     * tool consulted the gate.
     */
    private final Set<String> approvedCalls = ConcurrentHashMap.newKeySet();

    public PermissionGate(AcpSessionBridge sessions, AgentProperties props) {
        this.sessions = sessions;
        EnumSet<AcpSchema.ToolKind> kinds = EnumSet.noneOf(AcpSchema.ToolKind.class);
        for (String raw : props.getAcp().getPermission().getRequiredKinds()) {
            parseKind(raw).ifPresent(kinds::add);
        }
        // Floor: a deployment can widen this, never narrow it below `execute` (CLAUDE.md
        // "Sandboxing invariants" — shell/git must never run unprompted in ACP mode).
        kinds.add(AcpSchema.ToolKind.EXECUTE);
        this.requiredKinds = Set.copyOf(kinds);
    }

    /**
     * @return a {@link ToolResult} the caller must return instead of running the tool (a
     *         REFUSED or ERROR outcome), or empty when the call may proceed.
     */
    public Optional<ToolResult> check(String sessionId,
                                      String callId,
                                      String toolName,
                                      AcpSchema.ToolKind kind,
                                      String title,
                                      List<AcpSchema.ToolCallLocation> locations,
                                      Object rawInput) {
        if (approvedCalls.remove(sessionId + "\u0000" + callId)) {
            return Optional.empty();
        }
        if (!requiredKinds.contains(kind)) {
            return Optional.empty();
        }
        if (alwaysAllowed.getOrDefault(sessionId, Set.of()).contains(toolName)) {
            return Optional.empty();
        }
        if (alwaysRejected.getOrDefault(sessionId, Set.of()).contains(toolName)) {
            return Optional.of(refused(callId, toolName));
        }

        SyncPromptContext ctx = sessions.promptContext(sessionId);
        if (ctx == null) {
            return Optional.of(ToolResult.error(callId,
                    "No ACP permission context for session " + sessionId + "; cannot ask for permission."));
        }

        AcpSchema.ToolCallUpdate toolCall = new AcpSchema.ToolCallUpdate(
                callId, title, kind, AcpSchema.ToolCallStatus.PENDING,
                List.of(), locations == null ? List.of() : locations, rawInput, null);
        AcpSchema.RequestPermissionRequest request = new AcpSchema.RequestPermissionRequest(
                sessionId, toolCall, OPTIONS);

        AcpSchema.RequestPermissionResponse response = ctx.requestPermission(request);
        return resolve(response.outcome(), sessionId, toolName, callId);
    }

    /**
     * Asks about one call whatever its kind, for a tool whose {@code agent.tools.policy} is
     * {@code ask} (#179). The same four options and the same per-session memory as {@link #check};
     * an approval here also covers that call's own {@link #check}, so a gated tool is not asked
     * about twice.
     */
    public Optional<ToolResult> ask(String sessionId, String callId, String toolName, Object rawInput) {
        Optional<ToolResult> refused;
        if (alwaysAllowed.getOrDefault(sessionId, Set.of()).contains(toolName)) {
            refused = Optional.empty();
        } else if (alwaysRejected.getOrDefault(sessionId, Set.of()).contains(toolName)) {
            refused = Optional.of(refused(callId, toolName));
        } else {
            SyncPromptContext ctx = sessions.promptContext(sessionId);
            if (ctx == null) {
                return Optional.of(ToolResult.error(callId,
                        "No ACP permission context for session " + sessionId + "; cannot ask for permission."));
            }
            AcpSchema.ToolCallUpdate toolCall = new AcpSchema.ToolCallUpdate(
                    callId, toolName, AcpSchema.ToolKind.OTHER, AcpSchema.ToolCallStatus.PENDING,
                    List.of(), List.of(), rawInput, null);
            refused = resolve(ctx.requestPermission(new AcpSchema.RequestPermissionRequest(sessionId, toolCall, OPTIONS)).outcome(),
                    sessionId, toolName, callId);
        }
        if (refused.isEmpty()) {
            approvedCalls.add(sessionId + "\u0000" + callId);
        }
        return refused;
    }

    /** Drops the {@link #ask} approval for one call once it has run. */
    public void forget(String sessionId, String callId) {
        approvedCalls.remove(sessionId + "\u0000" + callId);
    }

    /** For tests: how many {@link #ask} approvals are being held. */
    int heldApprovals() {
        return approvedCalls.size();
    }

    private Optional<ToolResult> resolve(AcpSchema.RequestPermissionOutcome outcome,
                                         String sessionId, String toolName, String callId) {
        if (outcome instanceof AcpSchema.PermissionCancelled) {
            return Optional.of(refused(callId, toolName));
        }
        if (outcome instanceof AcpSchema.PermissionSelected selected) {
            return switch (selected.optionId()) {
                case "allow_once" -> Optional.empty();
                case "allow_always" -> {
                    remember(alwaysAllowed, sessionId, toolName);
                    yield Optional.empty();
                }
                case "reject_once" -> Optional.of(refused(callId, toolName));
                case "reject_always" -> {
                    remember(alwaysRejected, sessionId, toolName);
                    yield Optional.of(refused(callId, toolName));
                }
                default -> Optional.of(ToolResult.error(callId,
                        "Unknown permission option id: " + selected.optionId()));
            };
        }
        return Optional.of(ToolResult.error(callId, "Unrecognised session/request_permission outcome: " + outcome));
    }

    private static void remember(Map<String, Set<String>> memory, String sessionId, String toolName) {
        memory.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(toolName);
    }

    private static ToolResult refused(String callId, String toolName) {
        return ToolResult.ok(callId, "Refused: the user did not allow `" + toolName + "` for this call.");
    }

    private static Optional<AcpSchema.ToolKind> parseKind(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            return Optional.of(AcpSchema.ToolKind.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static final List<AcpSchema.PermissionOption> OPTIONS = List.of(
            new AcpSchema.PermissionOption("allow_once", "Allow once", AcpSchema.PermissionOptionKind.ALLOW_ONCE),
            new AcpSchema.PermissionOption("allow_always", "Always allow", AcpSchema.PermissionOptionKind.ALLOW_ALWAYS),
            new AcpSchema.PermissionOption("reject_once", "Reject", AcpSchema.PermissionOptionKind.REJECT_ONCE),
            new AcpSchema.PermissionOption("reject_always", "Always reject", AcpSchema.PermissionOptionKind.REJECT_ALWAYS));
}
