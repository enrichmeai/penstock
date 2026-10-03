package com.example.agent.memory;

import com.example.agent.config.AgentProperties;
import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.TokenUsage;
import com.example.agent.service.persistence.AuditEventEntity;
import com.example.agent.service.persistence.AuditEventRepository;
import com.example.agent.tools.ToolSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Companion to {@link MemoryRetrievalIT}: with {@code agent.memory.enabled} left at its default
 * ({@code false}), the same triggering input must leave the system prompt byte-identical to
 * {@code agent.llm.system-prompt} and must never write a {@code pattern.loaded} audit event —
 * the flag-off path does nothing at all, not even read the patterns/ directory.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "agent.workspace=${java.io.tmpdir}/agent-test-workspace-memory-off",
        "agent.llm.provider=stub",
        "agent.auth.enabled=false",
        "agent.rate-limit.enabled=false",
        // agent.memory.enabled intentionally not set: proves the real, shipped default.
        "agent.storage.type=sqlite",
        "spring.datasource.url=jdbc:h2:mem:memory-retrieval-off;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class MemoryRetrievalDisabledIT {

    private static final Pattern SESSION_ID = Pattern.compile("\"sessionId\":\"([0-9a-f-]+)\"");

    @Autowired MockMvc mvc;
    @Autowired AuditEventRepository auditRepo;
    @Autowired AgentProperties props;

    static final AtomicReference<String> LAST_SYSTEM_PROMPT = new AtomicReference<>();

    @TestConfiguration
    static class StubCfg {
        @Bean
        @Primary
        LlmProvider capturingStub() {
            return new LlmProvider() {
                @Override public String name() { return "stub"; }
                @Override public CompletionResult complete(String sys, List<ChatMessage> hist, List<ToolSpec> tools, String sessionId) {
                    LAST_SYSTEM_PROMPT.set(sys);
                    return new CompletionResult(ChatMessage.assistantText("ok"), new TokenUsage(1, 1));
                }
            };
        }
    }

    @Test
    void sameTriggeringInputLeavesTheSystemPromptUnchangedAndLogsNothing() throws Exception {
        assertThat(props.getMemory().isEnabled()).isFalse();

        MvcResult result = mvc.perform(post("/api/chat")
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"How do I wire up a stdio json-rpc agent for the editor?\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String sessionId = extractSessionId(result.getResponse().getContentAsString());

        // Compare against the exact provider call, not a hardcoded copy of the prompt text.
        assertThat(LAST_SYSTEM_PROMPT.get()).isEqualTo(props.getLlm().getSystemPrompt());

        List<AuditEventEntity> events = awaitLlmCallEvent(sessionId);
        assertThat(events).extracting(AuditEventEntity::getEventType)
                .doesNotContain("pattern.loaded", "fact.loaded", "episode.loaded");
    }

    private static String extractSessionId(String body) {
        Matcher m = SESSION_ID.matcher(body);
        assertThat(m.find()).as("response contains a sessionId: %s", body).isTrue();
        return m.group(1);
    }

    /** Waits for the llm_call event that always follows a turn, then returns everything recorded. */
    private List<AuditEventEntity> awaitLlmCallEvent(String sessionId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            List<AuditEventEntity> events = auditRepo.findBySessionIdOrderByTimestampAsc(sessionId);
            if (!events.isEmpty()) {
                return events;
            }
            Thread.sleep(50);
        }
        return List.of();
    }
}
