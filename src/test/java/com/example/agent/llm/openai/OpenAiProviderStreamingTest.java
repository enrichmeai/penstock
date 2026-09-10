package com.example.agent.llm.openai;

import com.example.agent.config.AgentMetrics;
import com.example.agent.config.AgentProperties;
import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.GatewayRefusal;
import com.example.agent.llm.GatewayRefusedException;
import com.example.agent.llm.LiteLlmFixtures;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiProviderStreamingTest {

    private MockWebServer server;
    private OpenAiProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        AgentProperties props = new AgentProperties();
        AgentProperties.OpenAi cfg = props.getLlm().getOpenai();
        cfg.setApiKey("test-key");
        cfg.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        cfg.setModel("gpt-4o");

        props.getCredentials().setPerUser(java.util.Map.of("openai", java.util.Map.of("alice", "alice-virtual-key")));

        provider = new OpenAiProvider(
                props,
                WebClient.builder(),
                new ObjectMapper(),
                new AgentMetrics(new SimpleMeterRegistry()),
                new com.example.agent.tools.ConfiguredCredentialResolver(props));
    }

    @Test
    void theStreamingPathCarriesThePerUserKeyAndTheRequestId() throws Exception {
        String sse = String.join("\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}",
                "data: [DONE]",
                "");
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse));

        provider.completeStreaming("system", List.of(ChatMessage.user("hi")), List.of(),
                new com.example.agent.llm.LlmCallContext("alice", "session-9", "req-stream-1"), t -> {});

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer alice-virtual-key");
        assertThat(req.getHeader("X-Request-Id")).isEqualTo("req-stream-1");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    /**
     * With streaming on (the default), every model call takes this path and never sees
     * LlmRetry — so the refusal must be typed here too, or the demo's 429 stays a 500.
     */
    @Test
    void aBudgetRefusalOnTheStreamingPathIsTypedWithoutARetry() throws Exception {
        server.enqueue(LiteLlmFixtures.load(LiteLlmFixtures.BUDGET_EXCEEDED).asMockResponse());

        assertThatThrownBy(() -> provider.completeStreaming("system", List.of(ChatMessage.user("hi")), List.of(),
                new com.example.agent.llm.LlmCallContext("bob", "session-10", "req-stream-2"), t -> {}))
                .isInstanceOfSatisfying(GatewayRefusedException.class, refused ->
                        assertThat(refused.refusal()).isEqualTo(GatewayRefusal.BUDGET_EXCEEDED));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void aRateLimitOnTheStreamingPathIsTypedWithItsRetryAfterAndNotRetried() throws Exception {
        server.enqueue(LiteLlmFixtures.load(LiteLlmFixtures.RATE_LIMITED).asMockResponse());

        assertThatThrownBy(() -> provider.completeStreaming("system", List.of(ChatMessage.user("hi")), List.of(),
                new com.example.agent.llm.LlmCallContext("bob", "session-11", "req-stream-3"), t -> {}))
                .isInstanceOfSatisfying(GatewayRefusedException.class, refused -> {
                    assertThat(refused.refusal()).isEqualTo(GatewayRefusal.RATE_LIMITED);
                    assertThat(refused.retryAfter()).contains(java.time.Duration.ofSeconds(60));
                });
        assertThat(server.getRequestCount()).as("a mid-stream retry would re-emit tokens; none is made").isEqualTo(1);
    }

    @Test
    void streamsTextDeltasAndUsage() throws Exception {
        String sse = String.join("\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello \"}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"world\"}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15}}",
                "data: [DONE]",
                "");

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse));

        List<String> tokens = new ArrayList<>();
        CompletionResult result = provider.completeStreaming(
                "system",
                List.of(ChatMessage.user("hi")),
                List.of(),
                "session-1",
                tokens::add);

        assertThat(tokens).containsExactly("Hello ", "world");
        assertThat(result.message().text()).isEqualTo("Hello world");
        assertThat(result.message().toolCalls()).isEmpty();
        assertThat(result.usage().inputTokens()).isEqualTo(12);
        assertThat(result.usage().outputTokens()).isEqualTo(3);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/chat/completions");
        JsonNode sent = new ObjectMapper().readTree(req.getBody().readUtf8());
        assertThat(sent.path("stream").asBoolean()).isTrue();
        assertThat(sent.path("stream_options").path("include_usage").asBoolean()).isTrue();
    }

    @Test
    void assemblesToolCallFromArgumentFragments() {
        String sse = String.join("\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"index\":0,\"id\":\"call_abc\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"\"}}]}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"pa\"}}]}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"th\\\":\\\"\"}}]}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"foo.txt\\\"}\"}}]}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":7,\"total_tokens\":27}}",
                "data: [DONE]",
                "");

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse));

        List<String> tokens = new ArrayList<>();
        CompletionResult result = provider.completeStreaming(
                "system",
                List.of(ChatMessage.user("read foo.txt")),
                List.of(),
                "session-2",
                tokens::add);

        assertThat(tokens).isEmpty();
        assertThat(result.message().text()).isEmpty();
        assertThat(result.message().toolCalls()).hasSize(1);
        ToolCall call = result.message().toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_abc");
        assertThat(call.name()).isEqualTo("read_file");
        assertThat(call.arguments()).containsEntry("path", "foo.txt");
        assertThat(result.usage().inputTokens()).isEqualTo(20);
        assertThat(result.usage().outputTokens()).isEqualTo(7);
    }
}
