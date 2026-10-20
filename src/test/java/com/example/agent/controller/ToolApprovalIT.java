package com.example.agent.controller;

import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.TokenUsage;
import com.example.agent.model.ToolCall;
import com.example.agent.tools.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The approval endpoints over HTTP (#179): a {@code list_dir} call under policy {@code ask}
 * waits, is listed by {@code GET /api/sessions/{id}/approvals}, and runs once
 * {@code POST /api/sessions/{id}/approvals/{callId}} approves it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "agent.workspace=${java.io.tmpdir}/agent-test-workspace",
        "agent.llm.provider=stub",
        "agent.auth.enabled=false",
        "agent.storage.type=memory",
        "agent.rate-limit.enabled=false",
        "agent.llm.streaming-enabled=false",
        "agent.tools.policy.list_dir=ask",
        "agent.tools.ask-timeout-seconds=30"
})
class ToolApprovalIT {

    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @TestConfiguration
    static class StubConfig {
        @Bean @Primary
        LlmProvider listDirOnce() {
            return new LlmProvider() {
                @Override public String name() { return "stub"; }
                @Override public CompletionResult complete(String sys, List<ChatMessage> hist, List<ToolSpec> tools, String sessionId) {
                    boolean toolRan = hist.stream().anyMatch(m -> !m.toolResults().isEmpty());
                    return toolRan
                            ? new CompletionResult(ChatMessage.assistantText("listed"), new TokenUsage(1, 1))
                            : new CompletionResult(ChatMessage.assistant("", List.of(new ToolCall("call-1", "list_dir", Map.of("path", ".")))), new TokenUsage(1, 1));
                }
            };
        }
    }

    private String newSession() throws Exception {
        MvcResult r = mvc.perform(post("/api/sessions")).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void anAskedCallWaitsIsListedAndRunsOnApprove() throws Exception {
        String sessionId = newSession();
        CompletableFuture<MvcResult> chat = CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(post("/api/chat").contentType(APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\",\"message\":\"list it\"}")).andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        JsonNode pending = null;
        for (int i = 0; i < 100 && (pending == null || pending.isEmpty()); i++) {
            Thread.sleep(50);
            pending = json.readTree(mvc.perform(get("/api/sessions/" + sessionId + "/approvals"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        }
        assertNotNull(pending);
        assertEquals(1, pending.size(), "one call waiting");
        assertEquals("call-1", pending.get(0).get("callId").asText());
        assertEquals("list_dir", pending.get(0).get("tool").asText());
        assertFalse(chat.isDone(), "the turn is paused");

        mvc.perform(post("/api/sessions/" + sessionId + "/approvals/call-1").contentType(APPLICATION_JSON)
                        .content("{\"decision\":\"approve\"}"))
                .andExpect(status().isNoContent());

        MvcResult done = chat.get(10, TimeUnit.SECONDS);
        JsonNode body = json.readTree(done.getResponse().getContentAsString());
        JsonNode history = body.get("history");
        assertEquals("listed", history.get(history.size() - 1).get("text").asText());
        JsonNode toolResult = history.get(2).get("toolResults").get(0);
        assertFalse(toolResult.get("isError").asBoolean(), toolResult.toString());

        mvc.perform(post("/api/sessions/" + sessionId + "/approvals/call-1").contentType(APPLICATION_JSON)
                        .content("{\"decision\":\"approve\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void answeringNothingWaitingIs404AndABadDecisionIs400() throws Exception {
        String sessionId = newSession();
        mvc.perform(post("/api/sessions/" + sessionId + "/approvals/nope").contentType(APPLICATION_JSON)
                        .content("{\"decision\":\"approve\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/sessions/" + sessionId + "/approvals/nope").contentType(APPLICATION_JSON)
                        .content("{\"decision\":\"maybe\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/sessions/no-such-session/approvals/nope").contentType(APPLICATION_JSON)
                        .content("{\"decision\":\"approve\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/sessions/" + sessionId + "/approvals"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }
}
