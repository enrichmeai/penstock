package com.example.agent.memory;

import com.example.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAssemblerTest {

    @TempDir
    Path root;

    @TempDir
    Path workspace;

    private AgentProperties props(boolean enabled) {
        AgentProperties props = new AgentProperties();
        props.getMemory().setEnabled(enabled);
        props.getMemory().setRoot(root.toString());
        return props;
    }

    private PatternCatalog loadedCatalog(AgentProperties props) {
        PatternCatalog catalog = new PatternCatalog(props);
        catalog.initialLoad();
        return catalog;
    }

    private void writePattern(String id, String version, List<String> triggers, List<String> signatures,
                               String verifiedDate, String patternMd) throws IOException {
        Path dir = root.resolve("patterns").resolve(id);
        Files.createDirectories(dir);
        String triggersYaml = triggers.isEmpty() ? " []" : "\n" + triggers.stream().map(t -> "  - " + t).reduce((a, b) -> a + "\n" + b).orElse("");
        String signaturesYaml = signatures.isEmpty() ? " []" : "\n" + signatures.stream().map(s -> "  - \"" + s + "\"").reduce((a, b) -> a + "\n" + b).orElse("");
        String yaml = "id: " + id + "\nversion: " + version +
                "\ntriggers:" + triggersYaml +
                "\nsignatures:" + signaturesYaml +
                "\nverified-against:\n  tag: v1\n  date: " + verifiedDate + "\n";
        Files.writeString(dir.resolve("manifest.yaml"), yaml);
        Files.writeString(dir.resolve("PATTERN.md"), patternMd);
    }

    private void writeRequest(String id, List<String> references, List<String> patterns) throws IOException {
        Path requests = root.resolve("requests");
        Files.createDirectories(requests);
        String refYaml = references.isEmpty() ? " []" : "\n" + references.stream().map(r -> "  - " + r).reduce((a, b) -> a + "\n" + b).orElse("");
        String patYaml = patterns.isEmpty() ? " []" : "\n" + patterns.stream().map(p -> "  - " + p).reduce((a, b) -> a + "\n" + b).orElse("");
        Files.writeString(requests.resolve(id + ".yaml"), "id: " + id + "\nreferences:" + refYaml + "\npatterns:" + patYaml + "\n");
    }

    private void writeReference(String id, String version, List<String> holds) throws IOException {
        Path references = root.resolve("references");
        Files.createDirectories(references);
        String holdsYaml = holds.isEmpty() ? " []" : "\n" + holds.stream().map(h -> "  - " + h).reduce((a, b) -> a + "\n" + b).orElse("");
        Files.writeString(references.resolve(id + ".yaml"), "id: " + id + "\nversion: \"" + version + "\"\nholds:" + holdsYaml + "\n");
    }

    @Test
    void disabledNeverMatches() throws IOException {
        writePattern("p1", "1", List.of("widget"), List.of(), "2026-01-01", "about widgets");
        AgentProperties props = props(false);
        ContextAssembler assembler = new ContextAssembler(loadedCatalog(props), workspace, props);

        assertThat(assembler.assemble("tell me about widgets")).isEmpty();
    }

    @Test
    void noTriggerOrSignatureHitMeansNoMatch() throws IOException {
        writePattern("p1", "1", List.of("widget"), List.of(), "2026-01-01", "about widgets");
        AgentProperties props = props(true);
        ContextAssembler assembler = new ContextAssembler(loadedCatalog(props), workspace, props);

        assertThat(assembler.assemble("completely unrelated input")).isEmpty();
    }

    @Test
    void triggerHitLoadsThePatternAndAudits() throws IOException {
        writePattern("p1", "3", List.of("widget", "gadget"), List.of(), "2026-01-01", "## About widgets\nDo the widget thing.");
        AgentProperties props = props(true);
        ContextAssembler assembler = new ContextAssembler(loadedCatalog(props), workspace, props);

        Optional<ContextAssembler.Assembled> result = assembler.assemble("how do I build a widget?");
        assertThat(result).isPresent();
        assertThat(result.get().block()).contains("pattern:p1 (v3)").contains("Do the widget thing.");
        assertThat(result.get().loadedPatterns()).containsExactly(new ContextAssembler.LoadedPattern("p1", "3"));
    }

    @Test
    void signatureHitAgainstTheWorkspaceCountsTowardsAMatch() throws IOException {
        Files.writeString(workspace.resolve("marker.json"), "{}");
        writePattern("p1", "1", List.of(), List.of("*.json"), "2026-01-01", "signature-based pattern");
        AgentProperties props = props(true);
        ContextAssembler assembler = new ContextAssembler(loadedCatalog(props), workspace, props);

        assertThat(assembler.assemble("nothing in common with triggers")).isPresent();
    }

    @Test
    void higherHitCountRanksFirst() throws IOException {
        writePattern("one-trigger", "1", List.of("alpha"), List.of(), "2026-01-01", "one trigger pattern");
        writePattern("two-triggers", "1", List.of("alpha", "beta"), List.of(), "2026-01-01", "two trigger pattern");
        AgentProperties props = props(true);
        ContextAssembler assembler = new ContextAssembler(loadedCatalog(props), workspace, props);

        Optional<ContextAssembler.Assembled> result = assembler.assemble("alpha and beta both appear here");
        assertThat(result).isPresent();
        List<ContextAssembler.LoadedPattern> loaded = result.get().loadedPatterns();
        assertThat(loaded.get(0).id()).isEqualTo("two-triggers");
        assertThat(loaded.get(1).id()).isEqualTo("one-trigger");
    }

    @Test
    void tiedHitCountBreaksByNewestVerifiedAgainstDateFirst() throws IOException {
        writePattern("older", "1", List.of("alpha"), List.of(), "2025-01-01", "older pattern");
        writePattern("newer", "1", List.of("alpha"), List.of(), "2026-06-01", "newer pattern");
        AgentProperties props = props(true);
        ContextAssembler assembler = new ContextAssembler(loadedCatalog(props), workspace, props);

        Optional<ContextAssembler.Assembled> result = assembler.assemble("alpha appears once");
        assertThat(result).isPresent();
        assertThat(result.get().loadedPatterns().get(0).id()).isEqualTo("newer");
    }

    @Test
    void atMostThreePatternsAreLoaded() throws IOException {
        for (int i = 1; i <= 5; i++) {
            writePattern("p" + i, "1", List.of("alpha"), List.of(), "2026-01-0" + i, "pattern " + i);
        }
        AgentProperties props = props(true);
        ContextAssembler assembler = new ContextAssembler(loadedCatalog(props), workspace, props);

        Optional<ContextAssembler.Assembled> result = assembler.assemble("alpha triggers every pattern");
        assertThat(result).isPresent();
        assertThat(result.get().loadedPatterns()).hasSize(3);
    }

    @Test
    void referencesOfAMatchedRequestAreIncludedInTheBlock() throws IOException {
        writePattern("p1", "1", List.of("alpha"), List.of(), "2026-01-01", "pattern one");
        writeRequest("req1", List.of("ref1"), List.of("p1"));
        writeReference("ref1", "2", List.of("a trusted fact about alpha"));
        AgentProperties props = props(true);
        ContextAssembler assembler = new ContextAssembler(loadedCatalog(props), workspace, props);

        Optional<ContextAssembler.Assembled> result = assembler.assemble("alpha appears once");
        assertThat(result).isPresent();
        assertThat(result.get().block())
                .contains("reference:ref1 (v2)")
                .contains("a trusted fact about alpha");
    }

    @Test
    void anEntryThatWouldExceedTheCapIsDroppedWhole() throws IOException {
        String hugeMd = "x".repeat(ContextAssembler.MAX_BLOCK_CHARS);
        writePattern("huge", "1", List.of("alpha"), List.of(), "2026-06-01", hugeMd);
        writePattern("small", "1", List.of("alpha"), List.of(), "2025-01-01", "small pattern body");
        AgentProperties props = props(true);
        ContextAssembler assembler = new ContextAssembler(loadedCatalog(props), workspace, props);

        Optional<ContextAssembler.Assembled> result = assembler.assemble("alpha appears once");
        assertThat(result).isPresent();
        // "huge" ranks first (newer date) but its entry alone exceeds the cap along with the
        // header, so it is skipped whole rather than truncated into invalid/partial markdown;
        // "small" still fits and is loaded.
        assertThat(result.get().loadedPatterns()).extracting(ContextAssembler.LoadedPattern::id)
                .doesNotContain("huge");
    }

    @Test
    void signatureHitBeyondTheConfiguredDepthIsNotCounted() throws IOException {
        Path deep = workspace;
        for (int i = 0; i < 12; i++) {
            deep = deep.resolve("d" + i);
        }
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("marker.json"), "{}");
        writePattern("p1", "1", List.of(), List.of("**/*.json"), "2026-01-01", "deep signature pattern");
        AgentProperties props = props(true);
        ContextAssembler assembler = new ContextAssembler(loadedCatalog(props), workspace, props);

        assertThat(assembler.assemble("nothing in common with triggers")).isEmpty();
    }
}
