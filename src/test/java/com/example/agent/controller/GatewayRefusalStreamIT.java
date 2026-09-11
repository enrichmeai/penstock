package com.example.agent.controller;

import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.GatewayRefusedException;
import com.example.agent.llm.LiteLlmFixtures;
import com.example.agent.llm.LlmMessage;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.tools.ToolSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * The SSE stream when the gateway refuses the model call: the {@code error} event must say
 * so with the same {@code code} and {@code reason} the REST endpoint returns, so the UI can
 * tell a refusal from a stream failure without reading server logs. The refusal is the
 * gateway's captured 429, thrown through the real controller and agent loop.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "agent.workspace=${java.io.tmpdir}/agent-test-workspace-gateway-refusal",
        "agent.llm.provider=stub",
        "agent.auth.enabled=false",
        "agent.storage.type=memory",
        "agent.rate-limit.enabled=false"
})
@Import(GatewayRefusalStreamIT.RefusingGateway.class)
class GatewayRefusalStreamIT {

    private static final String REQUEST_ID = "req-refused-1";
    private static final String UPSTREAM_BODY_DETAIL = "Team=support";

    @Autowired MockMvc mvc;

    @TestConfiguration
    static class RefusingGateway {
        @Bean @Primary
        LlmProvider refusing() {
            return new LlmProvider() {
                @Override public String name() { return "openai"; }
                @Override public CompletionResult complete(String sys, List<ChatMessage> hist, List<ToolSpec> tools, String sessionId) {
                    try {
                        throw GatewayRefusedException.from(name(),
                                LiteLlmFixtures.load(LiteLlmFixtures.BUDGET_EXCEEDED).asException());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            };
        }
    }

    @Test
    void theErrorEventCarriesTheGatewaysCodeAndReason() throws Exception {
        MvcResult async = mvc.perform(post("/api/chat/stream")
                        .contentType(APPLICATION_JSON)
                        .header("X-Request-Id", REQUEST_ID)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(async)).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("event:error");
        assertThat(body).contains("\"code\":\"gateway_refused\"");
        assertThat(body).contains("\"reason\":\"budget_exceeded\"");
        assertThat(body).contains("\"requestId\":\"" + REQUEST_ID + "\"");
        assertThat(body).contains(LlmMessage.GATEWAY_REFUSED_BUDGET_EXCEEDED.format());
        assertThat(body).doesNotContain(UPSTREAM_BODY_DETAIL);
        assertThat(body).doesNotContain("event:done");
    }
}
