package com.example.agent.service;

import com.example.agent.config.AgentProperties;
import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.*;
import com.example.agent.tools.Tool;
import com.example.agent.tools.ToolContext;
import com.example.agent.tools.ToolPolicy;
import com.example.agent.tools.ToolRegistry;
import com.example.agent.tools.ToolSpec;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** {@code agent.tools.policy} (#179): allow, ask and deny, and the defaults leaving behaviour unchanged. */
class ToolPolicyTest {

    private final AtomicInteger runs = new AtomicInteger();

    private final Tool touch = new Tool() {
        @Override public String name() { return "write_file"; }
        @Override public String description() { return "writes"; }
        @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        @Override public ToolResult execute(String id, Map<String, Object> args, ToolContext c) {
            runs.incrementAndGet();
            return ToolResult.ok(id, "written");
        }
    };

    /** Calls write_file once, then finishes. */
    private static LlmProvider oneCall() {
        return new LlmProvider() {
            int call = 0;
            @Override public String name() { return "scripted"; }
            @Override public CompletionResult complete(String sys, List<ChatMessage> hist, List<ToolSpec> tools, String sessionId) {
                call++;
                return call == 1
                        ? new CompletionResult(ChatMessage.assistant("", List.of(new ToolCall("c1", "write_file", Map.of("path", "a.txt")))), new TokenUsage(1, 1))
                        : new CompletionResult(ChatMessage.assistantText("done"), new TokenUsage(1, 1));
            }
        };
    }

    private AgentService service(AgentProperties props, ToolApprover approver, AuditLogger audit) {
        AgentService svc = new AgentService(oneCall(), new ToolRegistry(List.of(touch), props), props, new InMemorySessionStore());
        if (approver != null) svc.setToolApprover(approver);
        if (audit != null) svc.setAuditLogger(audit);
        return svc;
    }

    private static ToolResult toolResult(Session s) {
        return s.getHistory().stream().flatMap(m -> m.toolResults().stream()).findFirst().orElseThrow();
    }

    @Test
    void withNoPolicyEveryToolRunsAndNobodyIsAsked() {
        AgentProperties props = new AgentProperties();
        ToolApprover approver = mock(ToolApprover.class);
        AuditLogger audit = mock(AuditLogger.class);
        AgentService svc = service(props, approver, audit);
        Session s = svc.createSession();

        svc.chat(s, "write it");

        assertEquals(1, runs.get());
        assertFalse(toolResult(s).isError());
        verifyNoInteractions(approver);
        verify(audit, never()).toolPermission(any(), any(), any(), any(), any(), any());
    }

    @Test
    void denyRefusesWithAToolErrorAndIsAudited() {
        AgentProperties props = new AgentProperties();
        props.getTools().setPolicy(Map.of("write_file", "deny"));
        AuditLogger audit = mock(AuditLogger.class);
        AgentService svc = service(props, null, audit);
        Session s = svc.createSession();

        svc.chat(s, "write it");

        assertEquals(0, runs.get());
        ToolResult r = toolResult(s);
        assertTrue(r.isError());
        assertTrue(r.content().contains("denied by this deployment's tool policy"), r.content());
        verify(audit).toolPermission(any(), eq(s.getId()), eq("write_file"), eq("c1"), eq("denied"), eq("policy"));
    }

    @Test
    void askWithoutAnApproverIsRefusedNotRun() {
        AgentProperties props = new AgentProperties();
        props.getTools().setPolicy(Map.of("write_file", "ask"));
        AgentService svc = service(props, null, null);
        Session s = svc.createSession();

        svc.chat(s, "write it");

        assertEquals(0, runs.get());
        assertTrue(toolResult(s).isError());
    }

    @Test
    void askPausesAnnouncesAndResumesOnApprove() throws Exception {
        AgentProperties props = new AgentProperties();
        props.getTools().setPolicy(Map.of("write_file", "ask"));
        ToolApprovals approvals = new ToolApprovals(props);
        AuditLogger audit = mock(AuditLogger.class);
        AgentService svc = service(props, approvals, audit);
        Session s = svc.createSession();

        BlockingQueue<PendingApproval> announced = new LinkedBlockingQueue<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> turn = pool.submit(() -> svc.chatStreaming(s, "write it", TurnContext.none(), m -> {}, t -> {}, announced::add));
            PendingApproval p = announced.poll(5, TimeUnit.SECONDS);
            assertNotNull(p, "the pending call is announced before the wait");
            assertEquals("c1", p.callId());
            assertEquals("write_file", p.tool());
            assertEquals(Map.of("path", "a.txt"), p.arguments());
            assertEquals(0, runs.get(), "nothing runs before the answer");
            assertEquals(List.of(p), approvals.pending(s.getId()));

            assertTrue(approvals.answer(s.getId(), "c1", true, "alice"));
            turn.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, runs.get());
        assertFalse(toolResult(s).isError());
        assertTrue(approvals.pending(s.getId()).isEmpty());
        verify(audit).toolPermission(any(), eq(s.getId()), eq("write_file"), eq("c1"), eq("asked"), isNull());
        verify(audit).toolPermission(any(), eq(s.getId()), eq("write_file"), eq("c1"), eq("allowed"), eq("alice"));
    }

    @Test
    void askDeniedByTheUserDoesNotRun() throws Exception {
        AgentProperties props = new AgentProperties();
        props.getTools().setPolicy(Map.of("write_file", "ask"));
        ToolApprovals approvals = new ToolApprovals(props);
        AuditLogger audit = mock(AuditLogger.class);
        AgentService svc = service(props, approvals, audit);
        Session s = svc.createSession();

        BlockingQueue<PendingApproval> announced = new LinkedBlockingQueue<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> turn = pool.submit(() -> svc.chatStreaming(s, "write it", TurnContext.none(), m -> {}, t -> {}, announced::add));
            assertNotNull(announced.poll(5, TimeUnit.SECONDS));
            assertTrue(approvals.answer(s.getId(), "c1", false, "alice"));
            turn.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, runs.get());
        assertTrue(toolResult(s).content().contains("did not allow"));
        verify(audit).toolPermission(any(), eq(s.getId()), eq("write_file"), eq("c1"), eq("denied"), eq("alice"));
    }

    @Test
    void anUnansweredAskTimesOutToDeny() {
        AgentProperties props = new AgentProperties();
        props.getTools().setPolicy(Map.of("write_file", "ask"));
        props.getTools().setAskTimeoutSeconds(1);
        ToolApprovals approvals = new ToolApprovals(props);
        AuditLogger audit = mock(AuditLogger.class);
        AgentService svc = service(props, approvals, audit);
        Session s = svc.createSession();

        svc.chat(s, "write it");

        assertEquals(0, runs.get());
        ToolResult r = toolResult(s);
        assertTrue(r.isError());
        assertTrue(r.content().contains("in time"), r.content());
        assertFalse(approvals.answer(s.getId(), "c1", true, "late"), "a timed-out call can no longer be answered");
        verify(audit).toolPermission(any(), eq(s.getId()), eq("write_file"), eq("c1"), eq("timed-out"), isNull());
    }

    @Test
    void anAnswerForAnotherSessionDoesNotReachTheCall() {
        AgentProperties props = new AgentProperties();
        ToolApprovals approvals = new ToolApprovals(props);
        assertFalse(approvals.answer("someone-else", "c1", true, "mallory"));
    }

    @Test
    void policyKeysBindFromYamlAndEnvironmentSpellings() {
        Map<String, String> source = Map.of(
                "agent.tools.policy.write_file", "deny",
                "agent.tools.policy.shell", "ASK",
                "agent.tools.policy.edit.file", "allow");   // AGENT_TOOLS_POLICY_EDIT_FILE
        AgentProperties props = new Binder(new MapConfigurationPropertySource(source))
                .bind("agent", Bindable.of(AgentProperties.class)).get();

        assertEquals(ToolPolicy.DENY, props.getTools().policyFor("write_file"));
        assertEquals(ToolPolicy.ASK, props.getTools().policyFor("shell"));
        assertEquals(ToolPolicy.ALLOW, props.getTools().policyFor("edit_file"));
        assertEquals(ToolPolicy.ALLOW, props.getTools().policyFor("git"), "no entry means allow");
    }

    @Test
    void policyKeysBindFromRealEnvironmentVariables() {
        org.springframework.core.env.SystemEnvironmentPropertySource env =
                new org.springframework.core.env.SystemEnvironmentPropertySource(
                        org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
                        "AGENT_TOOLS_POLICY_WRITE_FILE", "ask",
                        "AGENT_TOOLS_POLICY_SHELL", "deny"));
        AgentProperties props = new Binder(org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(env))
                .bind("agent", Bindable.of(AgentProperties.class)).get();

        assertEquals(ToolPolicy.ASK, props.getTools().policyFor("write_file"));
        assertEquals(ToolPolicy.DENY, props.getTools().policyFor("shell"));
    }

    @Test
    void twoSpellingsOfOneToolWithDifferentPoliciesFailAtStartup() {
        AgentProperties props = new AgentProperties();
        Map<String, String> conflicting = new java.util.LinkedHashMap<>();
        conflicting.put("write_file", "deny");
        conflicting.put("write-file", "allow");
        assertThrows(IllegalArgumentException.class, () -> props.getTools().setPolicy(conflicting));
    }

    @Test
    void aSecondAskForTheSameCallIdWhileTheFirstWaitsIsDenied() throws Exception {
        AgentProperties props = new AgentProperties();
        props.getTools().setAskTimeoutSeconds(5);
        ToolApprovals approvals = new ToolApprovals(props);
        ToolContext ctx = new ToolContext("alice", "s1");
        ToolCall call = new ToolCall("c1", "shell", Map.of());
        CountDownLatch firstWaiting = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ToolApprover.Answer> first = pool.submit(() -> approvals.ask(ctx, call, p -> firstWaiting.countDown()));
            assertTrue(firstWaiting.await(5, TimeUnit.SECONDS));

            ToolApprover.Answer second = approvals.ask(ctx, call, p -> fail("the duplicate is never announced"));
            assertFalse(second.approved());

            assertTrue(approvals.answer("s1", "c1", true, "alice"));
            assertTrue(first.get(5, TimeUnit.SECONDS).approved(), "the first still gets its own answer");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aStreamThatCannotAnnounceDeniesAndLeavesNothingWaiting() {
        AgentProperties props = new AgentProperties();
        ToolApprovals approvals = new ToolApprovals(props);
        ToolApprover.Answer a = approvals.ask(new ToolContext("alice", "s1"), new ToolCall("c1", "shell", Map.of()),
                p -> { throw new RuntimeException("stream closed"); });
        assertFalse(a.approved());
        assertTrue(approvals.pending("s1").isEmpty());
    }

    @Test
    void anUnknownPolicyValueFailsAtStartup() {
        AgentProperties props = new AgentProperties();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> props.getTools().setPolicy(Map.of("shell", "maybe")));
        assertTrue(e.getMessage().contains("agent.tools.policy.shell"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> props.getTools().setAskTimeoutSeconds(0));
    }
}
