package com.example.agent.controller;

import com.example.agent.config.AgentProperties;
import com.example.agent.memory.PatternCatalog;
import com.example.agent.service.persistence.AuditEventEntity;
import com.example.agent.service.persistence.AuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the two edge cases of {@code GET /api/audit/patterns} that don't need a real
 * database (issue #81): the memory feature flag off, and memory storage mode (no
 * {@code AuditEventRepository} bean, which {@link AuditController} reports via {@link
 * AuditStoreUnavailableException} — {@code ErrorAdviceTest} covers that exception's 503
 * mapping). The aggregation behaviour itself — two {@code pattern.loaded} events plus a
 * never-loaded pattern — is covered by {@link AuditPatternsIT} against a real H2-backed
 * repository.
 */
class AuditControllerTest {

    @Test
    void memoryFlagOffReturnsEmptyListWithoutTouchingTheRepository() {
        AgentProperties props = new AgentProperties();
        props.getMemory().setEnabled(false);
        PatternCatalog catalog = new PatternCatalog(props);
        AuditEventRepository repo = mock(AuditEventRepository.class);
        AuditController controller = new AuditController(null, new ObjectMapper(), props, catalog);
        controller.setAuditEventRepository(repo);

        List<Map<String, Object>> result = controller.getPatternAudit();

        assertThat(result).isEmpty();
        verifyNoInteractions(repo);
    }

    @Test
    void memoryStorageModeThrowsAuditStoreUnavailableWhenNoAuditRepositoryIsAvailable() {
        AgentProperties props = new AgentProperties();
        props.getMemory().setEnabled(true);
        PatternCatalog catalog = new PatternCatalog(props);
        AuditController controller = new AuditController(null, new ObjectMapper(), props, catalog);
        // No repository wired — the shape of memory storage mode, which has no audit table.

        assertThatThrownBy(controller::getPatternAudit)
                .isInstanceOf(AuditStoreUnavailableException.class)
                .hasMessageContaining("memory storage mode");
    }

