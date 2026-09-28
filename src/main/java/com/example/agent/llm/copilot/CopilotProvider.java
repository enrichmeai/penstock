package com.example.agent.llm.copilot;

import com.example.agent.config.AgentMetrics;
import com.example.agent.config.AgentProperties;
import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.llm.LlmRetry;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.Role;
import com.example.agent.model.TokenUsage;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolResult;
import com.example.agent.tools.ToolSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * GitHub Copilot chat-completions provider.
 *
 * The Copilot API (api.githubcopilot.com) exposes an OpenAI-compatible
 * {@code /chat/completions} surface: same request/response shape, same
 * {@code tool_calls} semantics. The wire format here is intentionally a
 * near-copy of {@link com.example.agent.llm.openai.OpenAiProvider}; only
 * authentication and the default base URL differ.
 *
 * Supply a token via {@code GITHUB_COPILOT_TOKEN} (preferred) or
 * {@code GITHUB_TOKEN}. Enterprise callers can override the base URL to,
 * e.g., GitHub Models or a self-hosted gateway, via
 * {@code COPILOT_BASE_URL}.
 */
@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "copilot")
public class CopilotProvider implements LlmProvider {

    /** Standard integration header Copilot editors send; harmless on other gateways. */
    private static final String INTEGRATION_HEADER = "Copilot-Integration-Id";
    private static final String DEFAULT_INTEGRATION_ID = "vscode-chat";
    private static final String EDITOR_VERSION_HEADER = "Editor-Version";
    private static final String DEFAULT_EDITOR_VERSION = "spring-coding-agent/0.1.0";

    private final AgentProperties.Copilot cfg;
    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final AgentMetrics metrics;

    public CopilotProvider(AgentProperties props,
                           WebClient.Builder webClientBuilder,
                           ObjectMapper mapper,
                           AgentMetrics metrics) {
        this.cfg = props.getLlm().getCopilot();
        this.mapper = mapper;
        this.metrics = metrics;

        WebClient.Builder b = webClientBuilder
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Bearer " + (cfg.getApiKey() == null ? "" : cfg.getApiKey()))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(INTEGRATION_HEADER, DEFAULT_INTEGRATION_ID)
                .defaultHeader(EDITOR_VERSION_HEADER, DEFAULT_EDITOR_VERSION);

        if (cfg.getApiVersion() != null && !cfg.getApiVersion().isBlank()) {
            b = b.defaultHeader("X-GitHub-Api-Version", cfg.getApiVersion());
        }
        this.webClient = b.build();
    }

    @Override public String name() { return "copilot"; }

    @Override
    public CompletionResult complete(String systemPrompt, List<ChatMessage> history, List<ToolSpec> tools, String sessionId) {
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "GitHub Copilot token is not configured. Set env var GITHUB_COPILOT_TOKEN " +
                    "(or GITHUB_TOKEN) or agent.llm.copilot.api-key.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel());
        body.put("max_tokens", cfg.getMaxTokens());
        body.put("messages", convertHistory(systemPrompt, history));
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", convertTools(tools));
        }

