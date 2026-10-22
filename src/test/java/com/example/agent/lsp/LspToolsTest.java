package com.example.agent.lsp;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolResult;
import com.example.agent.tools.Tool;
import com.example.agent.tools.ToolContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Language-server tools (#190) against a scripted server that speaks LSP over stdio. */
class LspToolsTest {

    @TempDir Path dir;
    Path workspace;
    LspManager lsp;
    LspTools tools = new LspTools();

    static List<String> fakeServer(String... flags) {
        List<String> argv = new java.util.ArrayList<>(List.of(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), FakeLspServer.class.getName()));
        argv.addAll(List.of(flags));
        return argv;
    }

    private LspManager managerWith(List<String> command, List<String> env, int timeoutSeconds) {
        AgentProperties props = new AgentProperties();
        props.getTools().getLsp().setEnabled(true);
        props.getTools().getLsp().setTimeoutSeconds(timeoutSeconds);
        AgentProperties.Lsp.Server java = new AgentProperties.Lsp.Server();
        java.setCommand(command);
        java.setExtensions(List.of(".java"));
        java.setEnv(env);
        props.getTools().getLsp().getServers().put("java", java);
        return new LspManager(props, workspace);
    }

    @BeforeEach
    void start() throws IOException {
        Files.createDirectories(dir.resolve("ws/src"));
        workspace = dir.resolve("ws").toRealPath();
        AgentProperties props = new AgentProperties();
        props.getTools().getLsp().setEnabled(true);
        props.getTools().getLsp().setDiagnosticsWaitSeconds(10);
        AgentProperties.Lsp.Server java = new AgentProperties.Lsp.Server();
        java.setCommand(fakeServer());
        java.setExtensions(List.of(".java"));
        props.getTools().getLsp().getServers().put("java", java);
        lsp = new LspManager(props, workspace);
    }

    @AfterEach
    void stop() {
        lsp.stop();
    }

    private static ToolResult call(Tool t, Map<String, Object> args) {
        return t.execute("c1", args, new ToolContext("alice", "s1"));
    }

    @Test
    void diagnosticsReportsTheErrorTheServerPublishesWithItsLine() throws IOException {
        Files.writeString(workspace.resolve("src/A.java"), "class A {\n    BROKEN b;\n}\n");
        ToolResult r = call(tools.lspDiagnosticsTool(lsp), Map.of("path", "src/A.java"));

        assertThat(r.isError()).isFalse();
        assertThat(r.content()).isEqualTo("src/A.java:2:5 error: BROKEN cannot be resolved to a type");
    }

    @Test
    void diagnosticsSeesTheFileAsItIsNowAfterAnEdit() throws IOException {
        Path a = workspace.resolve("src/A.java");
        Files.writeString(a, "class A {\n    BROKEN b;\n}\n");
        Tool diagnostics = tools.lspDiagnosticsTool(lsp);
        assertThat(call(diagnostics, Map.of("path", "src/A.java")).content()).contains("error");

        Files.writeString(a, "class A {\n    int b;\n}\n");
        assertThat(call(diagnostics, Map.of("path", "src/A.java")).content()).isEqualTo("src/A.java: no errors or warnings.");
    }

    @Test
    void definitionGivesTheDeclarationAsAWorkspacePath() throws IOException {
        Files.writeString(workspace.resolve("src/A.java"), "class A { Target t; }\n");
        ToolResult r = call(tools.lspDefinitionTool(lsp), Map.of("path", "src/A.java", "line", 1, "column", 12));

        assertThat(r.content()).isEqualTo("Target.java:3:5");
    }

    @Test
    void referencesNameLocationsOutsideTheWorkspaceByFileNameOnly() throws IOException {
        Files.writeString(workspace.resolve("src/A.java"), "class A {}\n");
        ToolResult r = call(tools.lspReferencesTool(lsp), Map.of("path", "src/A.java", "line", "1", "column", "7"));

        assertThat(r.content().lines()).containsExactly(
                "src/A.java:1:1",
                "(outside the workspace) String.java",
                "(outside the workspace) Object.class");
        assertThat(r.content()).doesNotContain("/usr/lib/jvm").doesNotContain("jdt://");
    }

    @Test
    void symbolsFindsByName() {
        ToolResult r = call(tools.lspSymbolsTool(lsp), Map.of("query", "Billing"));

        assertThat(r.content()).isEqualTo("BillingService (class) src/Svc.java:5:14");
    }

    @Test
    void aPathOutsideTheWorkspaceIsRefused() {
        ToolResult r = call(tools.lspDefinitionTool(lsp), Map.of("path", "../outside/B.java", "line", 1, "column", 1));

        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("escapes workspace");
    }

    @Test
    void aFileNoServerServesIsRefusedByName() throws IOException {
        Files.writeString(workspace.resolve("notes.txt"), "hello\n");
        ToolResult r = call(tools.lspDiagnosticsTool(lsp), Map.of("path", "notes.txt"));

        assertThat(r.isError()).isTrue();
        assertThat(r.content()).isEqualTo("no language server serves notes.txt (configured: java)");
    }

    @Test
    void aServerThatDiedIsStartedAgainOnTheNextCall() throws IOException {
        Files.writeString(workspace.resolve("src/A.java"), "class A {}\n");
        Tool definition = tools.lspDefinitionTool(lsp);
        assertThat(call(definition, Map.of("path", "src/A.java", "line", 1, "column", 1)).isError()).isFalse();
        lsp.client("java").close();

        assertThat(call(definition, Map.of("path", "src/A.java", "line", 1, "column", 1)).content()).isEqualTo("Target.java:3:5");
    }

    @Test
    void theServerGetsAScrubbedEnvironmentNotPenstocksCredentials() throws Exception {
        Path envOut = dir.resolve("env.txt");
        LspManager m = managerWith(fakeServer("--env-out", envOut.toString()), List.of("JAVA_HOME"), 60);
        try {
            Files.writeString(workspace.resolve("src/A.java"), "class A {}\n");
            assertThat(call(tools.lspSymbolsTool(m), Map.of("query", "X")).isError()).isFalse();
            assertThat(Files.readString(envOut).lines()).isSubsetOf("PATH", "HOME", "LANG", "JAVA_HOME");
        } finally {
            m.stop();
        }
        assertThat(LspClient.environment(Map.of("PATH", "/bin", "ANTHROPIC_API_KEY", "sk-x", "JAVA_HOME", "/jdk",
                "GITHUB_COPILOT_TOKEN", "t"), List.of("JAVA_HOME"), workspace))
                .containsOnlyKeys("PATH", "HOME", "LANG", "JAVA_HOME");
    }

    @Test
    void aServerThatNeverAnswersInitializeIsKilledNotLeaked() throws Exception {
        long before = liveChildren();
        LspManager m = managerWith(fakeServer("--mute"), List.of(), 2);
        Files.writeString(workspace.resolve("src/A.java"), "class A {}\n");
        ToolResult r = call(tools.lspDefinitionTool(m), Map.of("path", "src/A.java", "line", 1, "column", 1));
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("initialize");
        // not m.stop(): a failed start must clean up by itself, before anyone stops the manager
        long deadline = System.currentTimeMillis() + 5_000;
        while (liveChildren() > before && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(liveChildren()).as("the server process outlived its failed start").isEqualTo(before);
        m.stop();
    }

    private static long liveChildren() {
        return ProcessHandle.current().children().filter(ProcessHandle::isAlive).count();
    }

    @Test
    void aMalformedFrameMarksTheClientDeadSoTheNextCallRestartsIt() throws Exception {
        LspClient c = new LspClient(fakeServer("--garbage"), workspace, java.time.Duration.ofSeconds(10), List.of());
        try {
            long deadline = System.currentTimeMillis() + 5_000;
            while (c.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(c.isAlive()).isFalse();
        } finally {
            c.close();
        }
    }

    @Test
    void diagnosticsNameAPathOutsideTheWorkspaceByFileOnlyAndOneInsideRelatively() throws IOException {
        Files.writeString(workspace.resolve("src/A.java"), "class A {\n    MISSINGLIB x;\n}\n");
        ToolResult r = call(tools.lspDiagnosticsTool(lsp), Map.of("path", "src/A.java"));

        assertThat(r.content()).isEqualTo("src/A.java:2:1 error: Missing required library: 'lib-1.0.jar' for src/A.java");
    }

    @Test
    void theAcpProfileLeavesTheLanguageServerToolsOutUntilTheyAreAdded() throws IOException {
        // the editor's allow-list is explicit (CLAUDE.md sandboxing invariants): new tools join it on purpose
        List<String> acp = new java.util.ArrayList<>();
        try (var in = getClass().getResourceAsStream("/application.yml")) {
            for (Object doc : new org.yaml.snakeyaml.Yaml().loadAll(in)) {
                if (doc instanceof Map<?, ?> m && String.valueOf(m).contains("on-profile=acp")) {
                    Object tools = ((Map<?, ?>) ((Map<?, ?>) m.get("agent")).get("tools"));
                    acp.addAll(((List<?>) ((Map<?, ?>) tools).get("enabled")).stream().map(String::valueOf).toList());
                }
            }
        }
        assertThat(acp).contains("read_file").doesNotContain("diagnostics", "definition", "references", "symbols");
    }

    // --- registration -------------------------------------------------------------------

    @Configuration
    @EnableConfigurationProperties(AgentProperties.class)
    static class Wiring {
        @Bean
        Path agentWorkspace() throws IOException {
            return Files.createTempDirectory("lsp-ws");
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Wiring.class, LspManager.class, LspTools.class);

    @Test
    void theToolsAreAbsentWhenDisabledOrWithNoServer() {
        runner.run(ctx -> assertThat(ctx.getBeansOfType(Tool.class)).isEmpty());
        runner.withPropertyValues("agent.tools.lsp.enabled=true")
                .run(ctx -> assertThat(ctx.getBeansOfType(Tool.class)).isEmpty());
        runner.withPropertyValues("agent.tools.lsp.servers.java.command[0]=jdtls")
                .run(ctx -> assertThat(ctx.getBeansOfType(Tool.class)).isEmpty());
        // a command but no extensions serves no file: no tools rather than tools that always fail
        runner.withPropertyValues("agent.tools.lsp.enabled=true", "agent.tools.lsp.servers.java.command[0]=jdtls")
                .run(ctx -> assertThat(ctx.getBeansOfType(Tool.class)).isEmpty());
    }

    @Test
    void theFourToolsAreRegisteredWhenOnWithAServer() {
        runner.withPropertyValues("agent.tools.lsp.enabled=true", "agent.tools.lsp.servers.java.command[0]=jdtls",
                        "agent.tools.lsp.servers.java.extensions[0]=.java")
                .run(ctx -> assertThat(ctx.getBeansOfType(Tool.class).values()).extracting(Tool::name)
                        .containsExactlyInAnyOrder("diagnostics", "definition", "references", "symbols"));
    }
}
