package com.example.agent.memory;

import com.example.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Global memory (#195): a second, read-only root of public patterns in category folders
 * ({@code patterns/<category>/<id>/}), loaded below the owner's own root, with the root's
 * {@code stack:} choosing one layer per category.
 */
class GlobalMemoryTest {

    @TempDir
    Path root;

    @TempDir
    Path global;

    private PatternCatalog catalog() {
        AgentProperties props = new AgentProperties();
        props.getMemory().setEnabled(true);
        props.getMemory().setRoot(root.toString());
        props.getMemory().setGlobalRoot(global.toString());
        return new PatternCatalog(props);
    }

    private static void pattern(Path dir, String id, String visibility, String body) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.yaml"), "id: " + id + "\nformat: 1\nvisibility: " + visibility
                + "\nversion: 1\ntriggers: [" + id + "]\nverified-against: { tag: t, date: 2026-10-21 }\n");
        Files.writeString(dir.resolve("PATTERN.md"), body);
    }

    private void globalLayer(String category, String id) throws IOException {
        pattern(global.resolve("patterns").resolve(category).resolve(id), id, "public", "global " + id);
    }

    private void stack(String yaml) throws IOException {
        Files.writeString(root.resolve("memory.yaml"), "format: 1\nvisibility: private\n" + yaml);
    }

    @Test
    void loadsEveryCategorisedGlobalLayerWhenNoStackIsDeclared() throws IOException {
        globalLayer("language", "java21-spring-boot");
        globalLayer("cloud", "gcp-cloud-run");
        PatternCatalog catalog = catalog();
        catalog.initialLoad();

        assertThat(catalog.patterns()).extracting(PatternManifest::id)
                .containsExactlyInAnyOrder("java21-spring-boot", "gcp-cloud-run");
        assertThat(catalog.sectionDecisions()).extracting(PatternCatalog.SectionDecision::section).contains("global");
    }

    @Test
    void theStackChoosesOneLayerPerCategoryAndNothingElse() throws IOException {
        globalLayer("language", "java21-spring-boot");
        globalLayer("language", "python-fastapi");
        globalLayer("architecture", "rest-service");
        globalLayer("cloud", "gcp-cloud-run");
        globalLayer("cloud", "aws-lambda");
        globalLayer("ci", "github-actions-gradle");
        globalLayer("ci", "gitlab-ci");
        stack("stack:\n  language: java21-spring-boot\n  architecture: rest-service\n  cloud: gcp-cloud-run\n"
                + "  ci: github-actions-gradle\n");
        PatternCatalog catalog = catalog();
        catalog.initialLoad();

        assertThat(catalog.patterns()).extracting(PatternManifest::id).containsExactlyInAnyOrder(
                "java21-spring-boot", "rest-service", "gcp-cloud-run", "github-actions-gradle");
    }

    @Test
    void aCategoryTheStackDoesNotNameLoadsNothing() throws IOException {
        globalLayer("language", "java21-spring-boot");
        globalLayer("cloud", "gcp-cloud-run");
        stack("stack: { language: java21-spring-boot }\n");
        PatternCatalog catalog = catalog();
        catalog.initialLoad();

        assertThat(catalog.patterns()).extracting(PatternManifest::id).containsExactly("java21-spring-boot");
    }

    @Test
    void theOwnersPatternOverridesTheGlobalOneWithTheSameId() throws IOException {
        globalLayer("architecture", "rest-service");
        globalLayer("language", "java21-spring-boot");
        pattern(root.resolve("patterns").resolve("rest-service"), "rest-service", "private", "my own rest-service");
        PatternCatalog catalog = catalog();
        catalog.initialLoad();

        assertThat(catalog.patterns()).extracting(PatternManifest::id)
                .containsExactlyInAnyOrder("rest-service", "java21-spring-boot");
        assertThat(catalog.patterns()).filteredOn(p -> p.id().equals("rest-service"))
                .extracting(PatternManifest::patternMd).containsExactly("my own rest-service");
    }

    @Test
    void aGlobalCardThatIsNotPublicIsRefused() throws IOException {
        pattern(global.resolve("patterns/language/leaked"), "leaked", "private", "not for everyone");
        globalLayer("language", "java21-spring-boot");
        PatternCatalog catalog = catalog();
        catalog.initialLoad();

        assertThat(catalog.patterns()).extracting(PatternManifest::id).containsExactly("java21-spring-boot");
    }

    @Test
    void aDeclaredProjectsStackWinsOverTheRootsStack() throws IOException {
        globalLayer("cloud", "gcp-cloud-run");
        globalLayer("cloud", "aws-lambda");
        Files.createDirectories(root.resolve("estate"));
        Files.createDirectories(root.resolve("projects/site"));
        stack("stack: { cloud: gcp-cloud-run }\nprojects:\n  site:\n    audience: public\n    stack: { cloud: aws-lambda }\n");
        PatternCatalog catalog = catalog();
        catalog.props().getMemory().setProject("site");
        catalog.initialLoad();

        assertThat(catalog.patterns()).extracting(PatternManifest::id).containsExactly("aws-lambda");
    }

    @Test
    void noGlobalRootLoadsNoGlobalPatternsAndRecordsNoDecision() throws IOException {
        globalLayer("language", "java21-spring-boot");
        PatternCatalog catalog = catalog();
        catalog.props().getMemory().setGlobalRoot("");
        catalog.initialLoad();

        assertThat(catalog.patterns()).isEmpty();
        assertThat(catalog.sectionDecisions()).isEmpty();
    }

    @Test
    void aGlobalRootThatDoesNotExistIsRecordedNotFatal() {
        PatternCatalog catalog = catalog();
        catalog.props().getMemory().setGlobalRoot(global.resolve("missing").toString());
        catalog.initialLoad();

        assertThat(catalog.patterns()).isEmpty();
        assertThat(catalog.sectionDecisions()).extracting(PatternCatalog.SectionDecision::loaded).containsExactly(false);
    }

    @Test
    void aGlobalCardWithNoVisibilityIsRefused() throws IOException {
        Path dir = global.resolve("patterns/language/unmarked");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.yaml"), "id: unmarked\nversion: 1\ntriggers: [x]\n");
        globalLayer("language", "java21-spring-boot");
        PatternCatalog catalog = catalog();
        catalog.initialLoad();

        assertThat(catalog.patterns()).extracting(PatternManifest::id).containsExactly("java21-spring-boot");
    }

    @Test
    void symlinksInTheGlobalCheckoutAreNotFollowed() throws IOException {
        Path outside = root.resolve("outside");
        pattern(outside.resolve("secret-layer"), "secret-layer", "public", "from outside the checkout");
        Files.writeString(outside.resolve("credentials"), "aws_secret_access_key = not-for-the-model");
        Files.createDirectories(global.resolve("patterns"));
        Files.createSymbolicLink(global.resolve("patterns/linked-category"), outside);
        Files.createDirectories(global.resolve("patterns/language"));
        Files.createSymbolicLink(global.resolve("patterns/language/linked-layer"), outside.resolve("secret-layer"));
        Path leaky = global.resolve("patterns/cloud/leaky");
        pattern(leaky, "leaky", "public", "unused");
        Files.delete(leaky.resolve("PATTERN.md"));
        Files.createSymbolicLink(leaky.resolve("PATTERN.md"), outside.resolve("credentials"));
        globalLayer("ci", "github-actions-gradle");
        PatternCatalog catalog = catalog();
        catalog.initialLoad();

        assertThat(catalog.patterns()).extracting(PatternManifest::id).containsExactly("github-actions-gradle");
        assertThat(catalog.patterns()).extracting(PatternManifest::patternMd).noneMatch(md -> md.contains("not-for-the-model"));
    }

    @Test
    void aStackEntryThatMatchesNoLayerIsRecorded() throws IOException {
        globalLayer("cloud", "gcp-cloud-run");
        stack("stack: { cloud: gcp-cloud-runn, ci: github-actions-gradle }\n");
        PatternCatalog catalog = catalog();
        catalog.initialLoad();

        assertThat(catalog.patterns()).isEmpty();
        assertThat(catalog.sectionDecisions()).extracting(PatternCatalog.SectionDecision::reason)
                .anyMatch(r -> r.contains("no layer for ci=github-actions-gradle, cloud=gcp-cloud-runn"));
    }

    @Test
    void anUnreadableMemoryYamlLoadsNoGlobalLayer() throws IOException {
        globalLayer("language", "java21-spring-boot");
        Files.writeString(root.resolve("memory.yaml"), "stack: [unclosed\n");
        PatternCatalog catalog = catalog();
        catalog.initialLoad();

        assertThat(catalog.patterns()).isEmpty();
        assertThat(catalog.sectionDecisions()).extracting(PatternCatalog.SectionDecision::loaded).containsExactly(false);
    }

    @Test
    void aFlatRootWithADeclaredProjectUsesThatProjectsStack() throws IOException {
        globalLayer("cloud", "gcp-cloud-run");
        globalLayer("cloud", "aws-lambda");
        stack("stack: { cloud: gcp-cloud-run }\nprojects:\n  site:\n    audience: public\n    stack: { cloud: aws-lambda }\n");
        PatternCatalog catalog = catalog();
        catalog.props().getMemory().setProject("site");
        catalog.initialLoad();

        assertThat(catalog.patterns()).extracting(PatternManifest::id).containsExactly("aws-lambda");
    }
}
