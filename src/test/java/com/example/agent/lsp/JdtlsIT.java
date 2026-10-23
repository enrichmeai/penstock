package com.example.agent.lsp;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolResult;
import com.example.agent.tools.ToolContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The language-server tools (#190) against a real Eclipse jdtls, not the scripted server. Runs
 * when JDTLS_HOME points at an unpacked jdtls (CI downloads a pinned one and checks its sha256;
 * jdtls 1.63 needs Java 25, so JDTLS_JAVA names a Java 25 {@code java} when the tests run on 21);
 * otherwise it reports itself as skipped, never as passed.
 *
 * <p>The case grep cannot answer: {@code Calc} has two {@code add} overloads, and the compiler
 * knows which one each call reaches.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdtlsIT {

    private static final String CALC = """
            public class Calc {
                public int add(int a, int b) {
                    return a + b;
                }

                public String add(String a, String b) {
                    return a + b;
                }

                public void use() {
                    int n = add(1, 2);
                    String s = add("a", "b");
                }
            }
            """;

    Path workspace;
    Path data;
    LspManager lsp;
    LspTools tools = new LspTools();

    @BeforeAll
    void start() throws IOException {
        String home = System.getenv("JDTLS_HOME");
        assumeTrue(home != null && Files.isExecutable(Path.of(home, "bin", "jdtls")),
                "JDTLS_HOME is not set to an unpacked jdtls; this test did not run");
        workspace = Files.createTempDirectory("jdtls-ws").toRealPath();
        // At the root, in the default package: jdtls takes a loose file's folder as its source root.
        Files.writeString(workspace.resolve("Calc.java"), CALC);
        data = Files.createTempDirectory("jdtls-data");
        AgentProperties props = new AgentProperties();
        props.getTools().getLsp().setEnabled(true);
        // Bounded so a stalled server fails these tests inside CI's step timeout, not after it.
        props.getTools().getLsp().setTimeoutSeconds(60);
        props.getTools().getLsp().setDiagnosticsWaitSeconds(60);
        AgentProperties.Lsp.Server java = new AgentProperties.Lsp.Server();
        List<String> command = new java.util.ArrayList<>(
                List.of(Path.of(home, "bin", "jdtls").toString(), "-data", data.toString()));
        String serverJava = System.getenv("JDTLS_JAVA");
        if (serverJava != null && !serverJava.isBlank()) {
            command.add("--java-executable");
            command.add(serverJava);
        }
        java.setCommand(command);
        java.setExtensions(List.of(".java"));
        java.setEnv(List.of("JAVA_HOME"));
        props.getTools().getLsp().getServers().put("java", java);
        lsp = new LspManager(props, workspace);
        // One answered request first, so no test races jdtls importing the folder as a project.
        int line = lineOf(CALC, "int n = add(1, 2)");
        int column = CALC.split("\n")[line - 1].indexOf("add(") + 1;
        assertThat(call(tools.lspDefinitionTool(lsp), Map.of("path", "Calc.java", "line", line, "column", column))
                .content()).startsWith("Calc.java:");
    }

    @AfterAll
    void stop() {
        if (lsp != null) {
            lsp.stop();
        }
        for (Path dir : new Path[] {workspace, data}) {
            if (dir != null) {
                org.springframework.util.FileSystemUtils.deleteRecursively(dir.toFile());
            }
        }
    }

    private static ToolResult call(com.example.agent.tools.Tool t, Map<String, Object> args) {
        return t.execute("c1", args, new ToolContext("alice", "s1"));
    }

    private static int lineOf(String text, String needle) {
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i + 1;
            }
        }
        throw new AssertionError(needle);
    }

    @Test
    void definitionResolvesEachOverloadToItsOwnDeclaration() {
        int intCall = lineOf(CALC, "int n = add(1, 2)");
        int stringCall = lineOf(CALC, "String s = add(\"a\", \"b\")");
        int col = CALC.split("\n")[intCall - 1].indexOf("add(") + 1;
        int scol = CALC.split("\n")[stringCall - 1].indexOf("add(") + 1;

        ToolResult toInt = call(tools.lspDefinitionTool(lsp), Map.of("path", "Calc.java", "line", intCall, "column", col));
        ToolResult toString = call(tools.lspDefinitionTool(lsp), Map.of("path", "Calc.java", "line", stringCall, "column", scol));

        assertThat(toInt.content()).startsWith("Calc.java:" + lineOf(CALC, "public int add(int a, int b)") + ":");
        assertThat(toString.content()).startsWith("Calc.java:" + lineOf(CALC, "public String add(String a, String b)") + ":");
    }

    @Test
    void referencesOfOneOverloadFindOnlyItsOwnCall() {
        int decl = lineOf(CALC, "public int add(int a, int b)");
        int col = CALC.split("\n")[decl - 1].indexOf("add(") + 1;

        ToolResult r = call(tools.lspReferencesTool(lsp), Map.of("path", "Calc.java", "line", decl, "column", col));

        assertThat(r.content().lines()).containsExactly(
                "Calc.java:" + lineOf(CALC, "int n = add(1, 2)") + ":" + (CALC.split("\n")[lineOf(CALC, "int n = add(1, 2)") - 1].indexOf("add(") + 1));
    }

    @Test
    void diagnosticsReportATypeError() throws IOException {
        Path broken = workspace.resolve("Broken.java");
        Files.writeString(broken, "public class Broken {\n    int n = \"not a number\";\n}\n");

        ToolResult r = call(tools.lspDiagnosticsTool(lsp), Map.of("path", "Broken.java"));

        assertThat(r.content().lines())
                .singleElement()
                .satisfies(l -> assertThat(l).startsWith("Broken.java:2:").contains(" error: ").contains("Type mismatch"));
    }
}
