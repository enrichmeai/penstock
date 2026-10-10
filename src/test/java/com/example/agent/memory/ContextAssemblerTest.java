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

    private void writeFact(String id, String kind, String scope, String status, double confidence,
                           String lastConfirmed, List<String> triggers, String statement) throws IOException {
        Path facts = root.resolve("facts");
        Files.createDirectories(facts);
        String trig = triggers.isEmpty() ? " []" : "\n" + triggers.stream().map(t -> "  - " + t).reduce((a, b) -> a + "\n" + b).orElse("");
        Files.writeString(facts.resolve(id + ".yaml"),
                "id: " + id + "\nstatement: " + statement + "\nkind: " + kind + "\nsubject: s/" + id +
                "\nscope: " + scope + "\nstatus: " + status + "\nconfidence: " + confidence +
                "\nprovenance:\n  - owner: 2026-10-03\nfirst_seen: 2026-10-01\nlast_confirmed: " + lastConfirmed +
                "\nsupersedes: ~\nsuperseded_by: ~\ntriggers:" + trig + "\n");
    }

    private void writeEpisode(String id, String date, String project, String asked, String openLine) throws IOException {
        Path episodes = root.resolve("episodes");
        Files.createDirectories(episodes);
        Files.writeString(episodes.resolve(id + ".yaml"),
                "id: " + id + "\ndate: " + date + "\nproject: " + project + "\nasked: " + asked +
                "\nbuilt:\n  - pr: enrichmeai/penstock#1\n    files: [a.java, b.java, c.java]" +
                "\ndecided:\n  - what: did the thing\n    why: because\nrefused: []\nlearned:\n  - a learned line" +
                "\nopen:\n  - " + openLine + "\n");
    }

    private ContextAssembler assembler(AgentProperties props) {
        return new ContextAssembler(loadedCatalog(props), workspace, props);
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

    // --- #88: facts and episodes join the block ---------------------------------------------

    @Test
    void aFactIsSelectedByAWholeWordTrigger() throws IOException {
        writeFact("console-account", "account", "project:valuedocs", "asserted", 1.0, "2026-10-03",
                List.of("console", "google account"), "The console is used as owner@example.test.");
        AgentProperties props = props(true);
        props.getMemory().setProject("penstock");

        Optional<ContextAssembler.Assembled> hit = assembler(props).assemble("which account for the console?");
        assertThat(hit).isPresent();
        assertThat(hit.get().block()).contains(ContextAssembler.HEADER).contains("fact:console-account")
                .contains("owner@example.test");
        assertThat(hit.get().loadedFacts()).containsExactly(new ContextAssembler.LoadedFact("console-account", "asserted", 1.0));

        // "consoled" contains the trigger but is not the word; no pattern or episode either
        assertThat(assembler(props).assemble("he consoled her")).isEmpty();
    }

    @Test
    void aProjectScopedAccountFactIsSelectedWithoutATrigger() throws IOException {
        writeFact("vd-account", "account", "project:valuedocs", "asserted", 1.0, "2026-10-03", List.of("zzz"), "Account A.");
        writeFact("vd-decision", "decision", "project:valuedocs", "asserted", 1.0, "2026-10-03", List.of("zzz"), "Decision B.");
        writeFact("other-account", "account", "project:cistern", "asserted", 1.0, "2026-10-03", List.of("zzz"), "Account C.");
        AgentProperties props = props(true);
        props.getMemory().setProject("valuedocs");

        Optional<ContextAssembler.Assembled> hit = assembler(props).assemble("anything at all");
        assertThat(hit).isPresent();
        assertThat(hit.get().loadedFacts()).extracting(ContextAssembler.LoadedFact::id).containsExactly("vd-account");
    }

    @Test
    void aSupersededFactIsNeverSelected() throws IOException {
        writeFact("old-account", "account", "project:valuedocs", "superseded", 1.0, "2026-01-01", List.of("console"), "Old account.");
        AgentProperties props = props(true);
        props.getMemory().setProject("valuedocs");

        assertThat(assembler(props).assemble("the console please")).isEmpty();
    }

    @Test
    void maxFactsIsHonouredByConfidenceThenLastConfirmed() throws IOException {
        writeFact("low", "decision", "estate", "inferred", 0.7, "2026-10-03", List.of("alpha"), "Low.");
        writeFact("high-old", "decision", "estate", "asserted", 1.0, "2026-01-01", List.of("alpha"), "High old.");
        writeFact("high-new", "decision", "estate", "asserted", 1.0, "2026-09-01", List.of("alpha"), "High new.");
        AgentProperties props = props(true);
        props.getMemory().setMaxFacts(2);

        Optional<ContextAssembler.Assembled> hit = assembler(props).assemble("alpha");
        assertThat(hit).isPresent();
        assertThat(hit.get().loadedFacts()).extracting(ContextAssembler.LoadedFact::id).containsExactly("high-new", "high-old");
    }

    @Test
    void theProjectsLatestEpisodesAreRenderedWithoutBuiltOrLearned() throws IOException {
        writeEpisode("2026-10-01-one", "2026-10-01", "penstock", "first ask", "open one");
        writeEpisode("2026-10-02-two", "2026-10-02", "penstock", "second ask", "open two");
        writeEpisode("2026-10-03-three", "2026-10-03", "penstock", "third ask", "open three");
        writeEpisode("2026-10-03-other", "2026-10-03", "cistern", "cistern ask", "open cistern");
        AgentProperties props = props(true);
        props.getMemory().setProject("penstock");

        Optional<ContextAssembler.Assembled> hit = assembler(props).assemble("where are we?");
        assertThat(hit).isPresent();
        assertThat(hit.get().loadedEpisodes()).extracting(ContextAssembler.LoadedEpisode::id)
                .containsExactly("2026-10-03-three", "2026-10-02-two");
        assertThat(hit.get().block()).contains("third ask").contains("did the thing — because").contains("open three")
                .doesNotContain("a.java").doesNotContain("a learned line").doesNotContain("cistern ask");
    }

    @Test
    void whenTheBlockIsFullAPatternIsDroppedBeforeAFact() throws IOException {
        // Sized from the header, so the pattern fits on its own and only the fact can push it out
        // (#132: a fixed size went vacuous when the header grew).
        String hugeMd = "x".repeat(ContextAssembler.MAX_BLOCK_CHARS - ContextAssembler.HEADER.length() - 400);
        writePattern("huge", "1", List.of("alpha"), List.of(), "2026-06-01", hugeMd);
        AgentProperties props = props(true);
        Optional<ContextAssembler.Assembled> alone = assembler(props).assemble("alpha");
        assertThat(alone).isPresent();
        assertThat(alone.get().loadedPatterns()).extracting(ContextAssembler.LoadedPattern::id).containsExactly("huge");

        writeFact("f1", "decision", "estate", "asserted", 1.0, "2026-10-03", List.of("alpha"), "y".repeat(600));
        Optional<ContextAssembler.Assembled> hit = assembler(props).assemble("alpha");
        assertThat(hit).isPresent();
        assertThat(hit.get().loadedFacts()).extracting(ContextAssembler.LoadedFact::id).containsExactly("f1");
        assertThat(hit.get().loadedPatterns()).isEmpty();
    }

    // --- #132: the header tells the model how to cite cards and what it may write ------------

    @Test
    void theHeaderCarriesThePlanningMarkersAndTheDraftingRule() {
        // #92's markers, verbatim, so check-plan.sh can read what the model writes
        assertThat(ContextAssembler.HEADER).contains("[card: <id>]").contains("[assumption] — ask:");
        // facts come from consolidation and the owner's review, never from the model
        assertThat(ContextAssembler.HEADER).contains("Never write or edit a fact card")
                .contains("draft_episode").contains("<date>-<slug>");
        // it rides every turn that has memory, inside MAX_BLOCK_CHARS
        assertThat(ContextAssembler.HEADER.length()).isLessThan(700);
    }

    @Test
    void wholeWordHandlesMetacharactersAndMultiWordTriggers() {
        assertThat(ContextAssembler.wholeWord("c++", "we write c++ here")).isTrue();
        assertThat(ContextAssembler.wholeWord(".claude", "edit .claude/settings.json")).isTrue();
        assertThat(ContextAssembler.wholeWord("text/turtle", "put as text/turtle please")).isTrue();
        assertThat(ContextAssembler.wholeWord("google account", "which google account?")).isTrue();
        assertThat(ContextAssembler.wholeWord("console", "he consoled her")).isFalse();
        assertThat(ContextAssembler.wholeWord("409", "status 4095")).isFalse();
        assertThat(ContextAssembler.wholeWord("", "anything")).isFalse();
    }

    @Test
    void maxFactsZeroLoadsNoFactAndEpisodesOnTheSameDateOrderById() throws IOException {
        writeFact("f1", "decision", "estate", "asserted", 1.0, "2026-10-03", List.of("alpha"), "F1.");
        writeEpisode("2026-10-03-bbb", "2026-10-03", "penstock", "bbb ask", "open b");
        writeEpisode("2026-10-03-aaa", "2026-10-03", "penstock", "aaa ask", "open a");
        AgentProperties props = props(true);
        props.getMemory().setMaxFacts(0);
        props.getMemory().setProject("penstock");

        Optional<ContextAssembler.Assembled> hit = assembler(props).assemble("alpha");
        assertThat(hit).isPresent();
        assertThat(hit.get().loadedFacts()).isEmpty();
        assertThat(hit.get().block()).doesNotContain("### facts");
        assertThat(hit.get().loadedEpisodes()).extracting(ContextAssembler.LoadedEpisode::id)
                .containsExactly("2026-10-03-bbb", "2026-10-03-aaa");
    }

    @Test
    void factsAloneBeyondTheCapAreDroppedWholeAndTheHeadingNeverStandsEmpty() throws IOException {
        writeFact("huge", "decision", "estate", "asserted", 1.0, "2026-10-03", List.of("alpha"), "y".repeat(ContextAssembler.MAX_BLOCK_CHARS));
        AgentProperties props = props(true);

        assertThat(assembler(props).assemble("alpha")).isEmpty();

        writeFact("small", "decision", "estate", "asserted", 0.9, "2026-10-03", List.of("alpha"), "fits.");
        Optional<ContextAssembler.Assembled> hit = assembler(props).assemble("alpha");
        assertThat(hit).isPresent();
        assertThat(hit.get().loadedFacts()).extracting(ContextAssembler.LoadedFact::id).containsExactly("small");
        assertThat(hit.get().block()).contains("### facts");
    }

    @Test
    void theProjectIsDerivedFromTheWorkspaceDirectoryThroughTheAliases() {
        AgentProperties props = props(true);
        props.getMemory().getProjectAliases().put("enrichmeai.github.io", "site");
        assertThat(ContextAssembler.resolveProject(Path.of("/w/enrichmeai.github.io"), props)).isEqualTo("site");
        assertThat(ContextAssembler.resolveProject(Path.of("/w/penstock"), props)).isEqualTo("penstock");
        props.getMemory().setProject("valuedocs");
        assertThat(ContextAssembler.resolveProject(Path.of("/w/penstock"), props)).isEqualTo("valuedocs");
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
