package com.example.agent.lsp;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolResult;
import com.example.agent.tools.EditFileTool;
import com.example.agent.tools.ToolContext;
import com.example.agent.tools.ToolRegistry;
import com.example.agent.tools.WriteFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #190: after write_file or edit_file, the language server's errors come back with the result. */
class AfterEditDiagnosticsTest {

    @TempDir Path dir;
    Path workspace;
    AgentProperties props;
    LspManager lsp;
    ToolRegistry registry;

    @BeforeEach
    void start() throws IOException {
        Files.createDirectories(dir.resolve("ws/src"));
        workspace = dir.resolve("ws").toRealPath();
        props = new AgentProperties();
        props.getTools().getLsp().setEnabled(true);
        props.getTools().getLsp().setAfterEditWaitSeconds(10);
        AgentProperties.Lsp.Server java = new AgentProperties.Lsp.Server();
        java.setCommand(LspToolsTest.fakeServer());
        java.setExtensions(List.of(".java"));
        props.getTools().getLsp().getServers().put("java", java);
        lsp = new LspManager(props, workspace);
        registry = new ToolRegistry(List.of(new WriteFileTool(workspace, props), new EditFileTool(workspace, props)), props);
        registry.setAfterCall(List.of(new AfterEditDiagnostics(lsp)));
    }

    @AfterEach
    void stop() {
        lsp.stop();
    }

    private ToolResult call(String tool, Map<String, Object> args) {
        return registry.invoke(new ToolCall("c1", tool, args), new ToolContext("alice", "s1"));
    }

    @Test
    void aWriteThatDoesNotCompileComesBackWithItsErrors() {
        ToolResult r = call("write_file", Map.of("path", "src/A.java", "content", "class A {\n    BROKEN b;\n}\n"));

        assertThat(r.isError()).isFalse();
        assertThat(r.content()).endsWith("\n\nLanguage server, after this edit: 1 error\n"
                + "src/A.java:2:5 error: BROKEN cannot be resolved to a type");
    }

    @Test
    void anEditThatFixesItSaysSo() throws IOException {
        Files.writeString(workspace.resolve("src/A.java"), "class A {\n    BROKEN b;\n}\n");
        ToolResult r = call("edit_file", Map.of("path", "src/A.java", "old_string", "BROKEN", "new_string", "int"));

        assertThat(r.isError()).isFalse();
        assertThat(r.content()).endsWith("\n\nLanguage server, after this edit: no errors reported.");
    }

    @Test
    void aFileNoServerServesGetsNothingAppended() {
        ToolResult r = call("write_file", Map.of("path", "notes.txt", "content", "BROKEN\n"));

        assertThat(r.content()).doesNotContain("Language server");
    }

    @Test
    void aFailedEditGetsNothingAppended() {
        ToolResult r = call("edit_file", Map.of("path", "src/Missing.java", "old_string", "x", "new_string", "y"));

        assertThat(r.isError()).isTrue();
        assertThat(r.content()).doesNotContain("Language server");
    }

    @Test
    void aWaitOfZeroTurnsItOff() {
        props.getTools().getLsp().setAfterEditWaitSeconds(0);
        ToolResult r = call("write_file", Map.of("path", "src/A.java", "content", "class A { BROKEN b; }\n"));

        assertThat(r.content()).doesNotContain("Language server");
    }

    @Test
    void aBrokenServerNeverFailsTheEdit() throws IOException {
        AgentProperties.Lsp.Server bad = new AgentProperties.Lsp.Server();
        bad.setCommand(List.of("/nonexistent/language-server"));
        bad.setExtensions(List.of(".java"));
        props.getTools().getLsp().getServers().put("java", bad);
        LspManager broken = new LspManager(props, workspace);
        registry.setAfterCall(List.of(new AfterEditDiagnostics(broken)));

        ToolResult r = call("write_file", Map.of("path", "src/A.java", "content", "class A {}\n"));

        assertThat(r.isError()).isFalse();
        assertThat(Files.readString(workspace.resolve("src/A.java"))).isEqualTo("class A {}\n");
        assertThat(r.content()).doesNotContain("Language server");
        broken.stop();
    }

    @Test
    void anAdditionThatThrowsIsIgnoredAndTheEditStillSucceeds() throws IOException {
        registry.setAfterCall(List.of((name, args, result) -> { throw new IllegalStateException("boom"); }));
        ToolResult r = call("write_file", Map.of("path", "src/B.java", "content", "class B {}\n"));

        assertThat(r.isError()).isFalse();
        assertThat(Files.readString(workspace.resolve("src/B.java"))).isEqualTo("class B {}\n");
    }

    @Test
    void aServerStillStartingNeverHoldsTheEditPastTheWait() throws Exception {
        AgentProperties.Lsp.Server slow = new AgentProperties.Lsp.Server();
        slow.setCommand(LspToolsTest.fakeServer("--slow-init", "8000"));
        slow.setExtensions(List.of(".java"));
        props.getTools().getLsp().getServers().put("java", slow);
        props.getTools().getLsp().setAfterEditWaitSeconds(2);
        LspManager starting = new LspManager(props, workspace);
        registry.setAfterCall(List.of(new AfterEditDiagnostics(starting)));
        try {
            long t0 = System.nanoTime();
            ToolResult r = call("write_file", Map.of("path", "src/A.java", "content", "class A { BROKEN b; }\n"));
            long ms = (System.nanoTime() - t0) / 1_000_000;

            assertThat(r.isError()).isFalse();
            assertThat(r.content()).doesNotContain("Language server");
            assertThat(ms).as("the edit waited %d ms", ms).isLessThan(4_000);
        } finally {
            starting.stop();
        }
    }

    @Test
    void aServerThatFailedToStartIsNotRetriedOnEveryEdit() {
        AgentProperties.Lsp.Server mute = new AgentProperties.Lsp.Server();
        mute.setCommand(LspToolsTest.fakeServer("--mute"));
        mute.setExtensions(List.of(".java"));
        props.getTools().getLsp().getServers().put("java", mute);
        props.getTools().getLsp().setTimeoutSeconds(1);
        LspManager failing = new LspManager(props, workspace);
        try {
            assertThat(failing.client("java")).isNull();
        } catch (IOException first) {
            long t0 = System.nanoTime();
            try {
                failing.client("java");
            } catch (IOException second) {
                assertThat(second.getMessage()).contains("failed to start");
            }
            assertThat((System.nanoTime() - t0) / 1_000_000).as("the retry waited for another start").isLessThan(500);
        } finally {
            failing.stop();
        }
    }
}
