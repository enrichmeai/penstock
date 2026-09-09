package com.example.agent.llm.copilot;

import com.example.agent.config.AgentMetrics;
import com.example.agent.config.AgentProperties;
import com.example.agent.model.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CopilotProvider}. These cover the parts we can exercise
 * without a live GitHub Copilot endpoint: config validation and provider identity.
 * End-to-end HTTP behaviour is better tested with MockWebServer fixtures — tracked
 * in ROADMAP Phase 4 alongside wire tests for the other providers.
 */
class CopilotProviderTest {

    private CopilotProvider newProvider(String apiKey) {
        AgentProperties props = new AgentProperties();
        AgentProperties.Copilot cfg = props.getLlm().getCopilot();
        cfg.setApiKey(apiKey);

        return new CopilotProvider(
                props,
                WebClient.builder(),
                new ObjectMapper(),
                new AgentMetrics(new SimpleMeterRegistry())
        );
    }

    @Test
    void nameIsCopilot() {
        assertThat(newProvider("ghp-test").name()).isEqualTo("copilot");
    }

    @Test
    void missingApiKeyFailsFast() {
        CopilotProvider provider = newProvider(null);

        assertThatThrownBy(() -> provider.complete(
                "system",
                List.of(ChatMessage.user("hello")),
                List.of(),
                "session-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GitHub Copilot token is not configured");
    }

    @Test
    void blankApiKeyFailsFast() {
        CopilotProvider provider = newProvider("   ");

        assertThatThrownBy(() -> provider.complete(
                "system",
                List.of(ChatMessage.user("hi")),
                List.of(),
                (String) null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GITHUB_COPILOT_TOKEN");
    }
}
