package com.example.agent.memory;

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
 * #88, Metric A's local half, against a fixture memory root (#91 moved the owner's real account
 * fact to the private memory root, so this no longer reads it): the account question is answered
 * from the context block, and the fact and the project's episode are audited as loaded.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "agent.workspace=${java.io.tmpdir}/agent-test-workspace-memory-on",
        "agent.llm.provider=stub",
        "agent.auth.enabled=false",
        "agent.rate-limit.enabled=false",
        "agent.memory.enabled=true",
        // the workspace is a temp dir, so name the project explicitly (#88)
        "agent.memory.project=penstock",
        // a fixture root, never the owner's real cards (those live in the private memory root, #91)
        "agent.memory.root=${user.dir}/src/test/resources/memory-fixtures/account-recall",
        // JPA store on H2: activates the audit repository without a real SQLite file.
        "agent.storage.type=sqlite",
        "spring.datasource.url=jdbc:h2:mem:memory-account-recall;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class MemoryAccountRecallIT {

    private static final Pattern SESSION_ID = Pattern.compile("\"sessionId\":\"([0-9a-f-]+)\"");

    @Autowired MockMvc mvc;
    @Autowired AuditEventRepository auditRepo;

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

    /**
     * #88, Metric A's local half: an account question is answered from the block, by trigger — the fact's statement is in the first prompt,
     * {@code fact.loaded} and {@code episode.loaded} are audited, no tool call needed.
     */
    @Test
    void theAccountQuestionIsAnsweredFromTheFactAndAudited() throws Exception {
        MvcResult result = mvc.perform(post("/api/chat")
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"which Google account for the fixture-proj console?\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String sessionId = extractSessionId(result.getResponse().getContentAsString());

        assertThat(LAST_SYSTEM_PROMPT.get())
                .contains("fact:console-account-fixture")
                .contains("owner@fixture.example")
                .contains("### episode:");

        List<AuditEventEntity> events = awaitEvents(sessionId, "fact.loaded");
        assertThat(events).extracting(AuditEventEntity::getDetailJson)
                .anyMatch(d -> d.contains("\"factId\":\"console-account-fixture\"")
                        && d.contains("\"status\":\"asserted\"") && d.contains("\"confidence\":1.0"));
        List<AuditEventEntity> episodes = awaitEvents(sessionId, "episode.loaded");
        assertThat(episodes).isNotEmpty();
        assertThat(episodes.get(0).getDetailJson()).contains("\"episodeId\":\"2026-01-30-fixture-console-session\"").contains("\"date\":\"2026-01-30\"");
    }

    private static String extractSessionId(String body) {
        Matcher m = SESSION_ID.matcher(body);
        assertThat(m.find()).as("response contains a sessionId: %s", body).isTrue();
        return m.group(1);
    }

    private List<AuditEventEntity> awaitEvents(String sessionId, String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            List<AuditEventEntity> events = auditRepo.findBySessionIdOrderByTimestampAsc(sessionId).stream()
                    .filter(e -> type.equals(e.getEventType()))
                    .toList();
            if (!events.isEmpty()) {
                return events;
            }
            Thread.sleep(50);
        }
        return List.of();
    }
}
