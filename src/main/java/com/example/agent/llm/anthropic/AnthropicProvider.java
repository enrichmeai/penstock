package com.example.agent.llm.anthropic;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * Anthropic Messages API implementation.
 *
 * Docs: https://docs.claude.com/en/api/messages
 */
@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "anthropic", matchIfMissing = true)
public class AnthropicProvider implements LlmProvider {

    private static final String API_VERSION = "2023-06-01";

    private final AgentProperties.Anthropic cfg;
    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final AgentMetrics metrics;

    public AnthropicProvider(AgentProperties props,
                             WebClient.Builder webClientBuilder,
                             ObjectMapper mapper,
                             AgentMetrics metrics) {
        this.cfg = props.getLlm().getAnthropic();
        this.mapper = mapper;
        this.metrics = metrics;
        this.webClient = webClientBuilder
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader("x-api-key", cfg.getApiKey() == null ? "" : cfg.getApiKey())
                .defaultHeader("anthropic-version", API_VERSION)
                .defaultHeader("content-type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override public String name() { return "anthropic"; }

    @Override
    public CompletionResult complete(String systemPrompt, List<ChatMessage> history, List<ToolSpec> tools, String sessionId) {
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY is not configured. Set env var ANTHROPIC_API_KEY or agent.llm.anthropic.api-key.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel());
        body.put("max_tokens", cfg.getMaxTokens());
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        body.put("messages", convertHistory(history));
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", convertTools(tools));
        }

        JsonNode response;
        try {
            response = com.example.agent.llm.LlmRetry.call(name(), () -> webClient.post()
                    .uri("/v1/messages")
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
                    "ANTHROPIC_API_KEY is not configured. Set env var ANTHROPIC_API_KEY or agent.llm.anthropic.api-key.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel());
        body.put("max_tokens", cfg.getMaxTokens());
        body.put("stream", true);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        body.put("messages", convertHistory(history));
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", convertTools(tools));
        }

        StreamState state = new StreamState();
        try {
            webClient.post()
                    .uri("/v1/messages")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .timeout(Duration.ofMinutes(5))
                    .doOnNext(json -> handleSseEvent(json, state, onToken))
                    .blockLast();
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

        CompletionResult result = state.toResult();
        metrics.recordLlmCall(name(), true, result.usage().inputTokens(), result.usage().outputTokens());
        return result;
    }

    // ---------- streaming event handling ----------

    /** Per-content-block accumulator. */
    private static final class BlockState {
        String type;            // "text" | "tool_use"
        String id;              // tool_use only
        String name;            // tool_use only
        final StringBuilder buf = new StringBuilder();
    }

    /** Aggregate state across the whole stream. */
    private final class StreamState {
        final Map<Integer, BlockState> blocks = new TreeMap<>();
        int inputTokens = 0;
        int outputTokens = 0;

        CompletionResult toResult() {
            StringBuilder text = new StringBuilder();
            List<ToolCall> toolCalls = new ArrayList<>();
            for (BlockState b : blocks.values()) {
                if ("text".equals(b.type)) {
                    if (text.length() > 0) text.append("\n");
                    text.append(b.buf);
                } else if ("tool_use".equals(b.type)) {
                    Map<String, Object> args;
                    String raw = b.buf.toString();
                    if (raw.isBlank()) {
                        args = new HashMap<>();
                    } else {
                        try {
                            args = mapper.readValue(
                                    raw,
                                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                        } catch (Exception ex) {
                            throw new IllegalStateException(
                                    "Anthropic tool_use JSON parse failed: " + raw, ex);
                        }
                    }
                    if (args == null) args = new HashMap<>();
                    toolCalls.add(new ToolCall(b.id, b.name, args));
                }
            }
            return new CompletionResult(
                    ChatMessage.assistant(text.toString(), toolCalls),
                    new TokenUsage(inputTokens, outputTokens));
        }
    }

    private void handleSseEvent(String json, StreamState state, Consumer<String> onToken) {
        if (json == null || json.isBlank()) return;
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("Anthropic stream JSON parse failed: " + json, ex);
        }
        String type = node.path("type").asText();
        switch (type) {
            case "message_start" -> {
                JsonNode usage = node.path("message").path("usage");
                state.inputTokens = usage.path("input_tokens").asInt(state.inputTokens);
                state.outputTokens = usage.path("output_tokens").asInt(state.outputTokens);
            }
            case "content_block_start" -> {
                int index = node.path("index").asInt();
                JsonNode block = node.path("content_block");
                BlockState bs = state.blocks.computeIfAbsent(index, k -> new BlockState());
                bs.type = block.path("type").asText();
                if ("tool_use".equals(bs.type)) {
                    bs.id = block.path("id").asText();
                    bs.name = block.path("name").asText();
                }
            }
            case "content_block_delta" -> {
                int index = node.path("index").asInt();
                BlockState bs = state.blocks.get(index);
                if (bs == null) return;
                JsonNode delta = node.path("delta");
                String dt = delta.path("type").asText();
                if ("text_delta".equals(dt)) {
                    String t = delta.path("text").asText("");
                    bs.buf.append(t);
                    if (!t.isEmpty()) onToken.accept(t);
                } else if ("input_json_delta".equals(dt)) {
                    bs.buf.append(delta.path("partial_json").asText(""));
                }
            }
            case "content_block_stop" -> {
                // Finalization is implicit — buffers are read in toResult().
            }
            case "message_delta" -> {
                JsonNode usage = node.path("usage");
                if (usage.has("output_tokens")) {
                    state.outputTokens = usage.path("output_tokens").asInt(state.outputTokens);
                }
                if (usage.has("input_tokens")) {
                    state.inputTokens = usage.path("input_tokens").asInt(state.inputTokens);
                }
            }
            case "message_stop" -> {
                // terminator — nothing to do
            }
            default -> {
                // ping, error, or unknown — ignore
            }
        }
    }

    // ---------- request building ----------

    private List<Map<String, Object>> convertHistory(List<ChatMessage> history) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatMessage m : history) {
            if (m.role() == Role.SYSTEM) continue;   // system goes in top-level field

            if (m.role() == Role.USER) {
                out.add(Map.of("role", "user", "content", m.text()));
            } else if (m.role() == Role.ASSISTANT) {
                List<Map<String, Object>> content = new ArrayList<>();
                if (m.text() != null && !m.text().isBlank()) {
                    content.add(Map.of("type", "text", "text", m.text()));
                }
                for (ToolCall tc : m.toolCalls()) {
                    content.add(Map.of(
                            "type", "tool_use",
                            "id", tc.id(),
                            "name", tc.name(),
                            "input", tc.arguments() == null ? Map.of() : tc.arguments()
                    ));
                }
                out.add(Map.of("role", "assistant", "content", content));
            } else if (m.role() == Role.TOOL) {
                List<Map<String, Object>> content = new ArrayList<>();
                for (ToolResult r : m.toolResults()) {
                    Map<String, Object> block = new LinkedHashMap<>();
                    block.put("type", "tool_result");
                    block.put("tool_use_id", r.callId());
                    block.put("content", r.content());
                    if (r.isError()) block.put("is_error", true);
                    content.add(block);
                }
                out.add(Map.of("role", "user", "content", content));
            }
        }
        return out;
    }

    private List<Map<String, Object>> convertTools(List<ToolSpec> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolSpec t : tools) {
            out.add(Map.of(
                    "name", t.name(),
                    "description", t.description(),
                    "input_schema", t.inputSchema()
            ));
        }
        return out;
    }

    // ---------- response parsing ----------

    private CompletionResult parseResponse(JsonNode response) {
        if (response == null) {
            throw new IllegalStateException("Empty response from Anthropic API");
        }
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();

        JsonNode content = response.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    if (text.length() > 0) text.append("\n");
                    text.append(block.path("text").asText());
                } else if ("tool_use".equals(type)) {
                    String id = block.path("id").asText();
                    String name = block.path("name").asText();
                    Map<String, Object> args = mapper.convertValue(
                            block.path("input"),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    if (args == null) args = new HashMap<>();
                    toolCalls.add(new ToolCall(id, name, args));
                }
            }
        }
        JsonNode usage = response.path("usage");
        TokenUsage tokens = new TokenUsage(
                usage.path("input_tokens").asInt(0),
                usage.path("output_tokens").asInt(0));
        return new CompletionResult(ChatMessage.assistant(text.toString(), toolCalls), tokens);
    }
}
