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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #112 end to end: a turn in a public project whose memory.yaml {@code uses} names a private one
 * reaches the model with its own card and the owner's shareable card only; the private personal
 * card and the private project's card stay out, and the audit trail says which section was kept
 * out and why.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "agent.workspace=${java.io.tmpdir}/agent-test-workspace-audience",
        "agent.llm.provider=stub",
        "agent.auth.enabled=false",
        "agent.rate-limit.enabled=false",
        "agent.memory.enabled=true",
        "agent.memory.project=oss-lib",
        "agent.storage.type=sqlite",
        "spring.datasource.url=jdbc:h2:mem:memory-audience;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class ProjectAudienceIT {

    private static final Pattern SESSION_ID = Pattern.compile("\"sessionId\":\"([0-9a-f-]+)\"");
    private static final Path ROOT = fixture();

    @Autowired MockMvc mvc;
    @Autowired AuditEventRepository auditRepo;

    static final AtomicReference<String> LAST_SYSTEM_PROMPT = new AtomicReference<>();

    @DynamicPropertySource
    static void root(DynamicPropertyRegistry registry) {
        registry.add("agent.memory.root", ROOT::toString);
    }

    private static Path fixture() {
        try {
            Path root = Files.createTempDirectory("memory-audience");
            Files.writeString(root.resolve("memory.yaml"), """
                    format: 1
                    visibility: private
                    projects:
                      oss-lib: { audience: public, uses: [product] }
                      product: { audience: private }
                    """);
            fact(root, "estate", "owner-private-account", "estate", "private");
            fact(root, "estate", "owner-review-habit", "estate", "shareable");
            fact(root, "projects/oss-lib", "oss-lib-build", "project:oss-lib", "private");
            fact(root, "projects/product", "product-customer", "project:product", "private");
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void fact(Path root, String section, String id, String scope, String visibility) throws IOException {
        Path dir = root.resolve(section).resolve("facts");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".yaml"), "format: 1\nvisibility: " + visibility + "\nid: " + id +
                "\nstatement: " + id + " holds.\nkind: convention\nsubject: s/" + id + "\nscope: " + scope +
                "\nstatus: asserted\nconfidence: 1.0\nlast_confirmed: 2026-10-06\ntriggers: [zebra]\n");
    }

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
    void aPublicProjectsTurnKeepsPrivateCardsOutAndAuditsWhy() throws Exception {
        MvcResult result = mvc.perform(post("/api/chat")
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"How does the zebra build work?\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String sessionId = extractSessionId(result.getResponse().getContentAsString());

        assertThat(LAST_SYSTEM_PROMPT.get())
                .contains("oss-lib-build")
                .contains("owner-review-habit")
                .doesNotContain("owner-private-account")
                .doesNotContain("product-customer");

        List<AuditEventEntity> refused = awaitEvents(sessionId, "section.refused");
        assertThat(refused).hasSize(1);
        assertThat(refused.get(0).getDetailJson())
                .contains("\"section\":\"projects/product\"")
                .contains("narrower");
        List<AuditEventEntity> loaded = awaitEvents(sessionId, "section.loaded");
        assertThat(loaded).extracting(AuditEventEntity::getDetailJson)
                .anySatisfy(d -> assertThat(d).contains("\"section\":\"estate\"").contains("shareable"))
                .anySatisfy(d -> assertThat(d).contains("\"section\":\"projects/oss-lib\""));
    }

    private static String extractSessionId(String body) {
        Matcher m = SESSION_ID.matcher(body);
        assertThat(m.find()).as("response contains a sessionId: %s", body).isTrue();
        return m.group(1);
    }

    private List<AuditEventEntity> awaitEvents(String sessionId, String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        List<AuditEventEntity> events = List.of();
        while (System.currentTimeMillis() < deadline) {
            events = auditRepo.findBySessionIdOrderByTimestampAsc(sessionId).stream()
                    .filter(e -> type.equals(e.getEventType()))
                    .toList();
            if (!events.isEmpty()) {
                Thread.sleep(100); // the events are @Async: let the rest of the turn's land
                return auditRepo.findBySessionIdOrderByTimestampAsc(sessionId).stream()
                        .filter(e -> type.equals(e.getEventType())).toList();
            }
            Thread.sleep(50);
        }
        return events;
    }
}
