package com.example.agent.llm.ollama;

import com.example.agent.config.AgentMetrics;
import com.example.agent.config.AgentProperties;
import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.llm.ToolCallFormatObserver;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.Role;
import com.example.agent.model.TokenUsage;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolResult;
import com.example.agent.tools.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ollama chat endpoint implementation.
 *
 * Docs: https://github.com/ollama/ollama/blob/main/docs/api.md#generate-a-chat-completion
 * Ollama's tool support is OpenAI-compatible for models that advertise it.
 */
@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "ollama")
public class OllamaProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);

    private final AgentProperties.Ollama cfg;
    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final AgentMetrics metrics;
    private final TextToolCallParser textToolCallParser;
    private final ToolCallFormatObserver formatObserver;
    /** Sessions already warned about context pressure; bounded so it cannot grow forever. */
    private final Map<String, Boolean> contextWarned = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 1024;
                }
            });

    public OllamaProvider(AgentProperties props,
                          WebClient.Builder webClientBuilder,
                          ObjectMapper mapper,
                          AgentMetrics metrics,
                          ToolCallFormatObserver formatObserver) {
        this.cfg = props.getLlm().getOllama();
        this.mapper = mapper;
        this.metrics = metrics;
        this.textToolCallParser = new TextToolCallParser(mapper);
        this.formatObserver = formatObserver;
        this.webClient = webClientBuilder.clone() // never add headers to the shared builder
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader("content-type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override public String name() { return "ollama"; }

    /**
     * Ollama reports what it actually evaluated, so a prompt that got truncated comes
     * back with prompt_eval_count pinned at num_ctx. Warning at 80% catches both the
     * truncation itself and the approach to it, which is otherwise entirely silent.
     * Once per session — the loop calls this on every iteration of a turn.
     */
    private void warnIfNearContextLimit(int promptTokens, String sessionId) {
        int numCtx = cfg.getNumCtx();
        if (numCtx <= 0 || promptTokens < (numCtx * 4) / 5) {
            return;
        }
        String key = sessionId == null ? "-" : sessionId;
        if (contextWarned.putIfAbsent(key, Boolean.TRUE) == null) {
            log.warn("Prompt used {} of {} num_ctx tokens for model {}. Ollama silently "
                            + "discards anything beyond num_ctx, starting with the system prompt "
                            + "and tool schemas. Raise agent.llm.ollama.num-ctx (OLLAMA_NUM_CTX).",
                    promptTokens, numCtx, cfg.getModel());
        }
    }

    @Override
    public CompletionResult complete(String systemPrompt, List<ChatMessage> history, List<ToolSpec> tools, String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel());
        body.put("stream", false);
        body.put("messages", convertHistory(systemPrompt, history));
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", convertTools(tools));
        }
        // Without num_ctx Ollama applies its own default (measured: 2050 in the
        // bundled container, 4096 on host 0.12.11) and silently drops the overflow,
        // taking the system prompt and tool schemas with it. 0 means "leave it to
        // the server", so a deployment can set OLLAMA_CONTEXT_LENGTH instead.
        if (cfg.getNumCtx() > 0) {
            body.put("options", Map.of("num_ctx", cfg.getNumCtx()));
        }

        JsonNode response;
        try {
            response = com.example.agent.llm.LlmRetry.call(name(), () -> webClient.post()
                    .uri("/api/chat")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMinutes(10))
                    .block());
        } catch (Exception e) {
            metrics.recordLlmCall(name(), false, 0, 0);
            throw e;
        }

        CompletionResult result = parseResponse(response);
        warnIfNearContextLimit(result.usage().inputTokens(), sessionId);
        metrics.recordLlmCall(name(), true, result.usage().inputTokens(), result.usage().outputTokens());
        return result;
    }

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
                        calls.add(Map.of(
                                "function", Map.of(
                                        "name", tc.name(),
                                        "arguments", tc.arguments() == null ? Map.of() : tc.arguments()
                                )
                        ));
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
        if (response == null) {
            throw new IllegalStateException("Empty response from Ollama API");
        }
        JsonNode message = response.path("message");
        String text = message.path("content").asText("");

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode calls = message.path("tool_calls");
        if (calls.isArray()) {
            for (JsonNode call : calls) {
                String name = call.path("function").path("name").asText();
                JsonNode argNode = call.path("function").path("arguments");
                Map<String, Object> args;
                if (argNode.isObject()) {
                    args = mapper.convertValue(argNode, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                } else if (argNode.isTextual()) {
                    try {
                        args = mapper.readValue(argNode.asText(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        args = new HashMap<>();
                    }
                } else {
                    args = new HashMap<>();
                }
                // Ollama doesn't always return IDs — synthesise one.
                toolCalls.add(new ToolCall("call_" + UUID.randomUUID(), name, args));
            }
        }
        // Local models often ignore the structured tool_calls field and emit the
        // call as prose instead; recover those so the loop can still act on them,
        // and record which of the three things happened. A model that can never
        // produce a tool call otherwise fails invisibly: the turn returns text,
        // no error, and nothing done.
        ToolCallFormatObserver.Format format =
                toolCalls.isEmpty() ? ToolCallFormatObserver.Format.NONE
                                    : ToolCallFormatObserver.Format.STRUCTURED;
        if (toolCalls.isEmpty()) {
            TextToolCallParser.Result recovered = textToolCallParser.parse(text);
            if (!recovered.toolCalls().isEmpty()) {
                text = recovered.text();
                toolCalls = recovered.toolCalls();
                format = ToolCallFormatObserver.Format.RECOVERED;
                log.info("Recovered {} tool call(s) from assistant text for model {}. "
                                + "The model is not using Ollama's tool_calls field; recovery "
                                + "is best-effort and will not catch every format.",
                        toolCalls.size(), cfg.getModel());
            } else if (textToolCallParser.looksLikeAnUnrecoveredToolCall(text)) {
                log.warn("Model {} appears to have emitted a tool call as text that could "
                                + "not be parsed, so this turn will do nothing. Check the "
                                + "model's tool-call support — see docs/offline-docker-compose.md. "
                                + "First 200 chars: {}",
                        cfg.getModel(),
                        text.length() > 200 ? text.substring(0, 200) : text);
            }
        }
        formatObserver.record(format);
        metrics.recordToolCallFormat(name(), cfg.getModel(),
                format.name().toLowerCase(java.util.Locale.ROOT));

        // Ollama uses prompt_eval_count / eval_count at the top level.
        TokenUsage tokens = new TokenUsage(
                response.path("prompt_eval_count").asInt(0),
                response.path("eval_count").asInt(0));
        return new CompletionResult(ChatMessage.assistant(text, toolCalls), tokens);
    }
}
