package com.example.agent.controller;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The model gateway sees each employee's own key, end to end: the real
 * {@code OpenAiProvider} behind a mock LiteLLM, with the acting user resolved on the
 * request thread and carried into the loop. Alice has a virtual key and Bob does not,
 * so Alice's calls carry hers and Bob's carry the service key — and both carry the
 * request's {@code X-Request-Id}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "agent.workspace=${java.io.tmpdir}/agent-test-workspace-gateway",
        "agent.llm.provider=openai",
        "agent.llm.openai.api-key=service-key",
        "agent.credentials.per-user.openai.alice=alice-virtual-key",
        "agent.auth.enabled=true",
        "agent.auth.username=alice",
        "agent.auth.password=secret",
        "agent.rate-limit.enabled=false",
        "agent.storage.type=memory"
})
class PerUserGatewayKeyIT {

    private static final MockWebServer GATEWAY = startGateway();

    /** Answers like LiteLLM: SSE when the client asks for a stream, JSON otherwise. */
    private static MockWebServer startGateway() {
        try {
            MockWebServer server = new MockWebServer();
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    String accept = request.getHeader("Accept");
                    if (accept != null && accept.contains("text/event-stream")) {
                        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(String.join("\n\n",
                                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"}}]}",
                                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}",
                                "data: [DONE]",
                                ""));
                    }
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody(
                            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"},\"finish_reason\":\"stop\"}],"
                            + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}");
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void stopGateway() throws IOException {
        GATEWAY.shutdown();
    }

    @DynamicPropertySource
    static void gatewayUrl(DynamicPropertyRegistry registry) {
        // Trailing slash on purpose: the path must still compose to /v1/chat/completions.
        registry.add("agent.llm.openai.base-url", () -> GATEWAY.url("/").toString());
    }

    @Autowired MockMvc mvc;

    @Test
    void aliceCallsTheGatewayWithHerOwnKey() throws Exception {
        mvc.perform(post("/api/chat")
                        .with(httpBasic("alice", "secret"))
                        .header("X-Request-Id", "demo-req-alice")
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());

        RecordedRequest seenByGateway = GATEWAY.takeRequest(5, TimeUnit.SECONDS);
        assertThat(seenByGateway).isNotNull();
        assertThat(seenByGateway.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(seenByGateway.getHeader("Authorization")).isEqualTo("Bearer alice-virtual-key");
        assertThat(seenByGateway.getHeader("X-Request-Id")).isEqualTo("demo-req-alice");
    }

    @Test
    void bobHasNoKeyOfHisOwnAndUsesTheServiceKey() throws Exception {
        mvc.perform(post("/api/chat")
                        .with(user("bob"))
                        .header("X-Request-Id", "demo-req-bob")
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());

        RecordedRequest seenByGateway = GATEWAY.takeRequest(5, TimeUnit.SECONDS);
        assertThat(seenByGateway).isNotNull();
        assertThat(seenByGateway.getHeader("Authorization")).isEqualTo("Bearer service-key");
        assertThat(seenByGateway.getHeader("X-Request-Id")).isEqualTo("demo-req-bob");
    }

    @Test
    void theStreamingEndpointCarriesTheSameAcrossTheSseExecutor() throws Exception {
        MvcResult async = mvc.perform(post("/api/chat/stream")
                        .with(httpBasic("alice", "secret"))
                        .header("X-Request-Id", "demo-req-alice-stream")
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(async)).andExpect(status().isOk());

        RecordedRequest seenByGateway = GATEWAY.takeRequest(5, TimeUnit.SECONDS);
        assertThat(seenByGateway).isNotNull();
        assertThat(seenByGateway.getHeader("Accept")).contains("text/event-stream");
        assertThat(seenByGateway.getHeader("Authorization")).isEqualTo("Bearer alice-virtual-key");
        assertThat(seenByGateway.getHeader("X-Request-Id")).isEqualTo("demo-req-alice-stream");
    }
}
