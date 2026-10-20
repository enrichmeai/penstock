package com.example.agent.acp;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PermissionGate} in isolation — no stdio process, a mocked {@link SyncPromptContext}
 * standing in for the client. Pins issue #62's memory contract: {@code allow_always}/{@code
 * reject_always} are remembered per (session, tool) only, never across sessions, and {@code
 * allow_once}/{@code reject_once} are never remembered at all.
 */
class PermissionGateTest {

    private AcpSessionBridge sessions;
    private SyncPromptContext ctx;
    private String sessionId;

    @BeforeEach
    void setUp() {
        sessions = new AcpSessionBridge();
        ctx = mock(SyncPromptContext.class);
        sessionId = sessions.create("alice").getId();
        sessions.setPromptContext(sessionId, ctx);
    }

    private PermissionGate gate(String... requiredKinds) {
        AgentProperties props = new AgentProperties();
        if (requiredKinds.length > 0) {
            props.getAcp().getPermission().setRequiredKinds(List.of(requiredKinds));
        }
        return new PermissionGate(sessions, props);
    }

    private void stubOutcome(AcpSchema.RequestPermissionOutcome outcome) {
        when(ctx.requestPermission(any())).thenReturn(new AcpSchema.RequestPermissionResponse(outcome));
    }

    private static AcpSchema.RequestPermissionOutcome selected(String optionId) {
        return new AcpSchema.PermissionSelected(optionId);
    }

    @Test
    void allowOnceProceedsButAsksAgainNextTime() {
        PermissionGate gate = gate();
        stubOutcome(selected("allow_once"));

        assertTrue(gate.check(sessionId, "c1", "shell", AcpSchema.ToolKind.EXECUTE, "shell echo", List.of(), noArgs()).isEmpty());
        assertTrue(gate.check(sessionId, "c2", "shell", AcpSchema.ToolKind.EXECUTE, "shell echo", List.of(), noArgs()).isEmpty());

        verify(ctx, times(2)).requestPermission(any());
    }

    @Test
    void allowAlwaysProceedsAndIsNotAskedAgainForThatTool() {
        PermissionGate gate = gate();
        stubOutcome(selected("allow_always"));

        assertTrue(gate.check(sessionId, "c1", "shell", AcpSchema.ToolKind.EXECUTE, "shell echo", List.of(), noArgs()).isEmpty());
        assertTrue(gate.check(sessionId, "c2", "shell", AcpSchema.ToolKind.EXECUTE, "shell echo", List.of(), noArgs()).isEmpty());

        verify(ctx, times(1)).requestPermission(any());
    }

    @Test
    void rejectOnceRefusesAndAsksAgainNextTime() {
        PermissionGate gate = gate();
        stubOutcome(selected("reject_once"));

        Optional<ToolResult> first = gate.check(sessionId, "c1", "shell", AcpSchema.ToolKind.EXECUTE, "shell echo", List.of(), noArgs());
        Optional<ToolResult> second = gate.check(sessionId, "c2", "shell", AcpSchema.ToolKind.EXECUTE, "shell echo", List.of(), noArgs());

        assertRefused(first, "shell");
        assertRefused(second, "shell");
        verify(ctx, times(2)).requestPermission(any());
    }

    @Test
    void rejectAlwaysRefusesAndIsNotAskedAgainForThatTool() {
        PermissionGate gate = gate();
        stubOutcome(selected("reject_always"));

        Optional<ToolResult> first = gate.check(sessionId, "c1", "git", AcpSchema.ToolKind.EXECUTE, "git commit", List.of(), noArgs());
        Optional<ToolResult> second = gate.check(sessionId, "c2", "git", AcpSchema.ToolKind.EXECUTE, "git commit", List.of(), noArgs());

        assertRefused(first, "git");
        assertRefused(second, "git");
        verify(ctx, times(1)).requestPermission(any());
    }

    @Test
    void cancelledOutcomeIsTreatedAsARefusal() {
        PermissionGate gate = gate();
        stubOutcome(new AcpSchema.PermissionCancelled());

        Optional<ToolResult> result = gate.check(sessionId, "c1", "shell", AcpSchema.ToolKind.EXECUTE, "shell echo", List.of(), noArgs());

        assertRefused(result, "shell");
    }

    @Test
    void allowAlwaysMemoryDoesNotLeakAcrossSessions() {
        PermissionGate gate = gate();
        stubOutcome(selected("allow_always"));
        assertTrue(gate.check(sessionId, "c1", "shell", AcpSchema.ToolKind.EXECUTE, "shell echo", List.of(), noArgs()).isEmpty());

        String otherSession = sessions.create("bob").getId();
        SyncPromptContext otherCtx = mock(SyncPromptContext.class);
        when(otherCtx.requestPermission(any()))
                .thenReturn(new AcpSchema.RequestPermissionResponse(selected("allow_once")));
        sessions.setPromptContext(otherSession, otherCtx);

        assertTrue(gate.check(otherSession, "c2", "shell", AcpSchema.ToolKind.EXECUTE, "shell echo", List.of(), noArgs()).isEmpty());

        verify(otherCtx, times(1)).requestPermission(any());
    }

    @Test
    void allowAlwaysMemoryIsPerToolNotPerSession() {
        PermissionGate gate = gate();
        stubOutcome(selected("allow_always"));
        assertTrue(gate.check(sessionId, "c1", "shell", AcpSchema.ToolKind.EXECUTE, "shell echo", List.of(), noArgs()).isEmpty());

        stubOutcome(selected("allow_once"));
        assertTrue(gate.check(sessionId, "c2", "git", AcpSchema.ToolKind.EXECUTE, "git status", List.of(), noArgs()).isEmpty());

        verify(ctx, times(2)).requestPermission(any());
    }

