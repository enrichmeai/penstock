package com.example.agent.llm.openai;

import com.example.agent.config.AgentMetrics;
import com.example.agent.config.AgentProperties;
import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmCallContext;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.ToolCall;
import com.example.agent.tools.ConfiguredCredentialResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiProviderWireTest {

    private static final String TEXT_RESPONSE = "{"
            + "\"choices\":[{"
            + "\"message\":{\"role\":\"assistant\",\"content\":\"Hello world\"},"
            + "\"finish_reason\":\"stop\""
            + "}],"
            + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5,\"total_tokens\":17}"
            + "}";

    private MockWebServer server;
    private AgentProperties props;
    private OpenAiProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        props = new AgentProperties();
        AgentProperties.OpenAi cfg = props.getLlm().getOpenai();
        cfg.setApiKey("test-key");
        cfg.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        cfg.setModel("gpt-4o");
        props.getCredentials().setPerUser(Map.of("openai", Map.of("alice", "alice-virtual-key")));

        provider = provider(props);
    }

    private static OpenAiProvider provider(AgentProperties props) {
        return new OpenAiProvider(
                props,
                WebClient.builder(),
                new ObjectMapper(),
                new AgentMetrics(new SimpleMeterRegistry()),
                new ConfiguredCredentialResolver(props));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void completeReturnsTextResponse() throws Exception {
        server.enqueue(json(TEXT_RESPONSE));

        CompletionResult result = provider.complete(
                "system",
                List.of(ChatMessage.user("hi")),
                List.of(),
                "session-1");

        assertThat(result.message().text()).isEqualTo("Hello world");
        assertThat(result.message().toolCalls()).isEmpty();
        assertThat(result.usage().inputTokens()).isEqualTo(12);
        assertThat(result.usage().outputTokens()).isEqualTo(5);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/chat/completions");
        // Session-only entry point: no user known, so the configured key; no request ID to send.
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer test-key");
        assertThat(req.getHeader("X-Request-Id")).isNull();
    }

    @Test
    void completeAssemblesToolCallResponse() {
        String body = "{"
                + "\"choices\":[{"
                + "\"message\":{"
                + "\"role\":\"assistant\","
                + "\"content\":null,"
                + "\"tool_calls\":[{"
                + "\"id\":\"call_1\","
                + "\"type\":\"function\","
                + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"foo.txt\\\"}\"}"
                + "}]"
                + "},"
                + "\"finish_reason\":\"tool_calls\""
                + "}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":11,\"total_tokens\":18}"
                + "}";

        server.enqueue(json(body));

        CompletionResult result = provider.complete(
                "system",
                List.of(ChatMessage.user("read foo.txt")),
                List.of(),
                "session-2");

        assertThat(result.message().toolCalls()).hasSize(1);
        ToolCall call = result.message().toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_1");
        assertThat(call.name()).isEqualTo("read_file");
        assertThat(call.arguments()).containsEntry("path", "foo.txt");
        assertThat(result.usage().inputTokens()).isEqualTo(7);
        assertThat(result.usage().outputTokens()).isEqualTo(11);
    }

    @Test
    void perUserGatewayKeyIsChosenOverTheConfiguredKeyAndTheRequestIdTravels() throws Exception {
        server.enqueue(json(TEXT_RESPONSE));

        provider.complete("system", List.of(ChatMessage.user("hi")), List.of(),
                new LlmCallContext("alice", "session-3", "req-77"));

        RecordedRequest req = server.takeRequest();
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer alice-virtual-key");
        assertThat(req.getHeader("X-Request-Id")).isEqualTo("req-77");
    }

    @Test
    void usersWithoutTheirOwnKeyFallBackToTheConfiguredKey() throws Exception {
        server.enqueue(json(TEXT_RESPONSE));

        provider.complete("system", List.of(ChatMessage.user("hi")), List.of(),
                new LlmCallContext("bob", "session-4", "req-78"));

        RecordedRequest req = server.takeRequest();
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer test-key");
        assertThat(req.getHeader("X-Request-Id")).isEqualTo("req-78");
    }

    @Test
    void noCredentialAtAllIsAConfigurationErrorBeforeAnyCall() {
        props.getLlm().getOpenai().setApiKey(null);
        OpenAiProvider noServiceKey = provider(props);

        assertThatThrownBy(() -> noServiceKey.complete("system", List.of(ChatMessage.user("hi")), List.of(),
                new LlmCallContext("bob", "session-5", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent.credentials.per-user.openai.bob")
                .hasMessageContaining("OPENAI_API_KEY");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void perUserKeyAloneIsEnoughWithNoServiceKeyConfigured() throws Exception {
        props.getLlm().getOpenai().setApiKey(null);
        server.enqueue(json(TEXT_RESPONSE));

        provider(props).complete("system", List.of(ChatMessage.user("hi")), List.of(),
                new LlmCallContext("alice", "session-6", null));

        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer alice-virtual-key");
    }

    @Test
    void aLiteLlmStyleBaseUrlComposesToTheV1CompletionsPath() throws Exception {
        // LiteLLM is addressed as http://litellm:4000 and serves /v1/chat/completions; a
        // trailing slash on the base URL must not double the slash or lose the path.
        props.getLlm().getOpenai().setBaseUrl(server.url("/").toString());
        server.enqueue(json(TEXT_RESPONSE));

        provider(props).complete("system", List.of(ChatMessage.user("hi")), List.of(), "session-7");

        assertThat(server.takeRequest().getPath()).isEqualTo("/v1/chat/completions");
    }
}
