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
        assertThat(catalog.facts()).isEmpty();
        assertThat(catalog.episodes()).isEmpty();
    }

    // --- #88: facts and episodes -----------------------------------------------------------

    @Test
    void loadsFactsAndSkipsAnInvalidOne() throws IOException {
        Path facts = root.resolve("facts");
        Files.createDirectories(facts);
        Files.writeString(facts.resolve("good.yaml"), "id: good\nstatement: S.\nkind: account\nsubject: a/b\nscope: estate\n" +
                "status: asserted\nconfidence: 1.0\nlast_confirmed: 2026-10-03\ntriggers: [x, y]\n");
        Files.writeString(facts.resolve("no-statement.yaml"), "id: no-statement\nkind: account\n");
        Files.writeString(facts.resolve("broken.yaml"), "[unclosed\n");
        Files.writeString(facts.resolve("thin.yaml"), "id: thin\nstatement: T.\n"); // no confidence, triggers, date
        PatternCatalog catalog = catalog(true);
        catalog.initialLoad();

        assertThat(catalog.facts()).extracting(FactCard::id).containsExactly("good", "thin");
        FactCard good = catalog.facts().get(0);
        assertThat(good.confidence()).isEqualTo(1.0);
        assertThat(good.lastConfirmed()).isEqualTo(java.time.LocalDate.of(2026, 10, 3));
        assertThat(good.triggers()).containsExactly("x", "y");
        FactCard thin = catalog.facts().get(1);
        assertThat(thin.confidence()).isEqualTo(0.0);
        assertThat(thin.lastConfirmed()).isNull();
        assertThat(thin.triggers()).isEmpty();
        assertThat(thin.isActive()).isTrue();
    }

    @Test
    void loadsEpisodesNewestFirstAndSkipsAnInvalidOne() throws IOException {
        Path episodes = root.resolve("episodes");
        Files.createDirectories(episodes);
        Files.writeString(episodes.resolve("2026-10-01-a.yaml"), "id: 2026-10-01-a\ndate: \"2026-10-01\"\nproject: penstock\nasked: A\n" +
                "decided:\n  - plain string decision\nrefused: ~\nopen: [o1]\n");
        Files.writeString(episodes.resolve("2026-10-02-b.yaml"), "id: 2026-10-02-b\ndate: 2026-10-02\nproject: penstock\nasked: B\n" +
                "decided:\n  - what: did\n    why: because\n");
        Files.writeString(episodes.resolve("2026-10-02-c.yaml"), "id: 2026-10-02-c\ndate: 2026-10-02\nproject: penstock\nasked: C\n");
        Files.writeString(episodes.resolve("2026-10-03-nodate.yaml"), "id: 2026-10-03-nodate\nproject: penstock\n");
        Files.writeString(episodes.resolve("broken.yaml"), "- just\n- a list\n");
        PatternCatalog catalog = catalog(true);
        catalog.initialLoad();

        assertThat(catalog.episodes()).extracting(EpisodeCard::id).containsExactly("2026-10-02-c", "2026-10-02-b", "2026-10-01-a");
        EpisodeCard a = catalog.episodes().get(2);
        assertThat(a.date()).isEqualTo(java.time.LocalDate.of(2026, 10, 1)); // quoted string parsed too
        assertThat(a.decided()).containsExactly("plain string decision");
        assertThat(a.refused()).isEmpty();
        assertThat(a.open()).containsExactly("o1");
        assertThat(catalog.episodes().get(1).decided()).containsExactly("did — because");
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

    // --- #91: a sectioned memory root (estate/ plus projects/<name>/) -------------------------

    private void writeFactIn(Path dir, String id) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".yaml"), "id: " + id + "\nstatement: " + id + " holds.\nkind: convention\n" +
                "subject: s/" + id + "\nscope: estate\nstatus: asserted\nconfidence: 1.0\nlast_confirmed: 2026-10-04\n");
    }

    @Test
    void aSectionedRootLoadsTheEstateAndOnlyTheWorkspacesProject() throws IOException {
        writeFactIn(root.resolve("estate/facts"), "estate-fact");
        writeFactIn(root.resolve("projects/penstock/facts"), "penstock-fact");
        writeFactIn(root.resolve("projects/valuedocs/facts"), "valuedocs-fact");
        Path eps = root.resolve("projects/penstock/episodes");
        Files.createDirectories(eps);
        Files.writeString(eps.resolve("2026-10-04-p.yaml"), "id: 2026-10-04-p\ndate: 2026-10-04\nproject: penstock\nasked: A\n");
        Path otherEps = root.resolve("projects/valuedocs/episodes");
        Files.createDirectories(otherEps);
        Files.writeString(otherEps.resolve("2026-10-04-v.yaml"), "id: 2026-10-04-v\ndate: 2026-10-04\nproject: valuedocs\nasked: V\n");
        PatternCatalog catalog = catalog(true);
        catalog.props().getMemory().setProject("penstock");
        catalog.initialLoad();

        assertThat(catalog.facts()).extracting(FactCard::id).containsExactly("estate-fact", "penstock-fact");
        assertThat(catalog.episodes()).extracting(EpisodeCard::id).containsExactly("2026-10-04-p");
    }

    @Test
    void aSectionedRootWithNoProjectLoadsTheEstateOnly() throws IOException {
        writeFactIn(root.resolve("estate/facts"), "estate-fact");
        writeFactIn(root.resolve("projects/penstock/facts"), "penstock-fact");
        PatternCatalog catalog = catalog(true);
        catalog.props().getMemory().setProject("");
        catalog.props().setWorkspace(null);
        catalog.initialLoad();

        assertThat(catalog.facts()).extracting(FactCard::id).containsExactly("estate-fact");
    }

    @Test
    void formatV1CardsLoadAndAQuotedHoldsLineStaysASentence() throws IOException {
        // #98: format and visibility are ignored by the Java reader; a holds line with ": " in it
        // must be quoted, and then loads as the sentence it is (unquoted, YAML made it a mapping).
        Path references = root.resolve("references");
        Files.createDirectories(references);
        Files.writeString(references.resolve("ref2.yaml"),
                "id: ref2\nformat: 1\nvisibility: public\nversion: \"1\"\nholds:\n" +
                "  - 'a Zed entry adds a type: custom field'\n");
        PatternCatalog catalog = catalog(true);
        catalog.initialLoad();

        assertThat(catalog.references()).extracting(ReferenceCard::id).containsExactly("ref2");
        assertThat(catalog.references().get(0).holds()).containsExactly("a Zed entry adds a type: custom field");
    }

    @Test
    void aSectionedRootTakesTheProjectFromTheWorkspaceFolderOrItsAlias() throws IOException {
        writeFactIn(root.resolve("estate/facts"), "estate-fact");
        writeFactIn(root.resolve("projects/valuedocs/facts"), "valuedocs-fact");
        writeFactIn(root.resolve("projects/cistern/facts"), "cistern-fact");
        PatternCatalog catalog = catalog(true);
        catalog.props().getMemory().setProject("");
        catalog.props().setWorkspace(root.resolve("work/valuedocs").toString());
        catalog.initialLoad();
        assertThat(catalog.facts()).extracting(FactCard::id).containsExactly("estate-fact", "valuedocs-fact");

        catalog.props().setWorkspace(root.resolve("work/valuedocs-data-platform").toString());
        catalog.props().getMemory().getProjectAliases().put("valuedocs-data-platform", "cistern");
        catalog.initialLoad();
        assertThat(catalog.facts()).extracting(FactCard::id).containsExactly("cistern-fact", "estate-fact");
    }

    @Test
    void aSectionedRootRefusesAProjectNameThatIsAPath() throws IOException {
        writeFactIn(root.resolve("estate/facts"), "estate-fact");
        writeFactIn(root.resolve("projects/valuedocs/facts"), "valuedocs-fact");
        writeFactIn(root.resolve("outside/facts"), "outside-fact");
        PatternCatalog catalog = catalog(true);
        for (String name : List.of("../outside", "projects/valuedocs", "..", "ValueDocs", "/tmp", "-x")) {
            catalog.props().getMemory().setProject(name);
            catalog.initialLoad();
            assertThat(catalog.facts()).as(name).extracting(FactCard::id).containsExactly("estate-fact");
        }
    }
}
