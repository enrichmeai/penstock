package com.example.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --acp} combined with a non-memory storage backend must fail fast rather than run:
 * {@code AcpSessionBridge} creates sessions directly, bypassing {@code SessionStore}, so
 * {@code JpaSessionStore.appendMessage} would otherwise write permanently orphaned
 * {@code agent_messages} rows for every ACP turn (see {@code AcpSessionBridge}'s javadoc).
 * These checks run before {@code SpringApplication.run}, so no Spring context is needed —
 * {@code AgentApplication.main} must throw before attempting to start one.
 */
class AgentApplicationAcpGuardTest {

    @Test
    void acpWithSqliteStorageFailsFastBeforeStartingSpring() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> AgentApplication.main(new String[] {"--acp", "--agent.storage.type=sqlite"}));
        assertTrue(ex.getMessage().contains("memory"), ex.getMessage());
    }

    @Test
    void acpWithPostgresStorageFailsFastBeforeStartingSpring() {
        assertThrows(IllegalStateException.class,
                () -> AgentApplication.main(new String[] {"--acp", "--agent.storage.type=postgres"}));
    }
}
