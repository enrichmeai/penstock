package com.example.agent.mcp;

import com.example.agent.config.AgentProperties;
import com.example.agent.memory.EpisodeCard;
import com.example.agent.memory.PatternCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #116: where a draft goes, what visibility it takes, and what it refuses. */
class EpisodeDraftsTest {

    @TempDir
    Path root;

    private EpisodeDrafts drafts() {
        AgentProperties props = new AgentProperties();
        props.getMemory().setRoot(root.toString());
        return new EpisodeDrafts(props);
    }

    private static Map<String, Object> args(String project, String slug) {
        return Map.of("project", project, "slug", slug, "asked", "Do the thing",
                "decided", List.of(Map.of("what", "one file", "why", "simpler")));
    }

    private String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    @Test
    void aFlatRootTakesItsVisibilityOnlyForItsOwnProject() throws IOException {
        Files.writeString(root.resolve("memory.yaml"), "format: 1\nproject: penstock\nvisibility: public\n");
        assertThat(drafts().draft(args("penstock", "own")).written()).isTrue();
        assertThat(Files.readString(root.resolve("drafts/" + today() + "-own.yaml"))).contains("visibility: public");
        assertThat(drafts().draft(args("cistern", "other")).written()).isTrue();
        assertThat(Files.readString(root.resolve("drafts/" + today() + "-other.yaml"))).contains("visibility: private");
    }

    @Test
    void aSectionedRootWritesEstateAndProjectDraftsInTheirSections() throws IOException {
        Files.createDirectories(root.resolve("projects"));
        Files.writeString(root.resolve("memory.yaml"), "format: 1\nvisibility: private\nprojects:\n  oss-lib: { audience: public }\n");
        assertThat(drafts().draft(args("estate", "owner-wide")).written()).isTrue();
        assertThat(root.resolve("estate/drafts/" + today() + "-owner-wide.yaml")).exists();
        assertThat(drafts().draft(args("oss-lib", "lib")).written()).isTrue();
        assertThat(root.resolve("projects/oss-lib/drafts/" + today() + "-lib.yaml")).exists();
    }

    /**
     * #162: the tool says nothing reads a draft until the owner keeps it, so recall must not load
     * one. A draft sits beside the section's episodes, not among them; moving it in is the review.
     */
    @Test
    void aDraftIsNotRecalledUntilTheOwnerMovesItIntoEpisodes() throws IOException {
        Files.createDirectories(root.resolve("projects"));
        Files.writeString(root.resolve("memory.yaml"), "format: 1\nvisibility: private\nprojects:\n  oss-lib: { audience: public }\n");
        EpisodeDrafts.Result r = drafts().draft(args("oss-lib", "pending"));
        assertThat(r.written()).isTrue();
        String id = today() + "-pending";

        AgentProperties recall = new AgentProperties();
        recall.getMemory().setEnabled(true);
        recall.getMemory().setRoot(root.toString());
        recall.getMemory().setProject("oss-lib");
        assertThat(PatternCatalog.loadedNow(recall).episodes()).extracting(EpisodeCard::id).doesNotContain(id);

        Path draft = root.resolve("projects/oss-lib/drafts/" + id + ".yaml");
        assertThat(draft).exists();
        assertThat(r.message()).contains("projects/oss-lib/drafts/" + id + ".yaml").contains("projects/oss-lib/episodes/");
        Files.createDirectories(root.resolve("projects/oss-lib/episodes"));
        Files.move(draft, root.resolve("projects/oss-lib/episodes/" + id + ".yaml"));
        assertThat(PatternCatalog.loadedNow(recall).episodes()).extracting(EpisodeCard::id).contains(id);
    }

    @Test
    void aDraftWhoseIdIsAlreadyAnEpisodeIsRefused() throws IOException {
        Files.writeString(root.resolve("memory.yaml"), "format: 1\nproject: penstock\nvisibility: public\n");
        Files.createDirectories(root.resolve("episodes"));
        Path kept = root.resolve("episodes/" + today() + "-taken.yaml");
        Files.writeString(kept, "id: " + today() + "-taken\n");
        EpisodeDrafts.Result r = drafts().draft(args("penstock", "taken"));
        assertThat(r.written()).isFalse();
        assertThat(r.message()).contains("already exists");
        assertThat(root.resolve("drafts")).doesNotExist();
    }

