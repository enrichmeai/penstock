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
 * Issue #73 ("retrieve before reason"): with {@code agent.memory.enabled=true}, a turn whose
 * input hits two of the seed pattern's triggers ({@code stdio}, {@code json-rpc}) must load
 * {@code patterns/stdio-json-rpc-agent} into that turn's system prompt and audit a
 * {@code pattern.loaded} event naming its id and version.
 *
 * <p>{@code agent.memory.root} is left at its default ({@code ${user.dir}}), which during
 * {@code ./gradlew test} is the project root — the same {@code patterns/} the real app would
 * read, so this proves the seed card actually loads, not a fixture standing in for it.
 *
 * <p>RED on {@code main}: {@code agent.memory.enabled} doesn't exist, {@code ContextAssembler}
 * doesn't exist, and {@code AuditLogger} has no {@code pattern.loaded} event — the system
 * prompt the stub sees never contains "stdio-json-rpc-agent" and no such audit event is ever
 * written, so both assertions below fail.
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
        // JPA store on H2: activates the audit repository without a real SQLite file.
        "agent.storage.type=sqlite",
        "spring.datasource.url=jdbc:h2:mem:memory-retrieval-on;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class MemoryRetrievalIT {

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

    @Test
    void triggeringTurnLoadsTheSeedPatternAndAuditsIt() throws Exception {
        MvcResult result = mvc.perform(post("/api/chat")
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"How do I wire up a stdio json-rpc agent for the editor?\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String sessionId = extractSessionId(result.getResponse().getContentAsString());

        assertThat(LAST_SYSTEM_PROMPT.get())
                .as("the system prompt the provider received")
                .contains("stdio-json-rpc-agent")
                .contains("Established facts, recent episodes and patterns");

        List<AuditEventEntity> events = awaitPatternLoadedEvents(sessionId);
        assertThat(events).isNotEmpty();
        AuditEventEntity event = events.get(0);
        assertThat(event.getDetailJson()).contains("\"patternId\":\"stdio-json-rpc-agent\"");
        assertThat(event.getDetailJson()).contains("\"version\":\"1\"");
        // a root that declares no projects records no section decisions (#112)
        assertThat(auditRepo.findBySessionIdOrderByTimestampAsc(sessionId))
                .noneMatch(e -> e.getEventType().startsWith("section."));
    }

    private static String extractSessionId(String body) {
        Matcher m = SESSION_ID.matcher(body);
        assertThat(m.find()).as("response contains a sessionId: %s", body).isTrue();
        return m.group(1);
    }

    private List<AuditEventEntity> awaitPatternLoadedEvents(String sessionId) throws InterruptedException {
        return awaitEvents(sessionId, "pattern.loaded");
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
