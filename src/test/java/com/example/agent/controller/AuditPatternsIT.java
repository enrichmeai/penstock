package com.example.agent.controller;

import com.example.agent.service.persistence.AuditEventEntity;
import com.example.agent.service.persistence.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #81 ("staleness and digest", part b): {@code GET /api/audit/patterns} aggregates
 * {@code pattern.loaded} audit events into per-pattern usage, and lists every pattern in the
 * catalog that has never been loaded, last.
 *
 * <p>{@code agent.memory.root} is left at its default ({@code ${user.dir}}), the project root,
 * so {@code patterns/stdio-json-rpc-agent} is in the catalog but never loaded by this test —
 * it is the "never-used" pattern the endpoint must still list, with zero loads.
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
                .andExpect(jsonPath("$[1].patternId").value("stdio-json-rpc-agent"))
                .andExpect(jsonPath("$[1].loads").value(0))
                .andExpect(jsonPath("$[1].lastLoadedAt").doesNotExist());
    }
}
