package com.example.agent.controller;

import com.example.agent.service.persistence.AuditEventEntity;
import com.example.agent.service.persistence.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #90 (part 2): {@code GET /api/audit/recall} aggregates {@code pattern.loaded},
 * {@code fact.loaded} and {@code episode.loaded} events per card against a real H2-backed
 * repository, lists every card in the catalogue that was never loaded, last, and
 * {@code /api/audit/patterns} keeps its shape as the pattern rows of the same aggregate.
 *
 * <p>{@code agent.memory.root} is a throwaway directory with one pattern, one fact and one
 * episode, so the "never loaded" rows do not depend on this repository's own cards (#83).
 *
 * <p>RED on {@code main}: there is no {@code /api/audit/recall} mapping (404).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "agent.workspace=${java.io.tmpdir}/agent-test-workspace-audit-recall",
        "agent.llm.provider=stub",
        "agent.auth.enabled=false",
        "agent.rate-limit.enabled=false",
        "agent.memory.enabled=true",
        "agent.storage.type=sqlite",
        "spring.datasource.url=jdbc:h2:mem:audit-recall-it;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class AuditRecallIT {

    @DynamicPropertySource
    static void memoryRoot(DynamicPropertyRegistry registry) {
        try {
            Path root = Files.createTempDirectory("audit-recall-it-memory-root");
            Path pattern = root.resolve("patterns").resolve("never-used-pattern");
            Files.createDirectories(pattern);
            Files.writeString(pattern.resolve("manifest.yaml"),
                    "id: never-used-pattern\nversion: 1\ntriggers: []\nsignatures: []\n"
                            + "verified-against:\n  tag: v1\n  date: 2026-01-01\n");
            Path facts = root.resolve("facts");
            Files.createDirectories(facts);
            Files.writeString(facts.resolve("never-recalled.yaml"), "id: never-recalled\nstatement: N holds.\n"
                    + "kind: convention\nsubject: s/n\nscope: estate\nstatus: asserted\nconfidence: 1.0\n"
                    + "last_confirmed: 2026-10-04\n");
            Path episodes = root.resolve("episodes");
            Files.createDirectories(episodes);
            Files.writeString(episodes.resolve("2026-10-01-never-read.yaml"),
                    "id: 2026-10-01-never-read\ndate: 2026-10-01\nproject: penstock\nasked: A\n");
            registry.add("agent.memory.root", root::toString);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired AuditEventRepository auditRepo;

    @Test
    void aggregatesTheThreeKindsAndListsNeverLoadedCardsLast() throws Exception {
        Instant now = Instant.now();
        auditRepo.save(new AuditEventEntity(now.minusSeconds(30), "alice", "s1",
                "pattern.loaded", "{\"patternId\":\"loaded-pattern\",\"version\":\"1\"}"));
        auditRepo.save(new AuditEventEntity(now.minusSeconds(20), "alice", "s2",
                "episode.loaded", "{\"episodeId\":\"2026-10-02-loaded\",\"date\":\"2026-10-02\",\"reason\":\"latest\"}"));
        auditRepo.save(new AuditEventEntity(now.minusSeconds(10), "alice", "s3",
                "fact.loaded", "{\"factId\":\"loaded-fact\",\"status\":\"asserted\",\"confidence\":1.0}"));
        auditRepo.save(new AuditEventEntity(now, "alice", "s4",
                "fact.loaded", "{\"factId\":\"loaded-fact\",\"status\":\"asserted\",\"confidence\":1.0}"));
        auditRepo.save(new AuditEventEntity(now, "alice", "s4", "section.loaded", "{\"section\":\"estate\"}"));

        mvc.perform(get("/api/audit/recall"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].kind").value("fact"))
                .andExpect(jsonPath("$[0].id").value("loaded-fact"))
                .andExpect(jsonPath("$[0].loads").value(2))
                .andExpect(jsonPath("$[0].versions").isEmpty())
                .andExpect(jsonPath("$[1].kind").value("episode"))
                .andExpect(jsonPath("$[2].kind").value("pattern"))
                .andExpect(jsonPath("$[2].versions[0]").value("1"))
                .andExpect(jsonPath("$[3].id").value("never-used-pattern"))
                .andExpect(jsonPath("$[4].id").value("never-recalled"))
                .andExpect(jsonPath("$[4].kind").value("fact"))
                .andExpect(jsonPath("$[4].loads").value(0))
                .andExpect(jsonPath("$[4].lastLoadedAt").doesNotExist())
                .andExpect(jsonPath("$[5].id").value("2026-10-01-never-read"))
                .andExpect(jsonPath("$[5].kind").value("episode"));

        mvc.perform(get("/api/audit/patterns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].patternId").value("loaded-pattern"))
                .andExpect(jsonPath("$[0].kind").doesNotExist())
                .andExpect(jsonPath("$[1].patternId").value("never-used-pattern"));
    }
}
