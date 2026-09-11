package com.example.agent.service;

import com.example.agent.config.AgentProperties;
import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmCallContext;
import com.example.agent.llm.LlmFailureReason;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.*;
import com.example.agent.tools.ToolContext;
import com.example.agent.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Orchestrates the agent's tool-use loop:
 *
 *   user turn → LLM → (tool calls → execute → feed results) × N → final assistant text
 *
 * Supports both a synchronous {@link #chat} and a streaming {@link #chatStreaming}
 * variant. Every produced message is persisted through {@link SessionStore}
 * (only matters for the SQLite-backed store; the in-memory one no-ops).
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final LlmProvider llm;
    private final ToolRegistry tools;
    private final AgentProperties props;
    private final SessionStore sessionStore;
    private AuditLogger auditLogger;

    public AgentService(LlmProvider llm,
                        ToolRegistry tools,
                        AgentProperties props,
                        SessionStore sessionStore) {
        this.llm = llm;
        this.tools = tools;
        this.props = props;
        this.sessionStore = sessionStore;
    }

    @Autowired(required = false)
    public void setAuditLogger(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    /** Synchronous: runs the full loop, returns all new messages. */
    public List<ChatMessage> chat(Session session, String userInput) {
        return chat(session, userInput, TurnContext.none());
    }

    /**
     * Synchronous, with what the inbound request carried: its request ID (sent on every
     * outbound call this turn makes) and the caller's own bearer (for tools that act as
     * the signed-in user). Identity itself travels on the session.
     */
    public List<ChatMessage> chat(Session session, String userInput, TurnContext turn) {
        List<ChatMessage> produced = new ArrayList<>();
        runTurn(session, userInput, turn, produced::add, NO_TOKEN);
        return produced;
    }

    /** Streaming: invokes {@code onMessage} for every message as it's produced. */
    public void chatStreaming(Session session, String userInput, Consumer<ChatMessage> onMessage) {
        runTurn(session, userInput, TurnContext.none(), onMessage, NO_TOKEN);
    }

    /**
     * Streaming with per-token deltas. {@code onToken} is invoked for every text
     * chunk the provider emits during an in-flight assistant message; {@code onMessage}
     * still fires once per complete message (the existing contract, kept for client
     * reconciliation — see ADR 0003).
     */
    public void chatStreaming(Session session,
                              String userInput,
                              Consumer<ChatMessage> onMessage,
                              Consumer<String> onToken) {
        runTurn(session, userInput, TurnContext.none(), onMessage, onToken);
    }

    /** {@link #chatStreaming(Session, String, Consumer, Consumer)} with the inbound request's context. */
    public void chatStreaming(Session session,
                              String userInput,
                              TurnContext turn,
                              Consumer<ChatMessage> onMessage,
                              Consumer<String> onToken) {
        runTurn(session, userInput, turn, onMessage, onToken);
    }

    private static final Consumer<String> NO_TOKEN = s -> {};

    private void runTurn(Session session,
                         String userInput,
                         TurnContext turn,
                         Consumer<ChatMessage> emit,
                         Consumer<String> onToken) {
        // Everything downstream needs about the caller, resolved here once: the
        // provider gets identity and request ID (never the inbound bearer); tools
        // get the same plus the bearer, for the one mode that forwards it.
        LlmCallContext llmContext = new LlmCallContext(session.getUserId(), session.getId(), turn.requestId());
        ToolContext toolContext = new ToolContext(session.getUserId(), session.getId(), turn.requestId(), turn.bearer());

        ChatMessage userMsg = ChatMessage.user(userInput);
        appendAndEmit(session, userMsg, emit);

        boolean titleUpdated = false;
        if ("New session".equals(session.getTitle())) {
            session.setTitle(truncate(userInput, 60));
            titleUpdated = true;
        }

        int maxTurns = props.getLlm().getMaxTurnsPerRequest();
        long perRequestTotal = 0;

        for (int i = 0; i < maxTurns; i++) {
            log.debug("Agent iteration {}/{}", i + 1, maxTurns);

            // Check session-level token budget
            if (session.getTotalUsage().total() >= props.getLlm().getMaxTokensPerSession()) {
                long maxSessionTokens = props.getLlm().getMaxTokensPerSession();
                appendAndEmit(session, ChatMessage.assistantText(
                        "Budget exceeded for this session (limit: " + maxSessionTokens + " tokens). Start a new session to continue."), emit);
                if (titleUpdated) sessionStore.update(session);
                return;
            }

            // Use windowed history for the LLM call. Audit here — not in the
            // providers — so the event carries the session owner resolved on
            // the request thread; the SecurityContext is absent on SSE threads.
            List<ChatMessage> windowedHist = windowedHistory(session.getHistory());
            CompletionResult result;
            try {
                result = props.getLlm().isStreamingEnabled()
                        ? llm.completeStreaming(
                                props.getLlm().getSystemPrompt(),
                                windowedHist,
                                tools.specs(),
                                llmContext,
                                onToken)
                        : llm.complete(
                                props.getLlm().getSystemPrompt(),
                                windowedHist,
                                tools.specs(),
                                llmContext);
            } catch (RuntimeException | Error e) {
                // Audited with its reason: a gateway's refusal (budget, rate limit) is a
                // different row from a provider that fell over. The exception itself goes
                // up untouched — the REST and SSE layers type their answer from it.
                if (auditLogger != null) {
                    auditLogger.llmCall(session.getUserId(), session.getId(), llm.name(), 0, 0, false,
                            LlmFailureReason.of(e));
                }
                throw e;
            }
            if (auditLogger != null) {
                auditLogger.llmCall(session.getUserId(), session.getId(), llm.name(),
                        result.usage().inputTokens(), result.usage().outputTokens(), true);
            }
            session.addUsage(result.usage());
            perRequestTotal += result.usage().total();
            ChatMessage assistant = result.message();
            appendAndEmit(session, assistant, emit);

            // The pre-call check uses last-iteration's totals; this catches the
            // case where a single call's usage tips the session over budget.
            if (session.getTotalUsage().total() >= props.getLlm().getMaxTokensPerSession()) {
                long maxSessionTokens = props.getLlm().getMaxTokensPerSession();
                appendAndEmit(session, ChatMessage.assistantText(
                        "Budget exceeded for this session (limit: " + maxSessionTokens + " tokens). Start a new session to continue."), emit);
                if (titleUpdated) sessionStore.update(session);
                return;
            }

            if (!assistant.hasToolCalls()) {
                if (titleUpdated) sessionStore.update(session);
                return;
            }

            // Check per-request token budget after LLM call
            if (perRequestTotal >= props.getLlm().getMaxTokensPerRequest()) {
                long maxRequestTokens = props.getLlm().getMaxTokensPerRequest();
                appendAndEmit(session, ChatMessage.assistantText(
                        "Per-request token budget reached (limit: " + maxRequestTokens + " tokens). " +
                        "Send another message to continue — the history is kept and the next " +
                        "request starts with a fresh budget — or ask something narrower."), emit);
                if (titleUpdated) sessionStore.update(session);
                return;
            }

            List<ToolResult> results = new ArrayList<>();
            for (ToolCall call : assistant.toolCalls()) {
                log.info("LLM invoked tool: {} args={}", call.name(), call.arguments());
                results.add(tools.invoke(call, toolContext));
            }
            appendAndEmit(session, ChatMessage.tool(results), emit);
        }

        appendAndEmit(session, ChatMessage.assistantText(
                "Stopped: reached max turns (" + maxTurns + "). " +
                "The task may be incomplete. Send another message to continue — the " +
                "history is kept and the next request starts with a fresh turn and " +
                "token budget."), emit);
        if (titleUpdated) sessionStore.update(session);
    }

    private void appendAndEmit(Session session, ChatMessage msg, Consumer<ChatMessage> emit) {
        session.add(msg);
        sessionStore.appendMessage(session, msg);
        // Let consumer exceptions propagate. For SSE this means the agent loop
        // unwinds promptly when the client disconnects, instead of finishing the
        // turn into a closed stream.
        emit.accept(msg);
    }

    public Session createSession() { return sessionStore.create(); }

    public Session requireSession(String id) {
        return sessionStore.get(id).orElseThrow(
                () -> new com.example.agent.controller.SessionNotFoundException(id));
    }

    /**
     * Apply context windowing policy to history before sending to LLM.
     * If policy is "last-n", keep only the last N messages, but ensure we never drop
     * a TOOL message that's the immediate response to an ASSISTANT tool call
     * (to avoid breaking tool_use blocks).
     */
    private List<ChatMessage> windowedHistory(List<ChatMessage> history) {
        if (!"last-n".equals(props.getLlm().getContext().getPolicy())) {
            return history;
        }

        int lastN = props.getLlm().getContext().getLastN();
        if (history.size() <= lastN) {
            return history;
        }

        // Keep the last N messages
        List<ChatMessage> windowed = new ArrayList<>(history.subList(history.size() - lastN, history.size()));

        // Ensure the first kept message doesn't break a tool_use pattern:
        // - Never start with a TOOL message (that would be orphaned from its ASSISTANT)
        // - Never start with an ASSISTANT that has toolCalls but no TOOL response
        int dropIdx = 0;
        while (dropIdx < windowed.size() - 1) {
            ChatMessage first = windowed.get(0);
            if (first.role() == Role.TOOL) {
                // Drop the orphaned TOOL message
                windowed.remove(0);
                dropIdx++;
            } else if (first.role() == Role.ASSISTANT && first.hasToolCalls()) {
                // Check if the next message is a TOOL response
                if (dropIdx + 1 < windowed.size() && windowed.get(1).role() == Role.TOOL) {
                    // Good, keep it
                    break;
                } else {
                    // Next message is not TOOL or doesn't exist, drop this ASSISTANT
                    windowed.remove(0);
                    dropIdx++;
                }
            } else {
                // SYSTEM, USER, or ASSISTANT without toolCalls — all fine as first message
                break;
            }
        }

        return windowed;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.strip();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
