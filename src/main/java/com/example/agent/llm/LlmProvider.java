package com.example.agent.llm;

import com.example.agent.model.ChatMessage;
import com.example.agent.tools.ToolSpec;

import java.util.List;
import java.util.function.Consumer;

/**
 * Provider-agnostic interface for chatting with an LLM.
 *
 * Implementations translate between our internal model ({@link ChatMessage},
 * {@link com.example.agent.model.ToolCall}) and the provider's wire format.
 *
 * <p>Two entry points exist for each call. The {@code sessionId} forms are the
 * original contract and remain what stubs and simple providers implement. The
 * {@link LlmCallContext} forms carry the acting user and the inbound request ID
 * as well; the agent loop calls these, and their defaults delegate to the
 * {@code sessionId} forms so a provider that has no use for the extra context
 * need not know they exist. A provider that authenticates per user, or tags its
 * outbound calls with the request ID, overrides the context forms.
 */
public interface LlmProvider {

    /** Human-readable identifier: "anthropic", "openai", "ollama", "copilot". */
    String name();

    /**
     * Send the full conversation to the model and return the assistant's next
     * message along with the token usage reported by the provider.
     *
     * The assistant message may contain text, tool calls, or both.
     *
     * @param sessionId id of the current session (used for audit logs); may be {@code null}
     *                  if the call is not associated with a session.
     */
    CompletionResult complete(String systemPrompt, List<ChatMessage> history, List<ToolSpec> tools, String sessionId);

    /**
     * Convenience overload for callers (mostly tests) that don't have a session id.
     */
    default CompletionResult complete(String systemPrompt, List<ChatMessage> history, List<ToolSpec> tools) {
        return complete(systemPrompt, history, tools, (String) null);
    }

    /**
     * {@link #complete(String, List, List, String)} with the full call context. The agent
     * loop uses this form; the default forwards the session id only.
     */
    default CompletionResult complete(String systemPrompt,
                                      List<ChatMessage> history,
                                      List<ToolSpec> tools,
                                      LlmCallContext context) {
        return complete(systemPrompt, history, tools, context.sessionId());
    }

    /**
     * Streaming variant of {@link #complete}. Implementations invoke {@code onToken}
     * once per upstream chunk (a few characters of assistant text). Tool-call
     * arguments are NOT streamed — they appear only in the returned message once
     * the upstream tool-call block closes.
     *
     * Default implementation delegates to {@link #complete} and never invokes the
     * consumer. Providers without streaming support inherit this safely.
     */
    default CompletionResult completeStreaming(String systemPrompt,
                                               List<ChatMessage> history,
                                               List<ToolSpec> tools,
                                               String sessionId,
                                               Consumer<String> onToken) {
        return complete(systemPrompt, history, tools, sessionId);
    }

    /**
     * {@link #completeStreaming(String, List, List, String, Consumer)} with the full call
     * context. The agent loop uses this form; the default forwards the session id only.
     */
    default CompletionResult completeStreaming(String systemPrompt,
                                               List<ChatMessage> history,
                                               List<ToolSpec> tools,
                                               LlmCallContext context,
                                               Consumer<String> onToken) {
        return completeStreaming(systemPrompt, history, tools, context.sessionId(), onToken);
    }
}