        JsonNode response;
        try {
            response = LlmRetry.call(name(), () -> webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMinutes(5))
                    .block());
        } catch (Exception e) {
            metrics.recordLlmCall(name(), false, 0, 0);
            throw e;
        }

        CompletionResult result = parseResponse(response);
        metrics.recordLlmCall(name(), true, result.usage().inputTokens(), result.usage().outputTokens());
        return result;
    }

    @Override
    public CompletionResult completeStreaming(String systemPrompt,
                                              List<ChatMessage> history,
                                              List<ToolSpec> tools,
                                              String sessionId,
                                              Consumer<String> onToken) {
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "GitHub Copilot token is not configured. Set env var GITHUB_COPILOT_TOKEN " +
                    "(or GITHUB_TOKEN) or agent.llm.copilot.api-key.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel());
        body.put("max_tokens", cfg.getMaxTokens());
        body.put("messages", convertHistory(systemPrompt, history));
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", convertTools(tools));
        }
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));

        StringBuilder textBuf = new StringBuilder();
        TreeMap<Integer, ToolCallBuf> toolBufs = new TreeMap<>();
        int[] usage = new int[]{0, 0};

        try {
            webClient.post()
                    .uri("/chat/completions")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .timeout(Duration.ofMinutes(5))
                    .toStream()
                    .forEach(raw -> handleStreamLine(raw, onToken, textBuf, toolBufs, usage));
        } catch (WebClientResponseException e) {
            // Typed here because this path bypasses LlmRetry: a 429 is the gateway's
            // refusal whichever path carried it, and must not reach the caller raw.
            metrics.recordLlmCall(name(), false, 0, 0);
            throw LlmRetry.failure(name(), e);
        } catch (WebClientRequestException e) {
            metrics.recordLlmCall(name(), false, 0, 0);
            throw LlmRetry.unreachable(name(), e);
        } catch (Exception e) {
            metrics.recordLlmCall(name(), false, 0, 0);
            throw e;
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        for (ToolCallBuf b : toolBufs.values()) {
            Map<String, Object> args;
            try {
                String argJson = b.arguments.length() == 0 ? "{}" : b.arguments.toString();
                args = mapper.readValue(argJson, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                args = new HashMap<>();
            }
            toolCalls.add(new ToolCall(b.id == null ? "" : b.id, b.name == null ? "" : b.name, args));
        }

        TokenUsage tokens = new TokenUsage(usage[0], usage[1]);
        CompletionResult result = new CompletionResult(
                ChatMessage.assistant(textBuf.toString(), toolCalls), tokens);

        metrics.recordLlmCall(name(), true, tokens.inputTokens(), tokens.outputTokens());
        return result;
    }

    /** Parse one SSE data payload. Tolerant of both decoded payloads and raw "data:" lines. */
    private void handleStreamLine(String raw,
                                  Consumer<String> onToken,
                                  StringBuilder textBuf,
                                  TreeMap<Integer, ToolCallBuf> toolBufs,
                                  int[] usage) {
        if (raw == null) return;
        String payload = raw;
        if (payload.startsWith("data:")) payload = payload.substring(5);
        payload = payload.trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) return;

        JsonNode event;
        try {
            event = mapper.readTree(payload);
        } catch (Exception e) {
            return;
        }

        JsonNode usageNode = event.path("usage");
        if (usageNode.isObject()) {
            usage[0] = usageNode.path("prompt_tokens").asInt(usage[0]);
            usage[1] = usageNode.path("completion_tokens").asInt(usage[1]);
        }

        JsonNode choices = event.path("choices");
        if (!choices.isArray()) return;
        for (JsonNode choice : choices) {
            JsonNode delta = choice.path("delta");
            JsonNode contentNode = delta.path("content");
            if (contentNode.isTextual()) {
                String chunk = contentNode.asText();
                if (!chunk.isEmpty()) {
                    textBuf.append(chunk);
                    onToken.accept(chunk);
                }
            }
            JsonNode tcArr = delta.path("tool_calls");
            if (tcArr.isArray()) {
                for (JsonNode tc : tcArr) {
                    int idx = tc.path("index").asInt(0);
                    ToolCallBuf buf = toolBufs.computeIfAbsent(idx, k -> new ToolCallBuf());
                    if (tc.hasNonNull("id")) buf.id = tc.path("id").asText();
                    JsonNode fn = tc.path("function");
                    if (fn.hasNonNull("name")) buf.name = fn.path("name").asText();
                    JsonNode argFrag = fn.path("arguments");
                    if (argFrag.isTextual()) buf.arguments.append(argFrag.asText());
                }
            }
        }
    }

    private static final class ToolCallBuf {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    // -- translation helpers (mirrors OpenAI wire format) -----------------

    private List<Map<String, Object>> convertHistory(String systemPrompt, List<ChatMessage> history) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            out.add(Map.of("role", "system", "content", systemPrompt));
        }
        for (ChatMessage m : history) {
            if (m.role() == Role.SYSTEM) continue;

            if (m.role() == Role.USER) {
                out.add(Map.of("role", "user", "content", m.text() == null ? "" : m.text()));
            } else if (m.role() == Role.ASSISTANT) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", "assistant");
                msg.put("content", m.text() == null ? "" : m.text());
                if (!m.toolCalls().isEmpty()) {
                    List<Map<String, Object>> calls = new ArrayList<>();
                    for (ToolCall tc : m.toolCalls()) {
                        try {
                            calls.add(Map.of(
                                    "id", tc.id(),
                                    "type", "function",
                                    "function", Map.of(
                                            "name", tc.name(),
                                            "arguments", mapper.writeValueAsString(
                                                    tc.arguments() == null ? Map.of() : tc.arguments())
                                    )
                            ));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                    msg.put("tool_calls", calls);
                }
                out.add(msg);
            } else if (m.role() == Role.TOOL) {
                for (ToolResult r : m.toolResults()) {
                    out.add(Map.of(
                            "role", "tool",
                            "tool_call_id", r.callId(),
                            "content", r.content()
                    ));
                }
            }
        }
        return out;
    }

    private List<Map<String, Object>> convertTools(List<ToolSpec> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolSpec t : tools) {
            out.add(Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", t.name(),
                            "description", t.description(),
                            "parameters", t.inputSchema()
                    )
            ));
        }
        return out;
    }

    private CompletionResult parseResponse(JsonNode response) {
        if (response == null || !response.has("choices")) {
            throw new IllegalStateException("Empty or invalid response from GitHub Copilot API");
        }
        JsonNode message = response.path("choices").path(0).path("message");
        String text = message.path("content").isTextual() ? message.path("content").asText() : "";

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode calls = message.path("tool_calls");
        if (calls.isArray()) {
            for (JsonNode call : calls) {
                String id = call.path("id").asText();
                String name = call.path("function").path("name").asText();
                String argJson = call.path("function").path("arguments").asText("{}");
                Map<String, Object> args;
                try {
                    args = mapper.readValue(argJson, new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    args = new HashMap<>();
                }
                toolCalls.add(new ToolCall(id, name, args));
            }
        }
        JsonNode usage = response.path("usage");
        TokenUsage tokens = new TokenUsage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0));
        return new CompletionResult(ChatMessage.assistant(text, toolCalls), tokens);
    }
}
