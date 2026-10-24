package com.example.agent.tools;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestResultsToolTest {

    @TempDir Path workspace;
    @TempDir Path outside;
    private AgentProperties props;
    private TestResultsTool tool;

    /**
     * A real Gradle 8 report (JUnit 5.12, JDK 21): an assertEquals failure, an I/O exception and a
     * throw inside a lambda. Gradle writes every exception as a failure, and prefixes frames with the
     * class loader ({@code app//}) and the module ({@code java.base@21.0.10/}).
     */
    static String gradle() throws Exception {
        try (var in = TestResultsToolTest.class.getResourceAsStream("/test-results/TEST-demo.DTest.xml")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** A Maven report: an assertion failure, an error with a cause, and a pass. */
    static final String SUREFIRE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.acme.ApiTest" tests="3" failures="1" errors="1" skipped="0">
              <testcase name="getsUser" classname="com.acme.ApiTest" time="0.1">
                <failure type="java.lang.AssertionError">java.lang.AssertionError: status 404
            	at com.acme.ApiTest.getsUser(ApiTest.java:12)
            </failure>
              </testcase>
              <testcase name="savesUser" classname="com.acme.ApiTest" time="0.1">
                <error message="could not save" type="java.lang.IllegalStateException">java.lang.IllegalStateException: could not save
            	at com.acme.Store.save(Store.java:40)
            	at com.acme.ApiTest.savesUser(ApiTest.java:30)
            	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
            	at org.apache.maven.surefire.junitplatform.JUnitPlatformProvider.invoke(JUnitPlatformProvider.java:1)
            	Suppressed: java.io.IOException: close failed
            		at com.acme.Store.close(Store.java:60)
            	Caused by: java.io.EOFException: suppressed cause
            Caused by: java.sql.SQLException: disk full
            	at com.acme.Store.write(Store.java:55)
            	Suppressed: java.io.IOException: rollback failed
            		at com.acme.Store.rollback(Store.java:70)
            	Caused by: java.io.EOFException: a later suppressed cause
            </error>
              </testcase>
              <testcase name="listsUsers" classname="com.acme.ApiTest" time="0.1"/>
            </testsuite>
            """;

    @BeforeEach
    void setUp() {
        props = new AgentProperties();
        props.setWorkspace(workspace.toString());
        tool = new TestResultsTool(workspace, props);
    }

    private ToolResult run(Map<String, Object> args) {
        return tool.execute("c1", args, ToolContext.anonymous());
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void aRealGradleReportShowsEachTestsOwnFrame() throws Exception {
        write(workspace.resolve("build/test-results/test/TEST-demo.DTest.xml"), gradle());

        String out = run(Map.of()).content();

        assertThat(out).startsWith("Tests: 3, failed: 3, errors: 0, skipped: 0\nFrom 1 report, newest written ");
        assertThat(out).contains("""
                FAILED demo.DTest > stream()  [java.lang.IllegalStateException]
                  message: java.lang.IllegalStateException: x
                  at demo.DTest.lambda$stream$0(DTest.java:7)
                  at demo.DTest.stream(DTest.java:7)

                FAILED demo.DTest > eq()""");
        assertThat(out).contains("""
                FAILED demo.DTest > eq()  [org.opentest4j.AssertionFailedError]
                  message: org.opentest4j.AssertionFailedError: expected: <4> but was: <5>
                  at demo.DTest.eq(DTest.java:5)

                FAILED demo.DTest > io()""");
        assertThat(out).endsWith("""
                FAILED demo.DTest > io()  [java.nio.file.NoSuchFileException]
                  message: java.nio.file.NoSuchFileException: /nope
                  at demo.DTest.io(DTest.java:6)
                """);
        // Not the assertion library's frames, not the JDK's I/O layers, not the loader prefixes.
        assertThat(out).doesNotContain("AssertionFailureBuilder", "sun.nio", "Method.invoke", "app//", "java.base");
    }

    @Test
    void namesTheFailureAndTheErrorWithTheirMessages() throws Exception {
        write(workspace.resolve("target/surefire-reports/TEST-com.acme.ApiTest.xml"), SUREFIRE);

        String out = run(Map.of()).content();

        assertThat(out).startsWith("Tests: 3, failed: 1, errors: 1, skipped: 0\nFrom 1 report");
        // No message attribute: the trace's first line stands in.
        assertThat(out).contains("""
                FAILED com.acme.ApiTest > getsUser  [java.lang.AssertionError]
                  message: java.lang.AssertionError: status 404
                  at com.acme.ApiTest.getsUser(ApiTest.java:12)
                """);
        // The main chain's cause, not the suppressed exception's.
        assertThat(out).endsWith("""
                ERROR com.acme.ApiTest > savesUser  [java.lang.IllegalStateException]
                  message: could not save
                  at com.acme.Store.save(Store.java:40)
                  at com.acme.ApiTest.savesUser(ApiTest.java:30)
                  root cause: java.sql.SQLException: disk full
                """);
    }

    @Test
    void pathNarrowsTheSearchToOneModule() throws Exception {
        write(workspace.resolve("api/target/surefire-reports/TEST-com.acme.ApiTest.xml"), SUREFIRE);
        write(workspace.resolve("core/build/test-results/test/TEST-demo.DTest.xml"), gradle());

        assertThat(run(Map.of()).content()).startsWith("Tests: 6, failed: 4, errors: 1, skipped: 0\nFrom 2 reports");
        String api = run(Map.of("path", "api")).content();
        assertThat(api).startsWith("Tests: 3, failed: 1, errors: 1, skipped: 0\nFrom 1 report");
        assertThat(api).doesNotContain("DTest");
    }

    @Test
    void aPathAtOrInsideAReportDirectoryFindsItsReports() throws Exception {
        write(workspace.resolve("build/test-results/test/TEST-demo.DTest.xml"), gradle());

        for (String path : new String[] {"build", "build/test-results", "build/test-results/test"}) {
            assertThat(run(Map.of("path", path)).content()).as(path).startsWith("Tests: 3, failed: 3");
        }
    }

    @Test
    void aSharedNameNeverHidesAFailure() throws Exception {
        // As Gradle writes them: a retried test, and a parameterised test whose two cases share a name.
        write(workspace.resolve("build/test-results/test/TEST-X.xml"), """
                <testsuite>
                  <testcase name="flaky()" classname="X"><failure message="first run">boom</failure></testcase>
                  <testcase name="flaky()" classname="X"/>
                  <testcase name="adds" classname="X"><failure message="case 1 broke">boom</failure></testcase>
                  <testcase name="adds" classname="X"/>
                  <testcase name="broken()" classname="X"/>
                  <testcase name="broken()" classname="X"><failure message="second run">boom</failure></testcase>
                </testsuite>
                """);

        String out = run(Map.of()).content();

        assertThat(out).startsWith("Tests: 6, failed: 3, errors: 0, skipped: 0");
        String note = "  note: a later entry with the same name passed: a retry, or another test with this name\n";
        assertThat(out).contains("  message: first run\n" + note, "  message: case 1 broke\n" + note);
        assertThat(out).contains("FAILED X > broken()\n  message: second run\n").doesNotContain("second run\n" + note);
    }

    @Test
    void manyFailuresInOneReportStayFast() throws Exception {
        StringBuilder suite = new StringBuilder("<testsuite>");
        for (int i = 0; i < 50_000; i++) {
            suite.append("<testcase name=\"t").append(i).append("\" classname=\"X\"><failure/></testcase>");
        }
        write(workspace.resolve("build/test-results/test/TEST-X.xml"), suite.append("</testsuite>").toString());

        long start = System.nanoTime();
        assertThat(run(Map.of()).content()).startsWith("Tests: 50000, failed: 50000");
        // Linear: a pairwise scan for a later pass took 30 s at 30,000 failures.
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(10));
    }

    @Test
    void aWalkStoppedByTheDirectoryCapSaysSo() throws Exception {
        for (int i = 0; i <= TestResultsTool.MAX_DIRS; i++) {
            Files.createDirectories(workspace.resolve("data/d" + i));
        }

        String out = run(Map.of()).content();

        assertThat(out).startsWith("No test reports under .")
                .endsWith("Stopped after " + TestResultsTool.MAX_DIRS + " directories: pass `path` (a module) to search all of it.\n");
    }

    @Test
    void aMonorepoUnderSrcIsSearchedButResourceFixturesAreNot() throws Exception {
        write(workspace.resolve("mono/src/api/build/test-results/test/TEST-demo.DTest.xml"), gradle());
        write(workspace.resolve("mono/src/api/src/test/resources/test-results/TEST-fixture.xml"), SUREFIRE);
        write(workspace.resolve(".venv/lib/build/test-results/TEST-vendored.xml"), SUREFIRE);

        assertThat(run(Map.of()).content()).startsWith("Tests: 3, failed: 3, errors: 0, skipped: 0\nFrom 1 report");
    }

    @Test
    void noReportsIsAClearResultNotAnError() throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java"));
        // A TEST-*.xml outside a report directory is not a report, nor is a fixture under src/.
        write(workspace.resolve("docs/TEST-notes.xml"), SUREFIRE);
        write(workspace.resolve("src/test/resources/test-results/TEST-demo.DTest.xml"), gradle());

        ToolResult r = run(Map.of());

        assertThat(r.isError()).isFalse();
        assertThat(r.content()).startsWith("No test reports under . (looked for TEST-*.xml in build/test-results and target/surefire-reports).");
    }

    @Test
    void aReportOutsideTheWorkspaceIsNeverRead() throws Exception {
        write(outside.resolve("build/test-results/test/TEST-com.acme.ApiTest.xml"), SUREFIRE);
        write(outside.resolve("TEST-com.acme.Leak.xml"), SUREFIRE);
        // A linked report directory, a linked report, and a path that climbs out.
        Files.createDirectories(workspace.resolve("build"));
        Files.createSymbolicLink(workspace.resolve("build/test-results"), outside.resolve("build/test-results"));
        Files.createDirectories(workspace.resolve("target/surefire-reports"));
        Files.createSymbolicLink(workspace.resolve("target/surefire-reports/TEST-com.acme.Leak.xml"),
                outside.resolve("TEST-com.acme.Leak.xml"));
        Files.createSymbolicLink(workspace.resolve("linked"), outside);

        assertThat(run(Map.of()).content()).startsWith("No test reports under .");
        assertThat(run(Map.of("path", "linked")).isError()).isTrue();
        assertThat(run(Map.of("path", "linked/build")).isError()).isTrue();
        assertThat(run(Map.of("path", "../" + outside.getFileName())).isError()).isTrue();
        assertThat(run(Map.of("path", outside.toString())).isError()).isTrue();
    }

    @Test
    void aDoctypeIsRefusedSoNoEntityIsFetched() throws Exception {
        write(outside.resolve("secret.txt"), "TOKEN-VALUE");
        write(workspace.resolve("build/test-results/test/TEST-x.xml"), """
                <?xml version="1.0"?>
                <!DOCTYPE t [<!ENTITY s SYSTEM "file://%s">]>
                <testsuite><testcase name="t" classname="X"><failure>&s;</failure></testcase></testsuite>
                """.formatted(outside.resolve("secret.txt")));
        write(workspace.resolve("build/test-results/test/TEST-bad.xml"), "not xml");

        ToolResult r = run(Map.of());

        assertThat(r.content()).startsWith("Tests: 0, failed: 0, errors: 0, skipped: 0\nFrom 2 reports");
        assertThat(r.content()).contains("Not read (not counted above): build/test-results/test/TEST-bad.xml "
                + "(not a readable JUnit report); build/test-results/test/TEST-x.xml (not a readable JUnit report)");
        // An entity in an attribute is malformed anyway; in content, only the DOCTYPE refusal stops it.
        assertThat(r.content()).doesNotContain("TOKEN-VALUE");
    }

    @Test
    void failuresMessagesAndFieldsAreBounded() throws Exception {
        StringBuilder suite = new StringBuilder("<testsuite>");
        String longMessage = "x".repeat(2000);
        for (int i = 0; i < 60; i++) {
            suite.append("<testcase name=\"t").append(i).append("\" classname=\"X\"><failure message=\"")
                    .append(longMessage).append("\"/></testcase>");
        }
        write(workspace.resolve("build/test-results/test/TEST-X.xml"), suite.append("</testsuite>").toString());

        ToolResult defaults = run(Map.of());
        assertThat(defaults.content()).contains("failed: 60");
        assertThat(defaults.content().split("\nFAILED ", -1)).hasSize(1 + TestResultsTool.DEFAULT_FAILURES);
        assertThat(defaults.content()).contains("... and 50 more not shown (max_failures)");
        assertThat(defaults.content()).contains("  message: " + "x".repeat(TestResultsTool.MAX_MESSAGE_CHARS) + " ...\n");

        Map<String, Object> many = new HashMap<>();
        many.put("max_failures", 1000);
        String capped = run(many).content();
        // 50 asked for, but 50 messages of 500 characters do not fit the output bound.
        assertThat(capped.length()).isLessThan(TestResultsTool.MAX_OUTPUT_CHARS + 200);
        assertThat(capped).containsPattern("\\.\\.\\. and \\d+ more not shown \\(" + TestResultsTool.MAX_OUTPUT_CHARS + " characters\\)");
        assertThat(run(Map.of("max_failures", "2")).content().split("\nFAILED ", -1)).hasSize(3);

        // A huge test name and a huge frame are cut too.
        String name = "n".repeat(100_000);
        write(workspace.resolve("build/test-results/test/TEST-X.xml"), "<testsuite><testcase name=\"" + name
                + "\" classname=\"X\"><failure>E\n\tat com.acme." + "f".repeat(100_000) + "(F.java:1)</failure></testcase></testsuite>");
        String cut = run(Map.of()).content();
        assertThat(cut.length()).isLessThan(2000);
        assertThat(cut).contains("FAILED X > " + "n".repeat(TestResultsTool.MAX_FIELD_CHARS) + " ...\n",
                "  at com.acme." + "f".repeat(TestResultsTool.MAX_FIELD_CHARS - "at com.acme.".length()) + " ...\n");
    }

    @Test
    void framesSkipTheJdkAndStopAtTheNextFrameworkFrame() {
        String trace = """
                java.lang.AssertionError: boom
                	at app//org.assertj.core.api.AbstractAssert.isEqualTo(AbstractAssert.java:1)
                	at app//com.acme.A.a(A.java:1)
                	at java.base@21.0.10/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
                	at app//com.acme.A.b(A.java:2)
                	at app//org.junit.platform.commons.util.ReflectionUtils.invokeMethod(ReflectionUtils.java:767)
                	at app//com.acme.A.c(A.java:3)
                """;
        assertThat(TestResultsTool.topFrames(trace)).containsExactly("at com.acme.A.a(A.java:1)", "at com.acme.A.b(A.java:2)");

        StringBuilder deep = new StringBuilder("E\n");
        for (int i = 0; i < 20; i++) deep.append("\tat com.acme.D.f").append(i).append("(D.java:").append(i).append(")\n");
        assertThat(TestResultsTool.topFrames(deep.toString())).hasSize(TestResultsTool.MAX_FRAMES);

        // A suppressed exception's frames are not the test's.
        String suppressed = "X: boom\n\tat app//demo.T.t(T.java:5)\n\tat java.base/java.lang.reflect.Method.invoke(Method.java:580)\n"
                + "\tSuppressed: java.io.IOException: close\n\t\tat app//demo.Res.close(Res.java:9)\n";
        assertThat(TestResultsTool.topFrames(suppressed)).containsExactly("at demo.T.t(T.java:5)");

        // Nothing of the code under test: the first three frames, as they are.
        String jdkOnly = "E\n\tat java.base/java.lang.Integer.parseInt(Integer.java:1)\n\tat java.base/java.lang.Integer.valueOf(Integer.java:2)\n"
                + "\tat java.base/java.lang.Thread.run(Thread.java:3)\n\tat java.base/java.lang.Thread.run(Thread.java:4)\n";
        assertThat(TestResultsTool.topFrames(jdkOnly)).containsExactly("at java.lang.Integer.parseInt(Integer.java:1)",
                "at java.lang.Integer.valueOf(Integer.java:2)", "at java.lang.Thread.run(Thread.java:3)");
    }

    @Test
    void loaderAndModulePrefixesGoButAHiddenClassKeepsItsName() {
        assertThat(TestResultsTool.withoutLoader("app//demo.T.t(T.java:5)")).isEqualTo("demo.T.t(T.java:5)");
        assertThat(TestResultsTool.withoutLoader("java.base@21.0.10/java.lang.Thread.run(Thread.java:1)"))
                .isEqualTo("java.lang.Thread.run(Thread.java:1)");
        assertThat(TestResultsTool.withoutLoader("com.foo.loader/foo@9.0/com.foo.Main.run(Main.java:101)"))
                .isEqualTo("com.foo.Main.run(Main.java:101)");
        assertThat(TestResultsTool.withoutLoader("com.acme.Foo$$Lambda/0x0000020001234.run(Unknown Source)"))
                .isEqualTo("com.acme.Foo$$Lambda/0x0000020001234.run(Unknown Source)");
        assertThat(TestResultsTool.withoutLoader("java.base/java.lang.invoke.LambdaForm$MH/0x00000200.invoke(LambdaForm$MH)"))
                .isEqualTo("java.lang.invoke.LambdaForm$MH/0x00000200.invoke(LambdaForm$MH)");
        assertThat(TestResultsTool.withoutLoader("demo.T.t(/abs/T.java:5)")).isEqualTo("demo.T.t(/abs/T.java:5)");
    }

    @Test
    void offWhenTheFileToolsAreOff() throws Exception {
        write(workspace.resolve("build/test-results/test/TEST-demo.DTest.xml"), gradle());
        props.getTools().getFile().setEnabled(false);

        ToolResult r = run(Map.of());

        assertThat(r.isError()).isTrue();
        assertThat(r.content()).isEqualTo("File tool is disabled.");
    }
}
