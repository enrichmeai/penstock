package com.example.agent.config;

import com.example.agent.llm.ToolCallFormatObserver;
import com.example.agent.llm.anthropic.AnthropicProvider;
import com.example.agent.llm.copilot.CopilotProvider;
import com.example.agent.llm.ollama.OllamaProvider;
import com.example.agent.llm.openai.OpenAiProvider;
import com.example.agent.model.ToolResult;
import com.example.agent.tools.CisternTool;
import com.example.agent.tools.ConfiguredCredentialResolver;
import com.example.agent.tools.CredentialMode;
import com.example.agent.tools.JiraTool;
import com.example.agent.tools.ToolContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every LLM provider sets its credential as a default header on the WebClient.Builder it is
 * given. When that builder was one shared singleton, every client built after it inherited the
 * header: the pod tool sent the Anthropic API key (x-api-key) and Copilot's headers to the
 * Cistern pod on every call, and Copilot's {@code Accept: application/json} made the pod answer
 * 406 to a Turtle read. No client may carry another client's headers.
 */
class WebClientHeaderIsolationTest {

    private static final String ANTHROPIC_KEY = "anthropic-key-must-stay-with-anthropic";
    private static final String COPILOT_TOKEN = "copilot-token-must-stay-with-copilot";
    private static final String POD_TOKEN = "pod-token";

    private MockWebServer pod;

    @BeforeEach
    void setUp() throws Exception {
        pod = new MockWebServer();
        pod.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        pod.shutdown();
    }

    @Test
    void aBuilderSharedByEveryProviderLeavesThePodRequestWithOnlyItsOwnHeaders() throws Exception {
        AgentProperties props = new AgentProperties();
        props.getLlm().getAnthropic().setApiKey(ANTHROPIC_KEY);
        props.getLlm().getCopilot().setApiKey(COPILOT_TOKEN);
        props.getTools().getCistern().setBaseUrl(pod.url("/").toString());
        props.getTools().getCistern().setToken(POD_TOKEN);
        props.getTools().getCistern().setCredentialMode(CredentialMode.SERVICE);
        AgentMetrics metrics = new AgentMetrics(new SimpleMeterRegistry());
        ObjectMapper mapper = new ObjectMapper();
        ConfiguredCredentialResolver credentials = new ConfiguredCredentialResolver(props);

        // One builder for all, as a singleton bean hands out; the providers are built first.
        WebClient.Builder shared = WebClient.builder();
        new AnthropicProvider(props, shared, mapper, metrics);
        new CopilotProvider(props, shared, mapper, metrics);
        new OpenAiProvider(props, shared, mapper, metrics, credentials);
        new OllamaProvider(props, shared, mapper, metrics, new ToolCallFormatObserver());
        CisternTool tool = new CisternTool(props, shared, credentials);

        pod.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/turtle").setBody("<#n> <http://purl.org/dc/terms/title> \"n\" ."));
        ToolResult result = tool.execute("c1", Map.of("type", "read", "path", "/notes/n.ttl"),
                new ToolContext("owner", "s1", "r1", Optional.empty()));

        RecordedRequest request = pod.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getHeader("x-api-key")).as("the Anthropic key reached the pod").isNull();
        assertThat(request.getHeader("anthropic-version")).isNull();
        assertThat(request.getHeader("Copilot-Integration-Id")).as("Copilot's headers reached the pod").isNull();
        assertThat(request.getHeader("Editor-Version")).isNull();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + POD_TOKEN);
        assertThat(request.getHeader("Accept")).as("Copilot's Accept made the pod answer 406")
                .isNotEqualTo("application/json");
        assertThat(result.isError()).isFalse();
    }

    @Test
    void aBuilderSharedByEveryProviderLeavesTheJiraRequestWithoutTheirCredentials() throws Exception {
        // Jira is the external destination: a leak there sends the keys off the owner's machines.
        // The guard is the providers cloning before they add headers: if one stops, this fails.
        try (MockWebServer jira = new MockWebServer()) {
            jira.start();
            AgentProperties props = new AgentProperties();
            props.getLlm().getAnthropic().setApiKey(ANTHROPIC_KEY);
            props.getLlm().getCopilot().setApiKey(COPILOT_TOKEN);
            props.getTools().getJira().setBaseUrl(jira.url("/").toString().replaceAll("/$", ""));
            props.getTools().getJira().setToken("jira-token");
            AgentMetrics metrics = new AgentMetrics(new SimpleMeterRegistry());
            ObjectMapper mapper = new ObjectMapper();

            WebClient.Builder shared = WebClient.builder();
            new AnthropicProvider(props, shared, mapper, metrics);
            new CopilotProvider(props, shared, mapper, metrics);
            JiraTool tool = new JiraTool(props, shared);

            jira.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json").setBody("{\"key\":\"P-1\",\"fields\":{}}"));
            tool.execute("c1", Map.of("type", "get", "query", "P-1"),
                    new ToolContext("owner", "s1", "r1", Optional.empty()));

            RecordedRequest request = jira.takeRequest(5, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getHeader("x-api-key")).as("the Anthropic key reached Jira").isNull();
            assertThat(request.getHeader("Copilot-Integration-Id")).as("Copilot's headers reached Jira").isNull();
            assertThat(request.getHeader("Authorization")).as("Copilot's token reached Jira")
                    .doesNotContain(COPILOT_TOKEN);
        }
    }

    @Test
    void theApplicationsBuilderBeanIsAFreshBuilderPerInjectionPoint() throws Exception {
        Scope scope = AppConfig.class.getMethod("webClientBuilder").getAnnotation(Scope.class);
        assertThat(scope).as("AppConfig.webClientBuilder needs @Scope(prototype)").isNotNull();
        assertThat(scope.value()).isEqualTo(ConfigurableBeanFactory.SCOPE_PROTOTYPE);
    }
}
