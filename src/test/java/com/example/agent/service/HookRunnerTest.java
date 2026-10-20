package com.example.agent.service;

import com.example.agent.config.AgentProperties;
import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.*;
import com.example.agent.tools.Tool;
import com.example.agent.tools.ToolContext;
import com.example.agent.tools.ToolRegistry;
import com.example.agent.tools.ToolSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Owner hooks (#184): stricter-only pre-tool, fail closed; post-tool notes; turn hooks; audit. */
class HookRunnerTest {

    @TempDir Path dir;
    private final AtomicInteger runs = new AtomicInteger();
    private final List<String> systemPrompts = new ArrayList<>();
    private final List<List<ChatMessage>> histories = new ArrayList<>();

    private Tool tool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public ToolResult execute(String id, Map<String, Object> args, ToolContext c) {
                runs.incrementAndGet();
                return ToolResult.ok(id, "ran " + name);
            }
        };
    }

    /** Calls {@code toolName} once, then finishes; records what it was shown. */
    private LlmProvider oneCall(String toolName) {
        return new LlmProvider() {
            int call = 0;
            @Override public String name() { return "scripted"; }
            @Override public CompletionResult complete(String sys, List<ChatMessage> hist, List<ToolSpec> tools, String sessionId) {
                call++;
                histories.add(List.copyOf(hist));
                return call == 1
                        ? new CompletionResult(ChatMessage.assistant("", List.of(new ToolCall("c1", toolName, Map.of("path", "x.txt")))), new TokenUsage(1, 1))
                        : new CompletionResult(ChatMessage.assistantText("done"), new TokenUsage(1, 1));
            }
        };
    }

    private Path script(String name, String body) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        return p;
    }

    private AgentProperties.Hook hook(Path script, String... tools) {
        AgentProperties.Hook h = new AgentProperties.Hook();
        h.setCommand(List.of(script.toString()));
        h.setTools(List.of(tools));
        h.setTimeoutSeconds(5);
        return h;
    }

    private Session chat(AgentProperties props, String toolName, AuditLogger audit, ToolApprover approver) {
        AgentService svc = new AgentService(oneCall(toolName), new ToolRegistry(List.of(tool("write_file"), tool("read_file")), props), props, new InMemorySessionStore());
        HookRunner hooks = new HookRunner(props, dir);
        if (audit != null) {
            hooks.setAuditLogger(audit);
            svc.setAuditLogger(audit);
        }
        svc.setHookRunner(hooks);
        if (approver != null) svc.setToolApprover(approver);
        Session s = svc.createSession();
        svc.chat(s, "do it");
        return s;
    }

    private static ToolResult result(Session s) {
        return s.getHistory().stream().flatMap(m -> m.toolResults().stream()).findFirst().orElseThrow();
    }

    @Test
    void withNoHooksNothingChanges() {
        AgentProperties props = new AgentProperties();
        AuditLogger audit = mock(AuditLogger.class);
        Session s = chat(props, "write_file", audit, null);
        assertEquals(1, runs.get());
        assertEquals("ran write_file", result(s).content());
        verify(audit, never()).hookRan(any(), any(), any(), anyInt(), any(), any(), anyLong());
    }

    @Test
    void aPreToolDenyStopsTheCallAndTheModelSeesTheReason() throws IOException {
        AgentProperties props = new AgentProperties();
        props.getHooks().setPreTool(List.of(hook(script("deny.sh",
                "cat > /dev/null; echo '{\"decision\":\"deny\",\"reason\":\"writes only under src/\"}'"))));
        AuditLogger audit = mock(AuditLogger.class);
        Session s = chat(props, "write_file", audit, null);

        assertEquals(0, runs.get());
        assertTrue(result(s).isError());
        assertThat(result(s).content()).contains("pre-tool hook").contains("writes only under src/");
        assertThat(histories.get(1).toString()).contains("writes only under src/");
        verify(audit).hookRan(any(), eq(s.getId()), eq("pre-tool"), eq(0), eq("write_file"), eq("deny"), anyLong());
    }

    @Test
    void theHookReadsTheCallOnStdin() throws IOException {
        Path seen = dir.resolve("seen.json");
        AgentProperties props = new AgentProperties();
        props.getHooks().setPreTool(List.of(hook(script("record.sh", "cat > " + seen))));
        chat(props, "write_file", null, null);
        assertThat(Files.readString(seen)).contains("\"event\":\"pre-tool\"").contains("\"tool\":\"write_file\"")
                .contains("\"path\":\"x.txt\"").contains("\"callId\":\"c1\"");
        assertEquals(1, runs.get(), "empty output is no opinion");
    }

    @Test
    void aHookCannotAllowWhatThePolicyDenies() throws IOException {
        Path marker = dir.resolve("ran");
        AgentProperties props = new AgentProperties();
        props.getTools().setPolicy(Map.of("write_file", "deny"));
        props.getHooks().setPreTool(List.of(hook(script("allow.sh", "touch " + marker + "; echo '{\"decision\":\"allow\"}'"))));
        Session s = chat(props, "write_file", null, null);
        assertEquals(0, runs.get());
        assertThat(result(s).content()).contains("tool policy");
        assertFalse(Files.exists(marker), "the hook is never asked about a call the policy denied");
    }

    @Test
    void aHookCannotAllowAnAskTheUserDenied() throws IOException {
        Path marker = dir.resolve("ran");
        AgentProperties props = new AgentProperties();
        props.getTools().setPolicy(Map.of("write_file", "ask"));
        props.getHooks().setPreTool(List.of(hook(script("allow.sh", "touch " + marker + "; echo '{\"decision\":\"allow\"}'"))));
        ToolApprover no = (ctx, call, announce) -> new ToolApprover.Answer(false, "alice", false);
        Session s = chat(props, "write_file", null, no);
        assertEquals(0, runs.get());
        assertThat(result(s).content()).contains("did not allow");
        assertFalse(Files.exists(marker));
    }

    @Test
    void aFailingPreToolHookDenies() throws IOException {
        for (String body : List.of("exit 3", "echo not-json", "echo '[1,2]'", "sleep 30")) {
            runs.set(0);
            AgentProperties props = new AgentProperties();
            AgentProperties.Hook h = hook(script("bad.sh", body));
            h.setTimeoutSeconds(1);
            props.getHooks().setPreTool(List.of(h));
            AuditLogger audit = mock(AuditLogger.class);
            long start = System.nanoTime();
            Session s = chat(props, "write_file", audit, null);
            assertEquals(0, runs.get(), body);
            assertTrue(result(s).isError(), body);
            assertThat(result(s).content()).contains("so the call was denied");
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000);
            verify(audit).hookRan(any(), any(), eq("pre-tool"), eq(0), eq("write_file"),
                    eq(body.startsWith("sleep") ? "timeout" : "error"), anyLong());
        }
    }

    @Test
    void hooksMatchByTool() throws IOException {
        AgentProperties props = new AgentProperties();
        props.getHooks().setPreTool(List.of(hook(script("deny.sh", "echo '{\"decision\":\"deny\"}'"), "write_file")));
        chat(props, "read_file", null, null);
        assertEquals(1, runs.get(), "read_file is not matched, so it runs");
    }

    @Test
    void aPostToolNoteReachesTheModelAndAFailingOneIsIgnored() throws IOException {
        AgentProperties props = new AgentProperties();
        props.getHooks().setPostTool(List.of(
                hook(script("note.sh", "cat > /dev/null; echo '{\"note\":\"lint: 2 warnings\"}'")),
                hook(script("broken.sh", "exit 1"))));
        AuditLogger audit = mock(AuditLogger.class);
        Session s = chat(props, "write_file", audit, null);
        assertEquals(1, runs.get());
        assertThat(result(s).content()).startsWith("ran write_file").contains("lint: 2 warnings");
        assertFalse(result(s).isError());
        verify(audit).hookRan(any(), any(), eq("post-tool"), eq(0), eq("write_file"), eq("note"), anyLong());
        verify(audit).hookRan(any(), any(), eq("post-tool"), eq(1), eq("write_file"), eq("error"), anyLong());
    }

    @Test
    void turnHooksRunAtStartAndEnd() throws IOException {
        Path log = dir.resolve("turns.log");
        AgentProperties props = new AgentProperties();
        props.getHooks().setTurnStart(List.of(hook(script("start.sh", "echo \"start $PENSTOCK_HOOK_EVENT\" >> " + log))));
        props.getHooks().setTurnEnd(List.of(hook(script("end.sh", "echo \"end $PENSTOCK_HOOK_EVENT\" >> " + log))));
        AuditLogger audit = mock(AuditLogger.class);
        chat(props, "write_file", audit, null);
        assertEquals(List.of("start turn-start", "end turn-end"), Files.readAllLines(log));
        verify(audit).hookRan(any(), any(), eq("turn-start"), eq(0), isNull(), eq("pass"), anyLong());
        verify(audit).hookRan(any(), any(), eq("turn-end"), eq(0), isNull(), eq("pass"), anyLong());
    }

    @Test
    void aHookGetsAMinimalEnvironmentNotTheAgents() throws IOException {
        assertNotNull(System.getenv("PATH"));
        AgentProperties props = new AgentProperties();
        props.getHooks().setPostTool(List.of(hook(script("env.sh",
                "cat > /dev/null; printf '{\"note\":\"%s\"}' \"$(env | cut -d= -f1 | sort | tr '\\n' ' ')\""))));
        Session s = chat(props, "write_file", null, null);
        String note = result(s).content();
        String names = note.substring(note.indexOf("]\n") + 2).trim();
        // What Penstock passes, plus what sh sets for itself (PWD, SHLVL, _); nothing inherited.
        java.util.Set<String> got = new java.util.TreeSet<>(List.of(names.split(" ")));
        got.removeAll(List.of("PWD", "SHLVL", "_", "OLDPWD"));
        assertEquals(new java.util.TreeSet<>(List.of("HOME", "LANG", "PATH", "PENSTOCK_HOOK_EVENT")), got);
        assertTrue(System.getenv().size() > 4, "the test JVM has more in its environment than the hook got");
    }

    @Test
    void hooksBindFromConfiguration() {
        Map<String, String> source = Map.of(
                "agent.hooks.pre-tool[0].command[0]", "/opt/hooks/guard",
                "agent.hooks.pre-tool[0].command[1]", "--strict",
                "agent.hooks.pre-tool[0].tools[0]", "write_file",
                "agent.hooks.pre-tool[0].timeout-seconds", "3");
        AgentProperties props = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(source))
                .bind("agent", org.springframework.boot.context.properties.bind.Bindable.of(AgentProperties.class)).get();
        AgentProperties.Hook h = props.getHooks().getPreTool().get(0);
        assertEquals(List.of("/opt/hooks/guard", "--strict"), h.getCommand());
        assertEquals(List.of("write_file"), h.getTools());
        assertEquals(3, h.getTimeoutSeconds());
        assertTrue(props.getHooks().getPostTool().isEmpty());
    }

    @Test
    void turnEndRunsEvenWhenTheTurnThrows() throws IOException {
        Path log = dir.resolve("turns.log");
        AgentProperties props = new AgentProperties();
        props.getHooks().setTurnEnd(List.of(hook(script("end.sh", "echo end >> " + log))));
        AgentService svc = new AgentService(oneCall("write_file"), new ToolRegistry(List.of(tool("write_file")), props), props, new InMemorySessionStore());
        svc.setHookRunner(new HookRunner(props, dir));
        Session s = svc.createSession();
        assertThrows(IllegalStateException.class, () -> svc.chatStreaming(s, "do it", m -> { throw new IllegalStateException("client gone"); }));
        assertEquals(List.of("end"), Files.readAllLines(log));
    }

    @Test
    void aSlowPostToolHookTimesOutAndTheResultStands() throws IOException {
        AgentProperties props = new AgentProperties();
        AgentProperties.Hook h = hook(script("slow.sh", "sleep 30"));
        h.setTimeoutSeconds(1);
        props.getHooks().setPostTool(List.of(h));
        AuditLogger audit = mock(AuditLogger.class);
        Session s = chat(props, "write_file", audit, null);
        assertEquals("ran write_file", result(s).content());
        verify(audit).hookRan(any(), any(), eq("post-tool"), eq(0), eq("write_file"), eq("timeout"), anyLong());
    }

    @Test
    void outputOver64KbDeniesAsTooLarge() throws IOException {
        AgentProperties props = new AgentProperties();
        props.getHooks().setPreTool(List.of(hook(script("big.sh", "head -c 200000 /dev/zero | tr '\\0' 'x'"))));
        AuditLogger audit = mock(AuditLogger.class);
        Session s = chat(props, "write_file", audit, null);
        assertEquals(0, runs.get());
        assertThat(result(s).content()).contains("output-too-large");
        verify(audit).hookRan(any(), any(), eq("pre-tool"), eq(0), eq("write_file"), eq("output-too-large"), anyLong());
    }

    @Test
    void aChildTheHookLeavesRunningIsKilledAndTheAnswerKept() throws IOException {
        Path pid = dir.resolve("child.pid");
        AgentProperties props = new AgentProperties();
        props.getHooks().setPostTool(List.of(hook(script("bg.sh",
                "cat > /dev/null; sleep 30 & echo $! > " + pid + "; sleep 0.3; echo '{\"note\":\"kept\"}'"))));
        long start = System.nanoTime();
        Session s = chat(props, "write_file", null, null);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(3_000);
        assertThat(result(s).content()).contains("kept");
        long child = Long.parseLong(Files.readString(pid).trim());
        assertTrue(deadOrZombie(child), "the background child was killed");
    }

    /**
     * Killed, allowing for a zombie: an orphan is reparented to PID 1, and a PID 1 that does not
     * reap (as in some containers) leaves it as a zombie, which ProcessHandle still calls alive.
     */
    private static boolean deadOrZombie(long pid) {
        long until = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < until) {
            if (ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true)) return true;
            Path status = Path.of("/proc/" + pid + "/status");
            try {
                if (Files.exists(status) && Files.readString(status).contains("State:\tZ")) return true;
            } catch (IOException ignored) {
                // gone between the checks
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Test
    void theFirstPreToolDenyWinsAndEachRunIsAudited() throws IOException {
        Path second = dir.resolve("second-ran");
        AgentProperties props = new AgentProperties();
        props.getHooks().setPreTool(List.of(
                hook(script("ok.sh", "echo '{\"decision\":\"allow\"}'")),
                hook(script("no.sh", "echo '{\"decision\":\"deny\",\"reason\":\"second says no\"}'")),
                hook(script("third.sh", "touch " + second))));
        AuditLogger audit = mock(AuditLogger.class);
        Session s = chat(props, "write_file", audit, null);
        assertEquals(0, runs.get());
        assertThat(result(s).content()).contains("second says no");
        assertFalse(Files.exists(second), "hooks after a deny do not run");
        verify(audit).hookRan(any(), any(), eq("pre-tool"), eq(0), eq("write_file"), eq("allow"), anyLong());
        verify(audit).hookRan(any(), any(), eq("pre-tool"), eq(1), eq("write_file"), eq("deny"), anyLong());
        verify(audit, never()).hookRan(any(), any(), eq("pre-tool"), eq(2), any(), any(), anyLong());
    }

    @Test
    void aHookThatBackgroundsAChildAndExitsAtOnceFailsClosedWithinTheBound() throws IOException {
        Path pid = dir.resolve("fast.pid");
        AgentProperties props = new AgentProperties();
        props.getHooks().setPreTool(List.of(hook(script("fast.sh",
                "sleep 30 & echo $! > " + pid + "; echo '{}'"))));
        long start = System.nanoTime();
        Session s = chat(props, "write_file", null, null);
        long ms = (System.nanoTime() - start) / 1_000_000;
        // Either the child was seen and killed (the call runs), or it held the output open and the
        // call was denied; never a hang, never a silent allow with the output unread.
        assertThat(ms).isLessThan(4_000);
        if (runs.get() == 0) {
            assertThat(result(s).content()).contains("so the call was denied");
        }
        long child = Long.parseLong(Files.readString(pid).trim());
        ProcessHandle.of(child).ifPresent(ProcessHandle::destroyForcibly);   // tidy up the escapee, if any
    }
}
