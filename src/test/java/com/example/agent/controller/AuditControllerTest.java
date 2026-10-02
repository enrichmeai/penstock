package com.example.agent.controller;

import com.example.agent.config.AgentProperties;
import com.example.agent.memory.PatternCatalog;
import com.example.agent.service.persistence.AuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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
}
