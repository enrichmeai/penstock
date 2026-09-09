package com.example.agent.config;

import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.TokenUsage;
import com.example.agent.tools.ToolSpec;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for LlmProviderHealthIndicator.
 *
 * Verifies that the health check correctly identifies when a provider is UP or DOWN
 * based on configuration alone (no external API calls).
 */
class LlmProviderHealthIndicatorTest {

    /**
     * Stub LLM provider that returns a configurable name.
     */
    static class StubLlmProvider implements LlmProvider {
        private final String providerName;

        StubLlmProvider(String providerName) {
            this.providerName = providerName;
        }

        @Override
        public String name() {
            return providerName;
        }

        @Override
        public CompletionResult complete(String systemPrompt, List<ChatMessage> history, List<ToolSpec> tools, String sessionId) {
            return new CompletionResult(ChatMessage.assistantText("stub"), TokenUsage.ZERO);
        }
    }

    /**
     * Helper to set up test properties with the given provider config.
     */
    private AgentProperties createProperties(String provider, String apiKey, String openAiApiKey) {
        return createProperties(provider, apiKey, openAiApiKey, null);
    }

    private AgentProperties createProperties(String provider, String apiKey, String openAiApiKey, String copilotApiKey) {
        AgentProperties props = new AgentProperties();
        AgentProperties.Llm llmCfg = new AgentProperties.Llm();
        llmCfg.setProvider(provider);

        AgentProperties.Anthropic anthropic = new AgentProperties.Anthropic();
        anthropic.setApiKey(apiKey);
        llmCfg.setAnthropic(anthropic);

        AgentProperties.OpenAi openai = new AgentProperties.OpenAi();
        openai.setApiKey(openAiApiKey);
        llmCfg.setOpenai(openai);

        AgentProperties.Ollama ollama = new AgentProperties.Ollama();
        ollama.setBaseUrl("http://localhost:11434");
        llmCfg.setOllama(ollama);

        AgentProperties.Copilot copilot = new AgentProperties.Copilot();
        copilot.setApiKey(copilotApiKey);
        llmCfg.setCopilot(copilot);

        props.setLlm(llmCfg);
        return props;
    }

    @Test
    void anthropicWithApiKeyReturnsUp() {
        // Arrange
        LlmProvider provider = new StubLlmProvider("anthropic");
        AgentProperties props = createProperties("anthropic", "sk-test-key", null);
        LlmProviderHealthIndicator indicator = new LlmProviderHealthIndicator(provider, props, new com.example.agent.llm.ToolCallFormatObserver());

        // Act
        Health health = indicator.health();

        // Assert
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("provider", "anthropic");
    }

    @Test
    void anthropicWithBlankApiKeyReturnsDown() {
        // Arrange
        LlmProvider provider = new StubLlmProvider("anthropic");
        AgentProperties props = createProperties("anthropic", "", null);
        LlmProviderHealthIndicator indicator = new LlmProviderHealthIndicator(provider, props, new com.example.agent.llm.ToolCallFormatObserver());

        // Act
        Health health = indicator.health();

        // Assert
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("provider", "anthropic");
        assertThat(health.getDetails()).containsEntry("reason", "Anthropic API key not configured");
    }

    @Test
    void anthropicWithNullApiKeyReturnsDown() {
        // Arrange
        LlmProvider provider = new StubLlmProvider("anthropic");
        AgentProperties props = createProperties("anthropic", null, null);
        LlmProviderHealthIndicator indicator = new LlmProviderHealthIndicator(provider, props, new com.example.agent.llm.ToolCallFormatObserver());

        // Act
        Health health = indicator.health();

        // Assert
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("provider", "anthropic");
        assertThat(health.getDetails()).containsEntry("reason", "Anthropic API key not configured");
    }

    @Test
    void openaiWithApiKeyReturnsUp() {
        // Arrange
        LlmProvider provider = new StubLlmProvider("openai");
        AgentProperties props = createProperties("openai", null, "sk-openai-test");
        LlmProviderHealthIndicator indicator = new LlmProviderHealthIndicator(provider, props, new com.example.agent.llm.ToolCallFormatObserver());

        // Act
        Health health = indicator.health();

        // Assert
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("provider", "openai");
    }

