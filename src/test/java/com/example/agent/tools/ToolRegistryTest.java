package com.example.agent.tools;

import com.example.agent.config.AgentMetrics;
import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolOutcome;
import com.example.agent.service.AuditLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    @Test
    void invokePassesResolvedIdentityToTheTool() {
        // Part B seam: the acting user reaches the tool as an explicit
        // ToolContext, resolved by the caller on the request thread — never
        // read from thread-local security state.
        java.util.concurrent.atomic.AtomicReference<ToolContext> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        Tool tool = new Tool() {
            @Override public String name() { return "ctx_capture"; }
            @Override public String description() { return "captures context"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(String id, Map<String, Object> args, ToolContext context) {
                seen.set(context);
                return ToolResult.ok(id, "ok");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(tool), new AgentProperties());

        registry.invoke(new ToolCall("c1", "ctx_capture", Map.of()), "sess-1", "alice");
        assertEquals("alice", seen.get().userId());
        assertEquals("sess-1", seen.get().sessionId());

        // Callers without a user resolve to the anonymous context, never null.
        registry.invoke(new ToolCall("c2", "ctx_capture", Map.of()));
        assertEquals(ToolContext.ANONYMOUS, seen.get().userId());
        assertNull(seen.get().sessionId());
    }

    /** One audited tool call, as the registry reported it. */
    private record Audited(String user, String tool, ToolOutcome outcome, int contentBytes) {}

    private static Tool returning(String name, ToolResult result) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(String id, Map<String, Object> args, ToolContext context) {
                if (result == null) throw new IllegalStateException("tool fell over");
                return new ToolResult(id, result.content(), result.outcome());
            }
        };
    }

    private static double count(SimpleMeterRegistry registry, String tool, String outcome) {
        Counter c = registry.find("tool_calls_total").tag("tool", tool).tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void aRefusalIsAuditedAndCountedAsRefusedNotAsASuccess() {
        // #8: the audit row for a refused read must not be indistinguishable from a granted one.
        String refusal = "Refused: the owner has not granted access to /support/. Report it.";
        List<Audited> audited = new ArrayList<>();
        AuditLogger recording = new AuditLogger(null, new ObjectMapper()) {
            @Override
            public void toolCall(String userId, String sessionId, String toolName, Map<String, Object> args,
                                 ToolOutcome outcome, int contentBytes) {
                audited.add(new Audited(userId, toolName, outcome, contentBytes));
            }
        };
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ToolRegistry registry = new ToolRegistry(List.of(returning("pod", ToolResult.refused("_", refusal))), new AgentProperties());
        registry.setAuditLogger(recording);
        registry.setMetrics(new AgentMetrics(meters));

        ToolResult r = registry.invoke(new ToolCall("c1", "pod", Map.of("type", "read", "path", "/support/")), "sess-1", "bob");

        assertEquals(ToolOutcome.REFUSED, r.outcome());
        assertEquals(List.of(new Audited("bob", "pod", ToolOutcome.REFUSED, refusal.getBytes(StandardCharsets.UTF_8).length)), audited);
        assertEquals(1.0, count(meters, "pod", "refused"));
        assertEquals(0.0, count(meters, "pod", "ok"), "a refusal is not a success");
        assertEquals(0.0, count(meters, "pod", "error"), "a refusal is not an error");
    }

    @Test
    void aSuccessAndAnErrorKeepTheirOwnOutcomes() {
        List<Audited> audited = new ArrayList<>();
        AuditLogger recording = new AuditLogger(null, new ObjectMapper()) {
            @Override
            public void toolCall(String userId, String sessionId, String toolName, Map<String, Object> args,
                                 ToolOutcome outcome, int contentBytes) {
                audited.add(new Audited(userId, toolName, outcome, contentBytes));
            }
        };
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ToolRegistry registry = new ToolRegistry(List.of(
                returning("read", ToolResult.ok("_", "doc")),
                returning("broken", ToolResult.error("_", "boom")),
                returning("thrower", null)), new AgentProperties());
        registry.setAuditLogger(recording);
        registry.setMetrics(new AgentMetrics(meters));

        registry.invoke(new ToolCall("c1", "read", Map.of()), "sess-1", "bob");
        registry.invoke(new ToolCall("c2", "broken", Map.of()), "sess-1", "bob");
        ToolResult threw = registry.invoke(new ToolCall("c3", "thrower", Map.of()), "sess-1", "bob");

        assertEquals(ToolOutcome.ERROR, threw.outcome());
        assertEquals(List.of(
                new Audited("bob", "read", ToolOutcome.OK, 3),
                new Audited("bob", "broken", ToolOutcome.ERROR, 4),
                new Audited("bob", "thrower", ToolOutcome.ERROR, 0)), audited);
        assertEquals(1.0, count(meters, "read", "ok"));
        assertEquals(1.0, count(meters, "broken", "error"));
        assertEquals(1.0, count(meters, "thrower", "error"));
    }

    @Test
    void toolReturningExactMaxOutputBytesIsNotTruncated() {
        AgentProperties props = new AgentProperties();
        int maxBytes = props.getTools().getMaxOutputBytes();

        Tool tool = new Tool() {
            @Override public String name() { return "test"; }
            @Override public String description() { return "test"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(String id, Map<String, Object> args, ToolContext context) {
                // Return exactly maxBytes of content
                String content = "x".repeat(maxBytes);
                return ToolResult.ok(id, content);
            }
        };

        ToolRegistry registry = new ToolRegistry(List.of(tool), props);
        ToolCall call = new ToolCall("call1", "test", Map.of());
        ToolResult result = registry.invoke(call);

        // Should not be truncated
        assertEquals("x".repeat(maxBytes), result.content());
    }

    @Test
    void toolReturning3xMaxOutputBytesIsTruncated() {
        AgentProperties props = new AgentProperties();
        int maxBytes = props.getTools().getMaxOutputBytes();

        Tool tool = new Tool() {
            @Override public String name() { return "test"; }
            @Override public String description() { return "test"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(String id, Map<String, Object> args, ToolContext context) {
                // Return 3x the max bytes
                String content = "x".repeat(maxBytes * 3);
                return ToolResult.ok(id, content);
            }
        };

        ToolRegistry registry = new ToolRegistry(List.of(tool), props);
        ToolCall call = new ToolCall("call1", "test", Map.of());
        ToolResult result = registry.invoke(call);

        // Should be truncated
        assertNotEquals("x".repeat(maxBytes * 3), result.content());
        assertTrue(result.content().contains("[output truncated"));

        // Check that the total length is reasonable (within maxBytes + marker overhead)
        int actualBytes = result.content().getBytes(StandardCharsets.UTF_8).length;
        int markerOverheadAllowance = 150;
        assertTrue(actualBytes <= maxBytes + markerOverheadAllowance,
                "Truncated output (" + actualBytes + " bytes) should be <= " + (maxBytes + markerOverheadAllowance));

        // isError should be preserved
        assertFalse(result.isError());
    }

    @Test
    void truncationPreservesErrorFlag() {
        AgentProperties props = new AgentProperties();
        int maxBytes = props.getTools().getMaxOutputBytes();

        Tool tool = new Tool() {
            @Override public String name() { return "test"; }
            @Override public String description() { return "test"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(String id, Map<String, Object> args, ToolContext context) {
                String errorMsg = "x".repeat(maxBytes * 2);
                return ToolResult.error(id, errorMsg);
            }
        };

        ToolRegistry registry = new ToolRegistry(List.of(tool), props);
        ToolCall call = new ToolCall("call1", "test", Map.of());
        ToolResult result = registry.invoke(call);

        // Error flag should be preserved
        assertTrue(result.isError());
        assertTrue(result.content().contains("[output truncated"));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({
            org.junit.jupiter.api.condition.OS.MAC,
            org.junit.jupiter.api.condition.OS.LINUX})
    void aFailingBuildStaysDiagnosableAfterTruncation(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) {
        // A real `./gradlew test` on a failing suite prints far more than 16 KB, and
        // the part that matters — the failure summary — is at the very end. Head-only
        // truncation would leave the agent staring at a dependency banner and no
        // reason for the failure, so the tail has to survive.
        AgentProperties props = new AgentProperties();
        AgentProperties.Shell shellCfg = new AgentProperties.Shell();
        shellCfg.setEnabled(true);
        shellCfg.setTimeoutSeconds(60);
        props.getTools().setShell(shellCfg);

        ShellTool shell = new ShellTool(workspace, props);
        ToolRegistry registry = new ToolRegistry(List.of(shell), props);

        String command = "for i in $(seq 1 4000); do echo 'Download https://repo1.maven.org/artifact-'$i'.jar'; done; "
                + "echo 'AgentControllerIT > healthHead() FAILED'; "
                + "echo '    java.lang.AssertionError: Status expected:<200> but was:<405>'; "
                + "echo 'BUILD FAILED in 46s'; exit 1";
        ToolResult result = registry.invoke(new ToolCall("c-build", "shell", Map.of("command", command)));

        assertTrue(result.isError(), "a failing build must surface as a tool error");
        assertTrue(result.content().contains("[output truncated"), "expected truncation marker");
        assertTrue(result.content().getBytes(StandardCharsets.UTF_8).length
                        <= props.getTools().getMaxOutputBytes() + 200,
                "truncated output should respect max-output-bytes");
        // The three lines that actually explain the failure:
        assertTrue(result.content().contains("healthHead() FAILED"), result.content());
        assertTrue(result.content().contains("Status expected:<200> but was:<405>"), result.content());
        assertTrue(result.content().contains("BUILD FAILED"), result.content());
    }

    @Test
    void invokeWithAContextHandsItToTheToolUnchanged() {
        // The request ID and the caller's bearer are resolved at the HTTP boundary and
        // must reach the tool as given — a tool acting as the signed-in user depends on it.
        java.util.concurrent.atomic.AtomicReference<ToolContext> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        Tool tool = new Tool() {
            @Override public String name() { return "ctx_capture"; }
            @Override public String description() { return "captures context"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(String id, Map<String, Object> args, ToolContext context) {
                seen.set(context);
                return ToolResult.ok(id, "ok");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(tool), new AgentProperties());
        ToolContext given = new ToolContext("alice", "sess-2", "req-9",
                com.example.agent.model.BearerToken.of("eyJ.alice"));

        registry.invoke(new ToolCall("c3", "ctx_capture", Map.of()), given);

        assertSame(given, seen.get());
        assertEquals("req-9", seen.get().requestId());
        assertEquals("eyJ.alice", seen.get().bearer().orElseThrow().secret());
    }
}