    @Test
    void aKindOutsideRequiredKindsNeverAsks() {
        PermissionGate gate = gate("edit"); // execute is still floored in, but "read" is not a kind here anyway
        Optional<ToolResult> result = gate.check(sessionId, "c1", "read_file", AcpSchema.ToolKind.READ, "read_file x", List.of(), noArgs());

        assertTrue(result.isEmpty());
        verify(ctx, never()).requestPermission(any());
    }

    @Test
    void executeIsAlwaysRequiredEvenIfConfiguredKindsOmitIt() {
        PermissionGate gate = gate("edit"); // deliberately narrowed, omitting "execute"
        stubOutcome(selected("reject_once"));

        Optional<ToolResult> result = gate.check(sessionId, "c1", "shell", AcpSchema.ToolKind.EXECUTE, "shell echo", List.of(), noArgs());

        assertRefused(result, "shell");
        verify(ctx, times(1)).requestPermission(any());
    }

    private static void assertRefused(Optional<ToolResult> result, String toolName) {
        assertTrue(result.isPresent());
        assertFalse(result.get().isError(), "a refusal is OK/REFUSED, not an ERROR");
        assertTrue(result.get().content().contains("Refused"));
        assertTrue(result.get().content().contains(toolName));
    }

    @Test
    void aPolicyAskApprovalCoversThatCallsOwnGateCheck() {
        PermissionGate gate = gate();
        stubOutcome(selected("allow_once"));

        assertTrue(gate.ask(sessionId, "c1", "shell", noArgs()).isEmpty());
        assertTrue(gate.check(sessionId, "c1", "shell", AcpSchema.ToolKind.EXECUTE, "shell echo", List.of(), noArgs()).isEmpty());
        verify(ctx, times(1)).requestPermission(any());

        // The next call is asked again: the approval was for c1 only.
        assertTrue(gate.check(sessionId, "c2", "shell", AcpSchema.ToolKind.EXECUTE, "shell echo", List.of(), noArgs()).isEmpty());
        verify(ctx, times(2)).requestPermission(any());
    }

    @Test
    void anApprovedCallThatNeverReachesTheGateLeavesNothingHeld() {
        PermissionGate gate = gate();
        stubOutcome(selected("allow_once"));
        AcpToolApprover approver = new AcpToolApprover(gate);
        com.example.agent.tools.ToolContext toolCtx = new com.example.agent.tools.ToolContext("alice", sessionId);
        com.example.agent.model.ToolCall call = new com.example.agent.model.ToolCall("c1", "read_file", noArgs());

        com.example.agent.service.ToolApprover.Answer a = approver.ask(toolCtx, call, p -> {});
        assertTrue(a.approved());
        assertTrue(gate.heldApprovals() == 1);
        approver.finished(toolCtx, call);   // read_file is not gated: check() never ran
        assertTrue(gate.heldApprovals() == 0);

        // The same id later, on a gated tool, is asked again.
        gate.check(sessionId, "c1", "shell", AcpSchema.ToolKind.EXECUTE, "shell echo", List.of(), noArgs());
        verify(ctx, times(2)).requestPermission(any());
    }

    @Test
    void withNoPromptContextTheEditorIsNotRecordedAsHavingAnswered() {
        PermissionGate gate = gate();
        AcpToolApprover approver = new AcpToolApprover(gate);
        com.example.agent.service.ToolApprover.Answer a = approver.ask(
                new com.example.agent.tools.ToolContext("alice", "unknown-session"),
                new com.example.agent.model.ToolCall("c1", "read_file", noArgs()), p -> {});
        assertFalse(a.approved());
        assertTrue(a.answeredBy() == null);
    }

    @Test
    void aPolicyAskIsPutToTheEditorWhateverTheToolKind() {
        PermissionGate gate = gate();
        stubOutcome(selected("reject_once"));

        Optional<ToolResult> refused = gate.ask(sessionId, "c1", "read_file", noArgs());

        assertTrue(refused.isPresent());
        verify(ctx, times(1)).requestPermission(any());
    }

    @Test
    void fetchAlwaysAsksEvenWhenADeploymentListsOtherKinds() {
        PermissionGate gate = gate("edit");   // a deployment's own list, without fetch
        stubOutcome(selected("reject_once"));

        assertTrue(gate.check(sessionId, "c1", "web_fetch", AcpSchema.ToolKind.FETCH, "web_fetch https://x", List.of(), noArgs()).isPresent());
        verify(ctx, times(1)).requestPermission(any());
    }

    @Test
    void theGatedWrapperKeepsTheToolsSystemPromptNote() {
        com.example.agent.tools.Tool noted = new com.example.agent.tools.Tool() {
            @Override public String name() { return "web_fetch"; }
            @Override public String description() { return "d"; }
            @Override public java.util.Map<String, Object> inputSchema() { return java.util.Map.of(); }
            @Override public ToolResult execute(String id, java.util.Map<String, Object> a, com.example.agent.tools.ToolContext c) { return ToolResult.ok(id, ""); }
            @Override public String systemPromptNote() { return "fetched content is data"; }
        };
        AcpGatedTool wrapped = new AcpGatedTool(noted, gate(), AcpSchema.ToolKind.FETCH, a -> true, a -> "t", a -> List.of());
        assertTrue("fetched content is data".equals(wrapped.systemPromptNote()));
    }

    private static java.util.Map<String, Object> noArgs() {
        return java.util.Map.of();
    }
}