    @Test
    void aProjectTheRootDoesNotDeclareIsRefused() throws IOException {
        Files.createDirectories(root.resolve("projects"));
        Files.writeString(root.resolve("memory.yaml"), "format: 1\nvisibility: private\nprojects:\n  oss-lib: { audience: public }\n");
        EpisodeDrafts.Result r = drafts().draft(args("valuedocs", "x"));
        assertThat(r.written()).isFalse();
        assertThat(r.message()).contains("not declared");
        assertThat(root.resolve("projects/valuedocs")).doesNotExist();
    }

    @Test
    void pathsAndMalformedEntriesAreRefusedBeforeAnythingIsWritten() {
        assertThat(drafts().draft(args("../x", "a")).written()).isFalse();
        assertThat(drafts().draft(args("penstock", "../a")).written()).isFalse();
        assertThat(drafts().draft(Map.of("project", "penstock", "slug", "a", "asked", " ")).written()).isFalse();
        assertThat(drafts().draft(Map.of("project", "penstock", "slug", "a", "asked", "x",
                "decided", List.of(Map.of("what", "no reason")))).written()).isFalse();
        assertThat(root.resolve("drafts")).doesNotExist();
    }

    @Test
    void aLongEntryCannotHideACredentialByBeingFoldedAcrossLines() throws IOException {
        String padded = "q".repeat(96) + " the password: hunter2abcdef1234 ok";
        Map<String, Object> a = Map.of("project", "penstock", "slug", "folded", "asked", "x",
                "learned", List.of(padded));
        EpisodeDrafts.Result r = drafts().draft(a);
        assertThat(r.written()).isFalse();
        assertThat(r.message()).doesNotContain("hunter2");
        assertThat(root.resolve("drafts")).doesNotExist();
        // and a long clean entry is written on one line, so the file holds what was scanned
        assertThat(drafts().draft(Map.of("project", "penstock", "slug", "long", "asked", "x",
                "learned", List.of("w ".repeat(120).trim()))).written()).isTrue();
        assertThat(Files.readAllLines(root.resolve("drafts/" + today() + "-long.yaml")))
                .anySatisfy(l -> assertThat(l).contains("w ".repeat(120).trim()));
    }

    @Test
    void aSectionedRootsDraftsArePrivateAsTheScriptWritesThem() throws IOException {
        Files.createDirectories(root.resolve("projects"));
        Files.writeString(root.resolve("memory.yaml"), "format: 1\nvisibility: shareable\nprojects:\n  oss-lib: { audience: public }\n");
        assertThat(drafts().draft(args("oss-lib", "vis")).written()).isTrue();
        assertThat(Files.readString(root.resolve("projects/oss-lib/drafts/" + today() + "-vis.yaml"))).contains("visibility: private");
    }

    @Test
    void theCredentialShapesMatchCredentialGrep() {
        // the same shapes scripts/lib/credential-grep.sh refuses, and the ordinary lines it lets through
        for (String bad : List.of("learned: - key sk-abcdefghijklmnop", "token: ghp_abcdefghijklmnop",
                "id AKIAABCDEFGHIJKLMN", "-----BEGIN RSA PRIVATE KEY-----", "apiSecret = \"s3cr3tvalue\"",
                "- the box password: hunter2abcdef1234")) {
            assertThat(CredentialShape.firstMatchingLine("ok\n" + bad)).as(bad).isEqualTo(2);
        }
        for (String fine : List.of("the build reads ZEBRA_HOME, by name only",
                "the token lives in GITHUB_TOKEN", "password: <from the vault>", "secret: ${DB_SECRET}",
                "rotate the token every month")) {
            assertThat(CredentialShape.firstMatchingLine(fine)).as(fine).isZero();
        }
    }
}