    @Test
    void openaiWithBlankApiKeyReturnsDown() {
        // Arrange
        LlmProvider provider = new StubLlmProvider("openai");
        AgentProperties props = createProperties("openai", null, "");
        LlmProviderHealthIndicator indicator = new LlmProviderHealthIndicator(provider, props, new com.example.agent.llm.ToolCallFormatObserver());

        // Act
        Health health = indicator.health();

        // Assert
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("provider", "openai");
        assertThat(health.getDetails()).containsEntry("reason", "OpenAI API key not configured");
    }

    @Test
    void openaiWithNullApiKeyReturnsDown() {
        // Arrange
        LlmProvider provider = new StubLlmProvider("openai");
        AgentProperties props = createProperties("openai", null, null);
        LlmProviderHealthIndicator indicator = new LlmProviderHealthIndicator(provider, props, new com.example.agent.llm.ToolCallFormatObserver());

        // Act
        Health health = indicator.health();

        // Assert
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("provider", "openai");
        assertThat(health.getDetails()).containsEntry("reason", "OpenAI API key not configured");
    }

    @Test
    void ollamaWithoutApiKeyReturnsUp() {
        // Arrange: Ollama does not require an API key; it always has a default base-url
        LlmProvider provider = new StubLlmProvider("ollama");
        AgentProperties props = createProperties("ollama", null, null);
        LlmProviderHealthIndicator indicator = new LlmProviderHealthIndicator(provider, props, new com.example.agent.llm.ToolCallFormatObserver());

        // Act
        Health health = indicator.health();

        // Assert
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("provider", "ollama");
    }

    @Test
    void ollamaAlwaysReturnsUpRegardlessOfConfig() {
        // Arrange: Even if we set random values, Ollama should still be UP
        LlmProvider provider = new StubLlmProvider("ollama");
        AgentProperties props = createProperties("ollama", "", "");
        LlmProviderHealthIndicator indicator = new LlmProviderHealthIndicator(provider, props, new com.example.agent.llm.ToolCallFormatObserver());

        // Act
        Health health = indicator.health();

        // Assert
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("provider", "ollama");
    }

    @Test
    void copilotWithTokenReturnsUp() {
        LlmProvider provider = new StubLlmProvider("copilot");
        AgentProperties props = createProperties("copilot", null, null, "ghp-test-token");
        LlmProviderHealthIndicator indicator = new LlmProviderHealthIndicator(provider, props, new com.example.agent.llm.ToolCallFormatObserver());

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("provider", "copilot");
    }

    @Test
    void copilotWithBlankTokenReturnsDown() {
        LlmProvider provider = new StubLlmProvider("copilot");
        AgentProperties props = createProperties("copilot", null, null, "");
        LlmProviderHealthIndicator indicator = new LlmProviderHealthIndicator(provider, props, new com.example.agent.llm.ToolCallFormatObserver());

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("provider", "copilot");
        assertThat(health.getDetails()).containsEntry("reason",
                "GitHub Copilot token not configured (set GITHUB_COPILOT_TOKEN)");
    }

    @Test
    void copilotWithNullTokenReturnsDown() {
        LlmProvider provider = new StubLlmProvider("copilot");
        AgentProperties props = createProperties("copilot", null, null, null);
        LlmProviderHealthIndicator indicator = new LlmProviderHealthIndicator(provider, props, new com.example.agent.llm.ToolCallFormatObserver());

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("provider", "copilot");
    }

    @Test
    void openAiWithOnlyPerUserGatewayKeysReturnsUp() {
        // A deployment where every employee carries a virtual key may have no service key.
        LlmProvider provider = new StubLlmProvider("openai");
        AgentProperties props = createProperties("openai", null, null);
        props.getCredentials().setPerUser(java.util.Map.of("openai", java.util.Map.of("alice", "alice-virtual-key")));
        LlmProviderHealthIndicator indicator = new LlmProviderHealthIndicator(provider, props, new com.example.agent.llm.ToolCallFormatObserver());

        assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");
    }

    @Test
    void openAiWithBlankPerUserKeysAndNoServiceKeyReturnsDown() {
        LlmProvider provider = new StubLlmProvider("openai");
        AgentProperties props = createProperties("openai", null, null);
        props.getCredentials().setPerUser(java.util.Map.of("openai", java.util.Map.of("alice", "")));
        LlmProviderHealthIndicator indicator = new LlmProviderHealthIndicator(provider, props, new com.example.agent.llm.ToolCallFormatObserver());

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("reason", "OpenAI API key not configured");
    }
}
