package com.example.agent.controller;

import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.Role;
import com.example.agent.model.TokenUsage;
import com.example.agent.model.ToolCall;
import com.example.agent.tools.ToolSpec;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acting as the signed-in user, end to end: a request authenticated with a JWT reaches the
 * controller, the turn crosses the SSE executor (no SecurityContext there), and the pod
 * tool presents <em>that</em> bearer to Cistern — never the agent's own — together with the
 * request's {@code X-Request-Id}. A stub-injected unit test cannot prove this: the token
 * and the ID have to survive the thread hop, and the filter, the controller and the tool
 * each own a piece of the answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "agent.workspace=${java.io.tmpdir}/agent-test-workspace-forwarded",
        "agent.llm.provider=stub",
        "agent.auth.enabled=true",
        "agent.auth.username=alice",
        "agent.auth.password=secret",
        "agent.rate-limit.enabled=false",
        "agent.storage.type=memory",
        "agent.tools.cistern.credential-mode=forward",
        // The agent's own credential exists and must never be sent in this mode.
        "agent.tools.cistern.token=agent-service-token"
})
class ForwardedBearerIT {

    private static final String ALICE_TOKEN = "eyJhbGciOiJSUzI1NiJ9.alice.signature";
    private static final MockWebServer POD = startPod();

    private static MockWebServer startPod() {
        try {
            MockWebServer server = new MockWebServer();
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void stopPod() throws IOException {
        POD.shutdown();
    }

    @DynamicPropertySource
    static void podUrl(DynamicPropertyRegistry registry) {
        registry.add("agent.tools.cistern.base-url", () -> POD.url("/").toString());
    }

    @Autowired MockMvc mvc;

    @TestConfiguration
    static class StubCfg {
        @Bean @Primary
        LlmProvider scriptedStub() {
            return new LlmProvider() {
                @Override public String name() { return "stub"; }
                @Override public CompletionResult complete(String sys, List<ChatMessage> hist, List<ToolSpec> tools, String sessionId) {
                    ChatMessage last = hist.get(hist.size() - 1);
                    if (last.role() == Role.USER) {
                        return new CompletionResult(
                                ChatMessage.assistant("Reading your notes.",
                                        List.of(new ToolCall("tc1", "pod", Map.of("type", "read", "path", "/notes/a.ttl")))),
                                new TokenUsage(10, 5));
                    }
                    return new CompletionResult(ChatMessage.assistantText("Done."), new TokenUsage(7, 3));
                }
            };
        }
    }

    @Test
    void syncChatPresentsTheCallersBearerAndRequestIdToThePod() throws Exception {
        POD.enqueue(new MockResponse().setBody("<#a> <#b> <#c> ."));

        mvc.perform(post("/api/chat")
                        .with(jwt().jwt(j -> j.tokenValue(ALICE_TOKEN).subject("alice")))
                        .header("X-Request-Id", "demo-req-sync")
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"read my notes\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "demo-req-sync"));

        RecordedRequest seenByPod = POD.takeRequest(5, TimeUnit.SECONDS);
        assertThat(seenByPod).isNotNull();
        assertThat(seenByPod.getPath()).isEqualTo("/notes/a.ttl");
        assertThat(seenByPod.getHeader("Authorization")).isEqualTo("Bearer " + ALICE_TOKEN);
        assertThat(seenByPod.getHeader("X-Request-Id")).isEqualTo("demo-req-sync");
    }

    @Test
    void streamingChatCarriesBothAcrossTheSseExecutor() throws Exception {
        POD.enqueue(new MockResponse().setBody("<#a> <#b> <#c> ."));

        MvcResult async = mvc.perform(post("/api/chat/stream")
                        .with(jwt().jwt(j -> j.tokenValue(ALICE_TOKEN).subject("alice")))
                        .header("X-Request-Id", "demo-req-stream")
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"read my notes\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult result = mvc.perform(asyncDispatch(async)).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("event:done");

        RecordedRequest seenByPod = POD.takeRequest(5, TimeUnit.SECONDS);
        assertThat(seenByPod).isNotNull();
        assertThat(seenByPod.getHeader("Authorization")).isEqualTo("Bearer " + ALICE_TOKEN);
        assertThat(seenByPod.getHeader("X-Request-Id")).isEqualTo("demo-req-stream");
    }

    @Test
    void aCallerWithoutABearerGetsARefusalAndThePodIsNeverCalledAsTheAgent() throws Exception {
        MvcResult result = mvc.perform(post("/api/chat")
                        .with(httpBasic("alice", "secret"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"read my notes\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("no bearer token to forward");
        assertThat(POD.takeRequest(300, TimeUnit.MILLISECONDS))
                .as("forward mode never substitutes the agent's own credential")
                .isNull();
    }
}
