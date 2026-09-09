package com.example.agent.controller;

import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.TokenUsage;
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

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Session ownership in memory mode, with auth on. The in-memory store stamps each session
 * with {@code CurrentUser.name()} — but only if Spring actually hands it a
 * {@code CurrentUser}: the class also has a no-arg constructor for unit tests, and with two
 * constructors and no {@code @Autowired} Spring picks the no-arg one, leaving every session
 * stamped "anonymous" and visible to everyone. {@code IdentityPropagationIT} could not see
 * this because it runs the JPA store.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "agent.workspace=${java.io.tmpdir}/agent-test-workspace-ownership",
        "agent.llm.provider=stub",
        "agent.auth.enabled=true",
        "agent.auth.username=alice",
        "agent.auth.password=secret",
        "agent.rate-limit.enabled=false",
        "agent.storage.type=memory"
})
class MemoryModeOwnershipIT {

    /** POST /api/sessions answers with a SessionSummary, whose id field is "id". */
    private static final Pattern SESSION_ID = Pattern.compile("\"id\":\"([0-9a-f-]+)\"");

    @Autowired MockMvc mvc;

    @TestConfiguration
    static class StubCfg {
        @Bean @Primary
        LlmProvider stub() {
            return new LlmProvider() {
                @Override public String name() { return "stub"; }
                @Override public CompletionResult complete(String sys, List<ChatMessage> h, List<ToolSpec> t, String sessionId) {
                    return new CompletionResult(ChatMessage.assistantText("ok"), TokenUsage.ZERO);
                }
            };
        }
    }

    @Test
    void aSessionBelongsToWhoeverCreatedItAndIsNotFoundForAnyoneElse() throws Exception {
        String body = mvc.perform(post("/api/sessions").with(httpBasic("alice", "secret")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher m = SESSION_ID.matcher(body);
        assertThat(m.find()).as("session id in %s", body).isTrue();
        String id = m.group(1);

        mvc.perform(get("/api/sessions/" + id).with(httpBasic("alice", "secret")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/sessions/" + id).with(user("bob")))
                .andExpect(status().isNotFound());
        assertThat(mvc.perform(get("/api/sessions").with(user("bob")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .doesNotContain(id);
    }
}
