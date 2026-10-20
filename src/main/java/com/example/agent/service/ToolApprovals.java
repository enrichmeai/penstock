package com.example.agent.service;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolCall;
import com.example.agent.tools.ToolContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The web and REST {@link ToolApprover} (#179): a tool call under policy {@code ask} waits here,
 * on the agent loop's thread, until {@code POST /api/sessions/{id}/approvals/{callId}} answers it
 * or {@code agent.tools.ask-timeout-seconds} passes. Pending calls live in memory only, for as
 * long as the turn waits; nothing is remembered between calls, so every {@code ask} asks.
 *
 * <p>Ownership is the controller's job: it resolves the session with
 * {@code AgentService#requireSession} (another user's session is a 404) before calling
 * {@link #answer} or {@link #pending}.
 */
@Component
public class ToolApprovals implements ToolApprover {

    private record Waiting(PendingApproval pending, CompletableFuture<Answer> answer) {}

    private final Map<String, Waiting> waiting = new ConcurrentHashMap<>();
    private final AgentProperties props;

    public ToolApprovals(AgentProperties props) {
        this.props = props;
    }

    @Override
    public Answer ask(ToolContext context, ToolCall call, Consumer<PendingApproval> announce) {
        int timeoutSeconds = props.getTools().getAskTimeoutSeconds();
        PendingApproval pending = new PendingApproval(context.sessionId(), call.id(), call.name(),
                call.arguments() == null ? Map.of() : call.arguments(),
                Instant.now().plusSeconds(timeoutSeconds));
        String key = key(context.sessionId(), call.id());
        Waiting w = new Waiting(pending, new CompletableFuture<>());
        if (waiting.putIfAbsent(key, w) != null) {
            // The same call id asked twice at once: never let the second ride on the first's answer.
            return new Answer(false, null, false);
        }
        try {
            try {
                announce.accept(pending);
            } catch (RuntimeException e) {
                // Nobody can see the question (the stream is gone): deny, never run unasked.
                return new Answer(false, null, false);
            }
            return w.answer().get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return lateOrTimeout(key, w);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return lateOrTimeout(key, w);
        } catch (ExecutionException e) {
            return new Answer(false, null, false);
        } finally {
            waiting.remove(key, w);
        }
    }

    /**
     * The wait ran out. If an answer took the entry first, it was accepted (and the caller got
     * 204), so honour it; otherwise this is a timeout and no later answer can land.
     */
    private Answer lateOrTimeout(String key, Waiting w) {
        if (waiting.remove(key, w)) {
            return Answer.timeout();
        }
        Answer answered = w.answer().getNow(null);
        return answered != null ? answered : Answer.timeout();
    }

    /**
     * Answers one pending call.
     *
     * @return false when no call with this id is waiting in this session (already answered,
     *         timed out, or never asked)
     */
    public boolean answer(String sessionId, String callId, boolean approve, String answeredBy) {
        // Taking the entry out first means an answer either reaches the waiting turn or gets
        // false: never a 204 for a call that has already timed out.
        Waiting w = waiting.remove(key(sessionId, callId));
        return w != null && w.answer().complete(new Answer(approve, answeredBy, false));
    }

    /** The calls waiting in one session, oldest first. */
    public List<PendingApproval> pending(String sessionId) {
        return waiting.values().stream()
                .map(Waiting::pending)
                .filter(p -> p.sessionId() != null && p.sessionId().equals(sessionId))
                .sorted(Comparator.comparing(PendingApproval::expiresAt))
                .toList();
    }

    private static String key(String sessionId, String callId) {
        return sessionId + "\u0000" + callId;
    }
}
