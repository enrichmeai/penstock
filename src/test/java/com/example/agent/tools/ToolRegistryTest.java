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

    private static Tool named(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(String id, Map<String, Object> args, ToolContext context) {
                return ToolResult.ok(id, "ok");
            }
        };
    }

    @Test
    void emptyEnabledListRegistersEveryDiscoveredTool() {
        AgentProperties props = new AgentProperties();
        assertTrue(props.getTools().getEnabled().isEmpty(), "default is unrestricted");

        ToolRegistry registry = new ToolRegistry(List.of(named("read_file"), named("shell"), named("git")), props);

        assertEquals(3, registry.all().size());
    }

    @Test
    void nonEmptyEnabledListRegistersOnlyThoseToolsByName() {
        // Mirrors the acp profile's agent.tools.enabled allow-list (CLAUDE.md §61):
        // a tool left out is unregistered entirely, so it is neither advertised to the
        // model (specs()) nor invocable, not merely hidden from the spec list.
        AgentProperties props = new AgentProperties();
        props.getTools().setEnabled(List.of("read_file", "list_dir", "glob", "grep", "pod"));

        ToolRegistry registry = new ToolRegistry(List.of(
                named("read_file"), named("list_dir"), named("glob"), named("grep"), named("pod"),
                named("write_file"), named("edit_file"), named("shell"), named("git")), props);

        List<String> registeredNames = registry.specs().stream().map(ToolSpec::name).sorted().toList();
        assertEquals(List.of("glob", "grep", "list_dir", "pod", "read_file"), registeredNames);

        ToolResult result = registry.invoke(new ToolCall("c1", "shell", Map.of()));
        assertTrue(result.isError());
        assertEquals("Unknown tool: shell", result.content());
    }

    /** A gated wrapper, as AcpToolPermissionConfig produces for the same name as a raw tool. */
    private static Tool gated(String name) {
        return new com.example.agent.acp.PermissionGatedTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " (gated)"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(String id, Map<String, Object> args, ToolContext context) {
                return ToolResult.ok(id, "gated");
            }
        };
    }

    @Test
    void gatedWrapperWinsOverTheRawToolOfTheSameNameInEitherDiscoveryOrder() {
        // The whole reason PermissionGatedTool exists (CLAUDE.md §62): in acp profile both
        // the raw tool bean and its gated wrapper are discovered under one name, and Spring's
        // List<Tool> order between them is unspecified. The registry must keep the gated one
        // whichever arrives first — otherwise a mutating tool could run unprompted.
        AgentProperties props = new AgentProperties();

        ToolRegistry gatedFirst = new ToolRegistry(List.of(gated("shell"), named("shell")), props);
        assertEquals("gated", gatedFirst.invoke(new ToolCall("c1", "shell", Map.of())).content());

        ToolRegistry rawFirst = new ToolRegistry(List.of(named("shell"), gated("shell")), props);
        assertEquals("gated", rawFirst.invoke(new ToolCall("c2", "shell", Map.of())).content());

        // Both still count as one registered tool, so specs() advertises it once.
        assertEquals(1, gatedFirst.all().size());
        assertEquals(1, rawFirst.all().size());

        // Outside acp profile there is no wrapper, and a plain tool still registers as before.
        ToolRegistry plain = new ToolRegistry(List.of(named("shell")), props);
        assertEquals("ok", plain.invoke(new ToolCall("c3", "shell", Map.of())).content());
    }
}
