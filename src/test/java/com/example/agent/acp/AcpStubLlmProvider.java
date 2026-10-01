package com.example.agent.acp;

import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.Role;
import com.example.agent.model.TokenUsage;
import com.example.agent.tools.ToolSpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic {@code LlmProvider} for {@link AcpStdioIT}, selected the same way a real
 * provider is — {@code agent.llm.provider=stub} — so it only exists on the test classpath
 * {@link AcpStdioIT} launches its child JVM with, never in the shipped jar (bootJar bundles
 * {@code src/main} only).
 *
 * <p>Replies with a fixed assistant message, except when the last user turn is exactly
 * {@link #SLEEP_TRIGGER}: it then sleeps long enough for {@link AcpStdioIT}'s cancel test to
 * send {@code session/cancel} while the "provider call" is in flight, mirroring a slow real
 * provider for that one scenario.
 */
@Component
@ConditionalOnProperty(prefix = "agent.llm", name = "provider", havingValue = "stub")
public class AcpStubLlmProvider implements LlmProvider {

    static final String SLEEP_TRIGGER = "sleep-for-cancel-test";
    static final String REPLY_TEXT = "Hello from the ACP stub.";

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public CompletionResult complete(String systemPrompt, List<ChatMessage> history, List<ToolSpec> tools, String sessionId) {
        if (SLEEP_TRIGGER.equals(lastUserText(history))) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return new CompletionResult(ChatMessage.assistantText(REPLY_TEXT), new TokenUsage(5, 5));
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
