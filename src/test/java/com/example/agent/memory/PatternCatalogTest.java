package com.example.agent.memory;

import com.example.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PatternCatalogTest {

    @TempDir
    Path root;

    private PatternCatalog catalog(boolean enabled) {
        AgentProperties props = new AgentProperties();
        props.getMemory().setEnabled(enabled);
        props.getMemory().setRoot(root.toString());
        return new PatternCatalog(props);
    }

    private void writePattern(String id, String manifestYaml, String patternMd, List<String> skeletonFiles) throws IOException {
        Path dir = root.resolve("patterns").resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.yaml"), manifestYaml);
        Files.writeString(dir.resolve("PATTERN.md"), patternMd);
        if (!skeletonFiles.isEmpty()) {
            Path skeleton = dir.resolve("skeleton");
            Files.createDirectories(skeleton);
            for (String f : skeletonFiles) {
                Files.writeString(skeleton.resolve(f), "// skeleton");
            }
        }
    }

    @Test
    void disabledNeverTouchesTheFilesystem() throws IOException {
        writePattern("p1", "id: p1\nversion: 1\ntriggers: [foo]\n", "When it applies...", List.of());
        PatternCatalog catalog = catalog(false);
        catalog.initialLoad();

        assertThat(catalog.patterns()).isEmpty();
    }

    @Test
    void loadsAManifestItsPatternMdAndSkeletonFileNames() throws IOException {
        writePattern("p1",
                "id: p1\nversion: 2\ntriggers: [foo, bar]\nsignatures: [\"**/foo.json\"]\n" +
                        "verified-against:\n  tag: v1.0.0\n  date: 2026-01-15\n",
                "When it applies: foo.",
                List.of("Skel.java", "other.xml"));

        PatternCatalog catalog = catalog(true);
        catalog.initialLoad();

        assertThat(catalog.patterns()).hasSize(1);
        PatternManifest p = catalog.patterns().get(0);
        assertThat(p.id()).isEqualTo("p1");
        assertThat(p.version()).isEqualTo("2");
        assertThat(p.triggers()).containsExactly("foo", "bar");
        assertThat(p.signatures()).containsExactly("**/foo.json");
        assertThat(p.verifiedAgainstDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(p.patternMd()).contains("When it applies: foo.");
        assertThat(p.skeletonFiles()).containsExactly("Skel.java", "other.xml");
    }

    @Test
    void invalidYamlIsSkippedNotFatal() throws IOException {
        Path badDir = root.resolve("patterns").resolve("bad");
        Files.createDirectories(badDir);
        Files.writeString(badDir.resolve("manifest.yaml"), "id: [this is not\n  a valid: mapping");
        writePattern("good", "id: good\nversion: 1\ntriggers: [x]\n", "ok", List.of());

        PatternCatalog catalog = catalog(true);
        catalog.initialLoad();

        assertThat(catalog.patterns()).extracting(PatternManifest::id).containsExactly("good");
    }

    @Test
    void loadsRequestAndReferenceCards() throws IOException {
        Path requests = root.resolve("requests");
        Files.createDirectories(requests);
        Files.writeString(requests.resolve("r1.yaml"),
                "id: r1\nreferences: [ref1]\npatterns: [p1]\n");

        Path references = root.resolve("references");
        Files.createDirectories(references);
        Files.writeString(references.resolve("ref1.yaml"),
                "id: ref1\nversion: \"1\"\nholds:\n  - fact one\n  - fact two\n");

        PatternCatalog catalog = catalog(true);
        catalog.initialLoad();

        assertThat(catalog.requests()).hasSize(1);
        RequestCard r = catalog.requests().get(0);
        assertThat(r.id()).isEqualTo("r1");
        assertThat(r.references()).containsExactly("ref1");
        assertThat(r.patterns()).containsExactly("p1");

        assertThat(catalog.references()).hasSize(1);
        ReferenceCard ref = catalog.references().get(0);
        assertThat(ref.id()).isEqualTo("ref1");
        assertThat(ref.holds()).containsExactly("fact one", "fact two");
    }
}
