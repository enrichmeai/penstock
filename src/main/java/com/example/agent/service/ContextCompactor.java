package com.example.agent.service;

import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmCallContext;
import com.example.agent.llm.LlmProvider;
import com.example.agent.mcp.CredentialShape;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.Role;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Folds the older part of a turn's history into one summary (#173), so a long task is not
 * stopped by the per-request token budget while the model still sees the newest messages
 * verbatim. The summary travels in the system prompt; the stored session history is never
 * changed. Sizes are estimated at four characters per token, which is enough to decide when to
 * fold, and the provider's reported usage stays the budget's measure.
 */
final class ContextCompactor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);

    /** First words of the summary call's system prompt; tests recognise the call by them. */
    static final String SUMMARY_INSTRUCTION = "You summarise the earlier part of an agent's working session";

    /** Heading of the summary block appended to the turn's system prompt. */
    static final String SUMMARY_HEADING = "## Earlier in this session (summarised)";

    private static final String SUMMARY_SYSTEM = SUMMARY_INSTRUCTION + """
             so the agent can continue the task with only this summary and the latest messages.
            Keep: the task as asked; files read or changed (paths); decisions made and why; \
            sub-tasks still open; tool errors not yet resolved. Drop pleasantries and repetition. \
            Never copy a password, token, key or other secret value. Plain text, at most 400 words.""";

    private static final int MAX_CHARS_PER_ITEM = 4_000;
    private static final String REDACTED = "[a line that looked like a credential was removed]";

    /** The model's view of the history after any folding. */
    record View(String summary, int foldedUpTo) {
        static final View NONE = new View(null, 0);
    }

    /**
     * What one compaction attempt did. {@code call} is the summary call's result, null when it
     * threw; {@code view} is unchanged when the summary call failed or came back blank, so the
     * request goes out unfolded rather than with the older history silently dropped.
     */
    record Folding(View view, int folded, long tokensBefore, long tokensAfter, CompletionResult call, boolean failed) {
    }

    private ContextCompactor() {
    }

    static long estimateTokens(String systemPrompt, List<ChatMessage> messages) {
        return estimateTokens(systemPrompt, messages, 0);
    }

    /** As above, plus {@code extraChars} sent on every call (the tool schemas). */
    static long estimateTokens(String systemPrompt, List<ChatMessage> messages, long extraChars) {
        long chars = extraChars + (systemPrompt == null ? 0 : systemPrompt.length());
        for (ChatMessage m : messages) {
            chars += m.text() == null ? 0 : m.text().length();
            for (ToolCall c : m.toolCalls()) {
                chars += c.name().length() + String.valueOf(c.arguments()).length();
            }
            for (ToolResult r : m.toolResults()) {
                chars += r.content() == null ? 0 : r.content().length();
            }
        }
        return chars / 4;
    }

    static String systemPromptWith(String systemPrompt, View view) {
        if (view.summary() == null) {
            return systemPrompt;
        }
        return systemPrompt + "\n\n" + SUMMARY_HEADING + "\n" + view.summary();
    }

    /**
     * Folds more of {@code history} into the summary when the request would exceed
     * {@code threshold} tokens, keeping at least {@code keepRecent} newest messages verbatim and
     * never starting the kept window on a tool result. Returns null when no folding is needed.
     */
    static Folding foldIfNeeded(LlmProvider llm, LlmCallContext context, String systemPrompt,
                                List<ChatMessage> history, View current, long threshold, int keepRecent,
                                long toolSpecChars) {
        List<ChatMessage> sent = history.subList(current.foldedUpTo(), history.size());
        long before = estimateTokens(systemPromptWith(systemPrompt, current), sent, toolSpecChars);
        if (before <= threshold) {
            return null;
        }
        int boundary = Math.max(current.foldedUpTo(), history.size() - keepRecent);
        // A tool result must stay with the assistant message that asked for it.
        while (boundary > current.foldedUpTo() && history.get(boundary).role() == Role.TOOL) {
            boundary--;
        }
        // An assistant tool call whose results never arrived (a turn stopped on its budget) is
        // folded rather than sent, since a provider may reject a call with no result.
        while (boundary < history.size() - 1
                && history.get(boundary).role() == Role.ASSISTANT && history.get(boundary).hasToolCalls()
                && history.get(boundary + 1).role() != Role.TOOL) {
            boundary++;
        }
        if (boundary <= current.foldedUpTo()) {
            log.warn("Request estimated at {} tokens is over the {}-token compaction threshold, but only the {} newest "
                    + "messages remain and they are kept verbatim; sending it unfolded", before, threshold, sent.size());
            return null;
        }
        String transcript = render(current.summary(), history.subList(current.foldedUpTo(), boundary));
        CompletionResult call;
        try {
            call = llm.complete(SUMMARY_SYSTEM, List.of(ChatMessage.user(transcript)), List.of(), context);
        } catch (RuntimeException e) {
            log.warn("Summary call for compaction failed; sending the request unfolded", e);
            return new Folding(current, 0, before, before, null, true);
        }
        String text = call == null || call.message() == null ? null : call.message().text();
        if (text == null || text.isBlank()) {
            log.warn("Summary call for compaction returned no text; sending the request unfolded");
            return new Folding(current, 0, before, before, call, true);
        }
        View next = new View(redact(text), boundary);
        long after = estimateTokens(systemPromptWith(systemPrompt, next), history.subList(boundary, history.size()), toolSpecChars);
        return new Folding(next, boundary - current.foldedUpTo(), before, after, call, false);
    }

    private static String render(String previousSummary, List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        if (previousSummary != null) {
            sb.append("Summary so far:\n").append(previousSummary).append("\n\nThen:\n");
        }
        for (ChatMessage m : messages) {
            switch (m.role()) {
                case USER -> sb.append("User: ").append(clip(m.text())).append('\n');
                case ASSISTANT -> {
                    if (m.text() != null && !m.text().isBlank()) {
                        sb.append("Assistant: ").append(clip(m.text())).append('\n');
                    }
                    for (ToolCall c : m.toolCalls()) {
                        sb.append("Assistant called ").append(c.name()).append(' ').append(clip(String.valueOf(c.arguments()))).append('\n');
                    }
                }
                case TOOL -> {
                    for (ToolResult r : m.toolResults()) {
                        sb.append(r.isError() ? "Tool error: " : "Tool result: ").append(clip(r.content())).append('\n');
                    }
                }
                default -> { }
            }
        }
        return sb.toString();
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_CHARS_PER_ITEM ? s : s.substring(0, MAX_CHARS_PER_ITEM) + " …[truncated]";
    }

    private static String redact(String summary) {
        if (summary == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : summary.split("\n", -1)) {
            sb.append(CredentialShape.matches(line) ? REDACTED : line).append('\n');
        }
        return sb.toString().strip();
    }
}
