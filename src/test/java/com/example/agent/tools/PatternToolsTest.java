package com.example.agent.tools;

import com.example.agent.config.AgentProperties;
import com.example.agent.memory.PatternCatalog;
import com.example.agent.model.ToolResult;
import com.example.agent.service.AuditLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** On-demand skills (#175): list and load only what the project's audience allows; every load audited. */
class PatternToolsTest {

    @TempDir Path root;

    @BeforeEach
    void layout() throws IOException {
        Files.writeString(root.resolve("memory.yaml"), """
                format: 1
                visibility: private
                projects:
                  oss-lib: { audience: public }
                  product: { audience: private }
                """);
        pattern("projects/oss-lib", "oss-pattern", "public", "How the open library ships a release.");
        pattern("projects/product", "product-pattern", "private", "How the private product wires billing.");
        pattern("estate", "estate-private-pattern", "private", "The owner's private way of doing things.");
    }

    private void pattern(String section, String id, String visibility, String summary) throws IOException {
        Path dir = root.resolve(section).resolve("patterns").resolve(id);
        Files.createDirectories(dir.resolve("skeleton"));
        Files.writeString(dir.resolve("manifest.yaml"), "id: " + id + "\nformat: 1\nvisibility: " + visibility
                + "\nversion: 2\ntriggers: [x]\nsignatures: []\nverified-against:\n  tag: v1.0.0\n  date: 2026-10-01\nsupersedes: ~\n");
        Files.writeString(dir.resolve("PATTERN.md"), "# " + id + "\n\n" + summary + "\nSecond line of the same paragraph.\n\n## Steps\n1. do it\n");
        Files.writeString(dir.resolve("skeleton").resolve("Main.java"), "class Main {}\n");
    }

    private PatternCatalog catalogFor(String project) {
        AgentProperties props = new AgentProperties();
        props.getMemory().setEnabled(true);
        props.getMemory().setRoot(root.toString());
        props.getMemory().setProject(project);
        return PatternCatalog.loadedNow(props);
    }

    private static ToolResult call(Tool t, Map<String, Object> args) {
        return t.execute("c1", args, new ToolContext("alice", "s1"));
    }

    @Test
    void aPublicProjectListsOnlyThePatternsItsAudienceMaySee() {
        PatternTools tools = new PatternTools();
        ToolResult r = call(tools.listPatternsTool(catalogFor("oss-lib")), Map.of());
        assertFalse(r.isError());
        assertThat(r.content()).contains("oss-pattern (v2): How the open library ships a release. Second line of the same paragraph.");
        assertThat(r.content()).doesNotContain("product-pattern").doesNotContain("estate-private-pattern");
    }

    @Test
    void aPrivateProjectSeesItsOwnAndThePersonalPatterns() {
        ToolResult r = call(new PatternTools().listPatternsTool(catalogFor("product")), Map.of());
        assertThat(r.content()).contains("product-pattern").contains("estate-private-pattern").doesNotContain("oss-pattern");
    }

    @Test
    void loadingAPatternOutsideTheAudienceIsARefusalNotAnEmptyResult() {
        AuditLogger audit = mock(AuditLogger.class);
        PatternTools.LoadPatternTool load = new PatternTools().loadPatternTool(catalogFor("oss-lib"));
        load.setAuditLogger(audit);
        for (String id : List.of("product-pattern", "estate-private-pattern", "no-such-pattern")) {
            ToolResult r = call(load, Map.of("id", id));
            assertTrue(r.isError(), id);
            assertThat(r.content()).startsWith("Refused: no pattern '" + id + "' is available to this project");
        }
        verifyNoInteractions(audit);
    }

    @Test
    void anAllowedLoadReturnsThePatternAndIsAuditedOnDemand() {
        AuditLogger audit = mock(AuditLogger.class);
        PatternTools.LoadPatternTool load = new PatternTools().loadPatternTool(catalogFor("oss-lib"));
        load.setAuditLogger(audit);
        ToolResult r = call(load, Map.of("id", "oss-pattern"));
        assertFalse(r.isError(), r.content());
        assertThat(r.content()).startsWith("Pattern oss-pattern (v2)").contains("## Steps").contains("- Main.java");
        verify(audit).patternLoaded("alice", "s1", "oss-pattern", "2", "on-demand");
    }

    @Test
    void aLongPatternIsCutAndSaysSo() throws IOException {
        Path md = root.resolve("projects/oss-lib/patterns/oss-pattern/PATTERN.md");
        Files.writeString(md, "# big\n\n" + "é".repeat(PatternTools.MAX_PATTERN_BYTES));   // 2 bytes each
        ToolResult r = call(new PatternTools().loadPatternTool(catalogFor("oss-lib")), Map.of("id", "oss-pattern"));
        assertThat(r.content()).contains("[PATTERN.md cut at " + PatternTools.MAX_PATTERN_BYTES + " bytes of");
        // Under the registry's 16 KB output cap, so its own head-and-tail cut never removes the note.
        assertThat(r.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(new AgentProperties().getTools().getMaxOutputBytes());
    }

    @Test
    void aMissingIdIsAnError() {
        assertTrue(call(new PatternTools().loadPatternTool(catalogFor("oss-lib")), Map.of()).isError());
    }

    @Configuration
    @EnableConfigurationProperties(AgentProperties.class)
    @Import(PatternTools.class)
    static class WithCatalog {
        @Bean PatternCatalog patternCatalog() { return mock(PatternCatalog.class); }
    }

    @Test
    void theToolsExistOnlyWithMemoryEnabled() {
        new ApplicationContextRunner().withUserConfiguration(WithCatalog.class)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(PatternTools.ListPatternsTool.class).doesNotHaveBean(PatternTools.LoadPatternTool.class));
        new ApplicationContextRunner().withUserConfiguration(WithCatalog.class)
                .withPropertyValues("agent.memory.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(PatternTools.ListPatternsTool.class).hasSingleBean(PatternTools.LoadPatternTool.class));
    }

    @Test
    void aDuplicateIdIsListedOnceAndALongIdIsNotEchoedInFull() throws IOException {
        // The same id in a used section: list it once, as load_pattern resolves it (first wins).
        Files.writeString(root.resolve("memory.yaml"), """
                format: 1
                visibility: private
                projects:
                  oss-lib: { audience: public }
                  product: { audience: private, uses: [oss-lib] }
                """);
        pattern("projects/product", "oss-pattern", "private", "A product copy of the same id.");
        ToolResult list = call(new PatternTools().listPatternsTool(catalogFor("product")), Map.of());
        assertEquals(1, list.content().split("oss-pattern \\(v", -1).length - 1, list.content());
        ToolResult r = call(new PatternTools().loadPatternTool(catalogFor("oss-lib")), Map.of("id", "y".repeat(500)));
        assertThat(r.content().length()).isLessThan(250);
    }

    @Configuration
    @EnableConfigurationProperties(AgentProperties.class)
    @Import(PatternTools.class)
    static class WithCatalogAndAudit {
        @Bean PatternCatalog patternCatalog() {
            PatternCatalog c = mock(PatternCatalog.class);
            when(c.patterns()).thenReturn(List.of(new com.example.agent.memory.PatternManifest(
                    "p1", "3", List.of(), List.of(), null, "# p1\n\nA pattern.\n", List.of())));
            return c;
        }
        @Bean AuditLogger auditLogger() { return mock(AuditLogger.class); }
    }

    @Test
    void springWiresTheAuditLoggerIntoTheRegisteredTool() {
        new ApplicationContextRunner().withUserConfiguration(WithCatalogAndAudit.class)
                .withPropertyValues("agent.memory.enabled=true")
                .run(ctx -> {
                    ToolResult r = call(ctx.getBean(PatternTools.LoadPatternTool.class), Map.of("id", "p1"));
                    assertFalse(r.isError(), r.content());
                    verify(ctx.getBean(AuditLogger.class)).patternLoaded("alice", "s1", "p1", "3", "on-demand");
                });
    }

    // --- #195: global memory through the tools --------------------------------------------

    @TempDir Path global;

    private void globalLayer(String category, String id) throws IOException {
        Path dir = global.resolve("patterns").resolve(category).resolve(id);
        Files.createDirectories(dir.resolve("skeleton"));
        Files.writeString(dir.resolve("manifest.yaml"), "id: " + id + "\nformat: 1\nvisibility: public\nversion: 1\n"
                + "triggers: [x]\nverified-against: { tag: t, date: 2026-10-21 }\n");
        Files.writeString(dir.resolve("PATTERN.md"), "# " + id + "\n\nThe " + id + " layer.\n");
        Files.writeString(dir.resolve("skeleton").resolve("build.gradle"), "// skeleton\n");
    }

    @Test
    void listPatternsForAJavaGcpGithubActionsStackListsThoseFourAndNoOthers() throws IOException {
        Path flat = root.resolve("flat");
        Files.createDirectories(flat);
        Files.writeString(flat.resolve("memory.yaml"), "format: 1\nvisibility: private\nstack: { language: java21-spring-boot, "
                + "architecture: rest-service, cloud: gcp-cloud-run, ci: github-actions-gradle }\n");
        for (String[] l : new String[][]{{"language", "java21-spring-boot"}, {"language", "python-fastapi"},
                {"architecture", "rest-service"}, {"cloud", "gcp-cloud-run"}, {"cloud", "aws-lambda"},
                {"ci", "github-actions-gradle"}, {"ci", "gitlab-ci"}}) {
            globalLayer(l[0], l[1]);
        }
        AgentProperties props = new AgentProperties();
        props.getMemory().setEnabled(true);
        props.getMemory().setRoot(flat.toString());
        props.getMemory().setGlobalRoot(global.toString());
        PatternCatalog catalog = PatternCatalog.loadedNow(props);

        ToolResult listed = call(new PatternTools().listPatternsTool(catalog), Map.of());
        assertThat(listed.content()).contains("java21-spring-boot (v1)", "rest-service (v1)", "gcp-cloud-run (v1)",
                "github-actions-gradle (v1)").doesNotContain("python-fastapi", "aws-lambda", "gitlab-ci");
        assertThat(listed.content().lines().filter(l -> l.startsWith("- "))).hasSize(4);

        ToolResult loaded = call(new PatternTools().loadPatternTool(catalog), Map.of("id", "gcp-cloud-run"));
        assertThat(loaded.content()).contains("The gcp-cloud-run layer.").contains("Skeleton files (in its skeleton/ folder)");
    }
}
