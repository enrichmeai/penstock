package com.example.agent.controller;

import com.example.agent.config.AgentProperties;
import com.example.agent.memory.PatternCatalog;
import com.example.agent.service.persistence.AuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for the two edge cases of {@code GET /api/audit/patterns} that don't need a real
 * database (issue #81): the memory feature flag off, and memory storage mode (no
 * {@code AuditEventRepository} bean). The aggregation behaviour itself — two {@code
 * pattern.loaded} events plus a never-loaded pattern — is covered by {@link AuditPatternsIT}
 * against a real H2-backed repository.
 */
class AuditControllerTest {

    @Test
    void memoryFlagOffReturnsEmptyListWithoutTouchingTheRepository() throws Exception {
        AgentProperties props = new AgentProperties();
        props.getMemory().setEnabled(false);
        PatternCatalog catalog = new PatternCatalog(props);
        AuditEventRepository repo = mock(AuditEventRepository.class);
        AuditController controller = new AuditController(null, new ObjectMapper(), props, catalog);
        controller.setAuditEventRepository(repo);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/audit/patterns"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verifyNoInteractions(repo);
    }

    @Test
    void memoryStorageModeReturns503WhenNoAuditRepositoryIsAvailable() throws Exception {
        AgentProperties props = new AgentProperties();
        props.getMemory().setEnabled(true);
        PatternCatalog catalog = new PatternCatalog(props);
        AuditController controller = new AuditController(null, new ObjectMapper(), props, catalog);
        // No repository wired — the shape of memory storage mode, which has no audit table.
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/audit/patterns"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(containsString("memory storage mode")));
    }
}
