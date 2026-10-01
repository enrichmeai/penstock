package com.example.agent.acp;

import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.Role;
import com.example.agent.model.TokenUsage;
import com.example.agent.model.ToolCall;
import com.example.agent.tools.ToolSpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic {@code LlmProvider} for {@link AcpPermissionIT}, selected the same way a real
 * provider is ({@code agent.llm.provider=permission-stub}), test-classpath only.
 *
 * <p>Replies with a single tool call for a known trigger phrase in the user's text, then — once
 * the matching TOOL result is the last message in history — a plain "done" to end the turn. One
 * trigger per scenario so each {@code session/prompt} call in the IT is self-contained.
 */
@Component
@ConditionalOnProperty(prefix = "agent.llm", name = "provider", havingValue = "permission-stub")
public class AcpPermissionStubLlmProvider implements LlmProvider {

    static final String RUN_SHELL = "run-shell";
    static final String RUN_POD_READ = "run-pod-read";
    static final String RUN_POD_WRITE = "run-pod-write";

    private final AtomicInteger callIds = new AtomicInteger();

    @Override
    public String name() { return "permission-stub"; }

    @Override
    public CompletionResult complete(String systemPrompt, List<ChatMessage> history, List<ToolSpec> tools, String sessionId) {
        if (!history.isEmpty() && history.get(history.size() - 1).role() == Role.TOOL) {
            return new CompletionResult(ChatMessage.assistantText("done"), new TokenUsage(1, 1));
        }
        String trigger = lastUserText(history);
        ToolCall call = toolCallFor(trigger);
        if (call == null) {
            return new CompletionResult(ChatMessage.assistantText("Nothing to do for: " + trigger), new TokenUsage(1, 1));
        }
        return new CompletionResult(ChatMessage.assistant(null, List.of(call)), new TokenUsage(1, 1));
    }

    private ToolCall toolCallFor(String trigger) {
        String id = "c" + callIds.incrementAndGet();
        return switch (trigger == null ? "" : trigger) {
            case RUN_SHELL -> new ToolCall(id, "shell", Map.of("command", "echo hi"));
            case RUN_POD_READ -> new ToolCall(id, "pod", Map.of("type", "read", "path", "/x"));
            case RUN_POD_WRITE -> new ToolCall(id, "pod", Map.of("type", "write", "path", "/x", "content", "y"));
            default -> null;
        };
    }

    private static String lastUserText(List<ChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).role() == Role.USER) {
                return history.get(i).text();
            }
        }
        return null;
    }
}