    @Test
    void skipsAPatternLoadedEventWithNoPatternIdInsteadOfMergingItUnderTheLiteralStringNull() {
        AgentProperties props = new AgentProperties();
        props.getMemory().setEnabled(true);
        PatternCatalog catalog = new PatternCatalog(props);
        AuditEventRepository repo = mock(AuditEventRepository.class);
        AuditEventEntity missingPatternId = new AuditEventEntity(
                Instant.now(), "alice", "s1", "pattern.loaded", "{\"version\":\"1\"}");
        AuditEventEntity valid = new AuditEventEntity(
                Instant.now(), "alice", "s2", "pattern.loaded", "{\"patternId\":\"real-pattern\",\"version\":\"2\"}");
        when(repo.findByEventTypeInOrderByTimestampDesc(anyCollection())).thenReturn(List.of(valid, missingPatternId));
        AuditController controller = new AuditController(null, new ObjectMapper(), props, catalog);
        controller.setAuditEventRepository(repo);

        List<Map<String, Object>> result = controller.getPatternAudit();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("patternId")).isEqualTo("real-pattern");
    }

    @TempDir Path root;

    /** A root with one pattern, one fact and one episode, none of them loaded yet (#90). */
    private PatternCatalog catalogWithOneCardOfEachKind(AgentProperties props) throws IOException {
        Path pattern = root.resolve("patterns/a-pattern");
        Files.createDirectories(pattern);
        Files.writeString(pattern.resolve("manifest.yaml"), "id: a-pattern\nversion: 3\ntriggers: []\nsignatures: []\n"
                + "verified-against:\n  tag: v1\n  date: 2026-01-01\n");
        Files.writeString(pattern.resolve("PATTERN.md"), "# a pattern\n");
        Path facts = root.resolve("facts");
        Files.createDirectories(facts);
        Files.writeString(facts.resolve("never-recalled.yaml"), "id: never-recalled\nstatement: N holds.\nkind: convention\n"
                + "subject: s/n\nscope: estate\nstatus: asserted\nconfidence: 1.0\nlast_confirmed: 2026-10-04\n");
        Path episodes = root.resolve("episodes");
        Files.createDirectories(episodes);
        Files.writeString(episodes.resolve("2026-10-01-never-read.yaml"),
                "id: 2026-10-01-never-read\ndate: 2026-10-01\nproject: penstock\nasked: A\n");
        props.getMemory().setRoot(root.toString());
        return PatternCatalog.loadedNow(props);
    }

    @Test
    void recallAggregatesAllThreeKindsAndListsNeverLoadedCardsLast() throws IOException {
        AgentProperties props = new AgentProperties();
        props.getMemory().setEnabled(true);
        PatternCatalog catalog = catalogWithOneCardOfEachKind(props);
        Instant now = Instant.now();
        AuditEventRepository repo = mock(AuditEventRepository.class);
        when(repo.findByEventTypeInOrderByTimestampDesc(anyCollection())).thenReturn(List.of(
                new AuditEventEntity(now, "alice", "s3", "fact.loaded", "{\"factId\":\"loaded-fact\",\"status\":\"asserted\",\"confidence\":1.0}"),
                new AuditEventEntity(now.minusSeconds(10), "alice", "s2", "episode.loaded", "{\"episodeId\":\"2026-10-02-loaded\",\"date\":\"2026-10-02\",\"reason\":\"latest\"}"),
                new AuditEventEntity(now.minusSeconds(20), "alice", "s1", "pattern.loaded", "{\"patternId\":\"a-pattern\",\"version\":\"3\"}"),
                new AuditEventEntity(now.minusSeconds(30), "alice", "s0", "fact.loaded", "{\"factId\":\"loaded-fact\",\"status\":\"asserted\",\"confidence\":1.0}"),
                new AuditEventEntity(now.minusSeconds(40), "alice", "s0", "fact.loaded", "{\"status\":\"asserted\"}"),
                new AuditEventEntity(now.minusSeconds(50), "alice", "s0", "fact.loaded", "{\"patternId\":\"a-pattern\"}"),
                new AuditEventEntity(now, "alice", "s4", "episode.loaded", "{\"episodeId\":\"2026-10-03-same-instant\"}")));
        AuditController controller = new AuditController(null, new ObjectMapper(), props, catalog);
        controller.setAuditEventRepository(repo);

        List<Map<String, Object>> result = controller.getRecallAudit();

        // A fact.loaded event naming a patternId, or no factId at all, is skipped, not counted.
        // Loaded at the same instant, episode sorts before fact (kind, then id).
        assertThat(result).extracting(r -> r.get("kind") + ":" + r.get("id")).containsExactly(
                "episode:2026-10-03-same-instant", "fact:loaded-fact", "episode:2026-10-02-loaded", "pattern:a-pattern",
                "fact:never-recalled", "episode:2026-10-01-never-read");
        assertThat(result.get(1).get("loads")).isEqualTo(2);
        assertThat(result.get(1).get("lastLoadedAt")).isEqualTo(now);
        assertThat(result.get(3).get("loads")).isEqualTo(1);
        assertThat(result.get(3).get("versions")).isEqualTo(List.of("3"));
        assertThat(result.get(1).get("versions")).isEqualTo(List.of());
        assertThat(result.get(4).get("loads")).isEqualTo(0);
        assertThat(result.get(4).get("lastLoadedAt")).isNull();
    }

    @Test
    void recallWithTheMemoryFlagOffIsEmptyAndInMemoryStorageModeIsUnavailable() {
        AgentProperties off = new AgentProperties();
        off.getMemory().setEnabled(false);
        AuditEventRepository repo = mock(AuditEventRepository.class);
        AuditController flagOff = new AuditController(null, new ObjectMapper(), off, new PatternCatalog(off));
        flagOff.setAuditEventRepository(repo);
        assertThat(flagOff.getRecallAudit()).isEmpty();
        assertThat(flagOff.getPatternAudit()).isEmpty();
        verifyNoInteractions(repo);

        AgentProperties on = new AgentProperties();
        on.getMemory().setEnabled(true);
        AuditController noTable = new AuditController(null, new ObjectMapper(), on, new PatternCatalog(on));
        assertThatThrownBy(noTable::getRecallAudit)
                .isInstanceOf(AuditStoreUnavailableException.class)
                .hasMessageContaining("memory storage mode");
        assertThatThrownBy(noTable::getPatternAudit)
                .isInstanceOf(AuditStoreUnavailableException.class);
    }
}
