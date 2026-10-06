package com.example.agent.memory;

import com.example.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #112: the root's memory.yaml declares its projects and each one's audience, and a card loads
 * into a project only if everyone who sees that project's output may see the card. The names
 * here are placeholders: oss-lib (public), product and platform (private), standalone (private,
 * no personal memory).
 */
class ProjectAudienceTest {

    @TempDir
    Path root;

    private static final String POLICY = """
            format: 1
            visibility: private
            projects:
              oss-lib:    { audience: public }
              oss-tool:   { audience: public, uses: [product] }
              product:    { audience: private }
              platform:   { audience: private, uses: [oss-lib, product] }
              standalone: { audience: private, personal: none, uses: [oss-lib] }
            """;

    @BeforeEach
    void layout() throws IOException {
        Files.writeString(root.resolve("memory.yaml"), POLICY);
        writeFact("estate", "estate-private", "estate", "private");
        writeFact("estate", "estate-shareable", "estate", "shareable");
        for (String p : List.of("oss-lib", "oss-tool", "product", "platform", "standalone", "undeclared")) {
            writeFact("projects/" + p, p + "-fact", "project:" + p, "private");
        }
    }

    private void writeFact(String section, String id, String scope, String visibility) throws IOException {
        Path dir = root.resolve(section).resolve("facts");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".yaml"), "format: 1\nvisibility: " + visibility + "\nid: " + id +
                "\nstatement: " + id + " holds.\nkind: convention\nsubject: s/" + id + "\nscope: " + scope +
                "\nstatus: asserted\nconfidence: 1.0\nlast_confirmed: 2026-10-06\n");
    }

    private PatternCatalog loadFor(String project) {
        AgentProperties props = new AgentProperties();
        props.getMemory().setEnabled(true);
        props.getMemory().setRoot(root.toString());
        props.getMemory().setProject(project);
        PatternCatalog catalog = new PatternCatalog(props);
        catalog.initialLoad();
        return catalog;
    }

    private List<String> factIds(String project) {
        return loadFor(project).facts().stream().map(FactCard::id).toList();
    }

    @Test
    void aPublicProjectGetsItsOwnCardsAndOnlyTheShareablePersonalOnes() {
        assertThat(factIds("oss-lib")).containsExactlyInAnyOrder("estate-shareable", "oss-lib-fact");
    }

    @Test
    void aPublicProjectNeverLoadsAPrivateProjectEvenWhenItsUsesNamesOne() {
        assertThat(factIds("oss-tool")).containsExactlyInAnyOrder("estate-shareable", "oss-tool-fact");
    }

    @Test
    void aPrivateProjectGetsAllPersonalMemoryAndWhatItUses() {
        assertThat(factIds("platform")).containsExactlyInAnyOrder(
                "estate-private", "estate-shareable", "platform-fact", "oss-lib-fact", "product-fact");
    }

    @Test
    void aPrivateProjectLoadsNoOtherProjectItDoesNotUse() {
        assertThat(factIds("product")).containsExactlyInAnyOrder("estate-private", "estate-shareable", "product-fact");
    }

    @Test
    void personalNoneLoadsNoPersonalCardAtAll() {
        assertThat(factIds("standalone")).containsExactlyInAnyOrder("standalone-fact", "oss-lib-fact");
    }

    @Test
    void anUndeclaredProjectInADeclaringRootGetsThePersonalMemoryOnly() {
        assertThat(factIds("undeclared")).containsExactlyInAnyOrder("estate-private", "estate-shareable");
    }

    @Test
    void aRootWithNoProjectsBlockBehavesAsBefore() throws IOException {
        Files.writeString(root.resolve("memory.yaml"), "format: 1\nvisibility: private\n");
        assertThat(factIds("oss-tool")).containsExactlyInAnyOrder("estate-private", "estate-shareable", "oss-tool-fact");
        assertThat(loadFor("oss-tool").sectionDecisions()).isEmpty();
    }

    @Test
    void everySectionIsRecordedLoadedOrRefusedWithItsReason() {
        List<PatternCatalog.SectionDecision> d = loadFor("oss-tool").sectionDecisions();
        assertThat(d).extracting(PatternCatalog.SectionDecision::section, PatternCatalog.SectionDecision::loaded)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("estate", true),
                        org.assertj.core.groups.Tuple.tuple("projects/oss-tool", true),
                        org.assertj.core.groups.Tuple.tuple("projects/product", false));
        assertThat(d.get(0).reason()).contains("shareable");
        assertThat(d.get(2).reason()).contains("private").contains("public");

        List<PatternCatalog.SectionDecision> s = loadFor("standalone").sectionDecisions();
        assertThat(s.get(0).section()).isEqualTo("estate");
        assertThat(s.get(0).loaded()).isFalse();
        assertThat(s.get(0).reason()).contains("personal: none");
    }

    @Test
    void aUsesTargetThatIsNotDeclaredIsRefused() throws IOException {
        Files.writeString(root.resolve("memory.yaml"), POLICY + "  extra: { audience: private, uses: [ghost] }\n");
        writeFact("projects/extra", "extra-fact", "project:extra", "private");
        List<PatternCatalog.SectionDecision> d = loadFor("extra").sectionDecisions();
        assertThat(d).anySatisfy(x -> {
            assertThat(x.section()).isEqualTo("projects/ghost");
            assertThat(x.loaded()).isFalse();
            assertThat(x.reason()).contains("not declared");
        });
    }

    @Test
    void aPersonalPatternWithNoVisibilityStaysOutOfAPublicProject() throws IOException {
        Path dir = root.resolve("estate/patterns/p1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.yaml"), "id: p1\nversion: 1\ntriggers: [x]\n");
        assertThat(loadFor("oss-lib").patterns()).isEmpty();
        assertThat(loadFor("product").patterns()).extracting(PatternManifest::id).containsExactly("p1");
    }
}
