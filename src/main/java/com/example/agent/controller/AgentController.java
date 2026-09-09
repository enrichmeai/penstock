package com.example.agent.controller;

import com.example.agent.config.AgentMetrics;
import com.example.agent.config.CurrentUser;
import com.example.agent.config.RequestIdFilter;
import com.example.agent.config.SseEmitterRegistry;
import com.example.agent.controller.dto.ChatRequest;
import com.example.agent.controller.dto.ChatResponse;
import com.example.agent.controller.dto.SessionSummary;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.Session;
import com.example.agent.service.AgentService;
import com.example.agent.service.SessionStore;
import com.example.agent.service.TurnContext;
import com.example.agent.tools.ToolRegistry;
import com.example.agent.tools.ToolSpec;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST API for the coding agent.
 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentService agent;
    private final SessionStore sessions;
    private final ToolRegistry tools;
    private final LlmProvider provider;
    private final AsyncTaskExecutor streamExecutor;
    private final AgentMetrics metrics;
    private final SseEmitterRegistry sseRegistry;
    private final CurrentUser currentUser;

    public AgentController(AgentService agent,
                           SessionStore sessions,
                           ToolRegistry tools,
                           LlmProvider provider,
                           @Qualifier("sseTaskExecutor") AsyncTaskExecutor streamExecutor,
                           AgentMetrics metrics,
                           SseEmitterRegistry sseRegistry,
                           CurrentUser currentUser) {
        this.agent = agent;
        this.sessions = sessions;
        this.tools = tools;
        this.provider = provider;
        this.streamExecutor = streamExecutor;
        this.metrics = metrics;
        this.sseRegistry = sseRegistry;
        this.currentUser = currentUser;
    }

    /**
     * What this request carried that the turn will need after it leaves this thread: the
     * request ID the filter resolved, and the bearer the caller authenticated with, if
     * any. Read here, on the request thread, and passed explicitly — the agent loop and
     * the SSE executor cannot see either.
     */
    private TurnContext turnContext(String requestId) {
        return new TurnContext(requestId, currentUser.bearer());
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "provider", provider.name(),
                "tools", tools.specs().stream().map(ToolSpec::name).toList()
        );
    }

    @GetMapping("/tools")
    public List<ToolSpec> tools() {
        return tools.specs();
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest req,
                             @RequestAttribute(name = RequestIdFilter.REQUEST_ID_ATTRIBUTE, required = false)
                             String requestId) {
        Session session = req.sessionId() == null || req.sessionId().isBlank()
                ? agent.createSession()
                : agent.requireSession(req.sessionId());

        List<ChatMessage> produced = agent.chat(session, req.message(), turnContext(requestId));
        return new ChatResponse(
                session.getId(),
                session.getTitle(),
                produced,
                session.getHistory(),
                session.getTotalUsage()
        );
    }

    /**
     * Streaming variant of /chat. Emits one SSE event per produced message:
     *
     *   event: session    data: { "sessionId":"...", "title":"..." }
     *   event: message    data: { ...ChatMessage JSON... }
     *   event: done       data: { "ok": true }
     *   event: error      data: { "error": "..." }
     *
     * Client consumes with fetch() + ReadableStream (EventSource can't do POST).
     */
    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest req,
                                 @RequestAttribute(name = RequestIdFilter.REQUEST_ID_ATTRIBUTE, required = false)
                                 String requestId) {
        SseEmitter emitter = sseRegistry.register(new SseEmitter(10 * 60 * 1000L));   // 10 min

        Session session;
        try {
            session = req.sessionId() == null || req.sessionId().isBlank()
                    ? agent.createSession()
                    : agent.requireSession(req.sessionId());
        } catch (RuntimeException ex) {
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("error", ex.getMessage())));
                emitter.complete();
            } catch (IOException ignore) {}
            return emitter;
        }

        final Session captured = session;
        // The task runs on a pooled thread with no request context: carry the
        // MDC (requestId/userId/sessionId) over so the stream's logs stay
        // attributable. Identity for persistence/audit travels via the Session;
        // the request ID and the caller's bearer travel via the TurnContext.
        final TurnContext turn = turnContext(requestId);
        final Map<String, String> mdc = MDC.getCopyOfContextMap();
        streamExecutor.execute(() -> {
            if (mdc != null) MDC.setContextMap(mdc);
            metrics.sseStreamStarted();
            try {
                emitter.send(SseEmitter.event().name("session").data(sessionPayload(captured)));

                agent.chatStreaming(captured, req.message(), turn,
                        msg -> {
                            try {
                                emitter.send(SseEmitter.event().name("message").data(msg));
                            } catch (IOException ioe) {
                                throw new RuntimeException(ioe);
                            }
                        },
                        delta -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(Map.of("text", delta)));
                            } catch (IOException ioe) {
                                throw new RuntimeException(ioe);
                            }
                        });

                emitter.send(SseEmitter.event().name("session").data(sessionPayload(captured)));
                emitter.send(SseEmitter.event().name("done").data(Map.of("ok", true)));
                emitter.complete();
            } catch (Exception e) {
                log.warn("SSE stream failed", e);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("error", e.getMessage() == null ? e.toString() : e.getMessage())));
                } catch (IOException ignore) {}
                emitter.completeWithError(e);
            } finally {
                metrics.sseStreamFinished();
                MDC.clear();   // pooled thread — don't leak this stream's context
            }
        });

        return emitter;
    }

    @GetMapping("/sessions")
    public List<SessionSummary> list() {
        return sessions.list().stream().map(SessionSummary::of).toList();
    }

    /**
     * Search the caller's own sessions by title or message text (case-insensitive).
     * Cross-user matches are never returned — empty array is the worst case.
     */
    @GetMapping("/sessions/search")
    public List<SessionSummary> search(@RequestParam("q") String q,
                                       @RequestParam(value = "limit", defaultValue = "20") int limit) {
        if (q == null || q.isBlank()) {
            throw new IllegalArgumentException("Query parameter 'q' must not be blank.");
        }
        int capped = Math.min(Math.max(1, limit), 100);
        return sessions.search(q.trim(), capped);
    }

    @PostMapping("/sessions")
    public SessionSummary create() {
        return SessionSummary.of(sessions.create());
    }

    @GetMapping("/sessions/{id}")
    public ChatResponse get(@PathVariable String id) {
        Session s = agent.requireSession(id);
        return new ChatResponse(s.getId(), s.getTitle(), List.of(), s.getHistory(), s.getTotalUsage());
    }

    @GetMapping("/sessions/{id}/usage")
    public Map<String, Object> usage(@PathVariable String id) {
        Session s = agent.requireSession(id);
        return Map.of(
                "sessionId", s.getId(),
                "inputTokens", s.getTotalUsage().inputTokens(),
                "outputTokens", s.getTotalUsage().outputTokens(),
                "total", s.getTotalUsage().total()
        );
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        // Ownership check first — cross-user or unknown → 404.
        agent.requireSession(id);
        sessions.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static Map<String, Object> sessionPayload(Session s) {
        return Map.of(
                "sessionId", s.getId(),
                "title", s.getTitle(),
                "usage", Map.of(
                        "inputTokens",  s.getTotalUsage().inputTokens(),
                        "outputTokens", s.getTotalUsage().outputTokens(),
                        "total",        s.getTotalUsage().total()
                ));
    }
}
