package com.example.agent.tools;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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
