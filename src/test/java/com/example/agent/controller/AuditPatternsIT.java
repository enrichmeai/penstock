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
 * Issue #81 ("staleness and digest", part b): {@code GET /api/audit/patterns} aggregates
 * {@code pattern.loaded} audit events into per-pattern usage, and lists every pattern in the
 * catalog that has never been loaded, last.
 *
 * <p>{@code agent.memory.root} points at a throwaway temp directory with a single fixture
 * pattern ({@code never-used-fixture}), not this repo's own {@code patterns/} — so the "never
 * loaded" assertion below stays correct regardless of how many real patterns this repo
 * accumulates (unlike pointing at {@code ${user.dir}}, which breaks the moment a second real
 * pattern is added).
 *
 * <p>RED on {@code main}: {@code AuditEventRepository} has no {@code findByEventTypeOrderByTimestampDesc}
 * and {@code AuditController} has no {@code /api/audit/patterns} mapping, so this does not compile.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "agent.workspace=${java.io.tmpdir}/agent-test-workspace-audit-patterns",
        "agent.llm.provider=stub",
        "agent.auth.enabled=false",
        "agent.rate-limit.enabled=false",
        "agent.memory.enabled=true",
        // JPA store on H2: activates the audit repository without a real SQLite file.
        "agent.storage.type=sqlite",
        "spring.datasource.url=jdbc:h2:mem:audit-patterns-it;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class AuditPatternsIT {

    @DynamicPropertySource
    static void memoryRoot(DynamicPropertyRegistry registry) {
        try {
            Path root = Files.createTempDirectory("audit-patterns-it-memory-root");
            Path patternDir = root.resolve("patterns").resolve("never-used-fixture");
            Files.createDirectories(patternDir);
            Files.writeString(patternDir.resolve("manifest.yaml"),
                    "id: never-used-fixture\nversion: 1\ntriggers: []\nsignatures: []\n"
                            + "verified-against:\n  tag: v1\n  date: 2026-01-01\n");
            registry.add("agent.memory.root", root::toString);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired AuditEventRepository auditRepo;

    @Test
    void aggregatesLoadsAndListsNeverLoadedPatternsLast() throws Exception {
        auditRepo.save(new AuditEventEntity(Instant.now().minusSeconds(60), "alice", "s1",
                "pattern.loaded", "{\"patternId\":\"loaded-pattern\",\"version\":\"1\"}"));
        auditRepo.save(new AuditEventEntity(Instant.now(), "alice", "s2",
                "pattern.loaded", "{\"patternId\":\"loaded-pattern\",\"version\":\"2\"}"));

        mvc.perform(get("/api/audit/patterns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].patternId").value("loaded-pattern"))
                .andExpect(jsonPath("$[0].loads").value(2))
                .andExpect(jsonPath("$[0].versions", org.hamcrest.Matchers.containsInAnyOrder("1", "2")))
                .andExpect(jsonPath("$[0].lastLoadedAt").exists())
                .andExpect(jsonPath("$[1].patternId").value("never-used-fixture"))
                .andExpect(jsonPath("$[1].loads").value(0))
                .andExpect(jsonPath("$[1].lastLoadedAt").doesNotExist());
    }
}
