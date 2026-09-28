package com.example.agent.service;

import com.example.agent.config.CurrentUser;
import com.example.agent.model.ToolOutcome;
import com.example.agent.service.persistence.AuditEventEntity;
import com.example.agent.service.persistence.AuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logs audit events (tool calls and LLM calls) asynchronously to the database.
 *
 * - If no AuditEventRepository is available (e.g., in memory-only mode or tests),
 *   all methods silently no-op with a debug log.
 * - All writes are async (@Async) so they never block the request.
 * - Catches and logs exceptions to ensure audit failures never break the agent.
 * - The acting user is an explicit parameter: @Async methods run on a pooled
 *   thread with no SecurityContext, so the principal must be resolved on the
 *   request thread and passed in — never read from thread-local state here.
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    private final AuditEventRepository repo;
    private final ObjectMapper mapper;

    @Autowired
    public AuditLogger(
            @Autowired(required = false) AuditEventRepository repo,
            ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    /** Keys of a {@code tool_call} event's {@code detail}. */
    private static final String TOOL_DETAIL_TOOL = "tool";
    private static final String TOOL_DETAIL_ARGS = "args";
    private static final String TOOL_DETAIL_OUTCOME = "outcome";
    private static final String TOOL_DETAIL_OK = "ok";
    private static final String TOOL_DETAIL_CONTENT_BYTES = "contentBytes";

    /**
     * Log a tool call event.
     *
     * @param userId acting user, resolved on the request thread (null/blank → "anonymous")
     * @param sessionId ID of the session (may be null)
     * @param toolName name of the tool invoked
     * @param args tool arguments
     * @param outcome how the call ended. {@code detail.outcome} ({@code OK|REFUSED|ERROR}) is the
     *                field of record; {@code detail.ok} is kept for older readers and is true only
     *                for {@code OK} — a refusal is the far system's decision, not a success
     * @param contentBytes size of the tool output
     */
    @Async
    public void toolCall(String userId, String sessionId, String toolName, Map<String, Object> args,
                         ToolOutcome outcome, int contentBytes) {
        if (repo == null) {
            log.debug("Audit repository not available; skipping tool_call event");
            return;
        }

        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put(TOOL_DETAIL_TOOL, toolName);
            detail.put(TOOL_DETAIL_ARGS, args);
            detail.put(TOOL_DETAIL_OUTCOME, outcome);
            detail.put(TOOL_DETAIL_OK, outcome == ToolOutcome.OK);
            detail.put(TOOL_DETAIL_CONTENT_BYTES, contentBytes);

            String detailJson = mapper.writeValueAsString(detail);
            AuditEventEntity event = new AuditEventEntity(
                    Instant.now(),
                    normalise(userId),
                    sessionId,
                    "tool_call",
                    detailJson
            );
            repo.save(event);
        } catch (Exception e) {
            log.warn("Failed to log tool_call event", e);
        }
    }

    /**
     * Log an LLM call event.
     *
     * @param userId acting user, resolved on the request thread (null/blank → "anonymous")
     * @param sessionId ID of the session (may be null)
     * @param provider name of the LLM provider
     * @param inputTokens number of input tokens
     * @param outputTokens number of output tokens
     * @param ok true if the LLM call succeeded
     */
    @Async
    public void llmCall(String userId, String sessionId, String provider, int inputTokens, int outputTokens, boolean ok) {
        if (repo == null) {
            log.debug("Audit repository not available; skipping llm_call event");
            return;
        }

        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("provider", provider);
            detail.put("inputTokens", inputTokens);
            detail.put("outputTokens", outputTokens);
            detail.put("ok", ok);

            String detailJson = mapper.writeValueAsString(detail);
            AuditEventEntity event = new AuditEventEntity(
                    Instant.now(),
                    normalise(userId),
                    sessionId,
                    "llm_call",
                    detailJson
            );
            repo.save(event);
        } catch (Exception e) {
            log.warn("Failed to log llm_call event", e);
        }
    }

    private static String normalise(String userId) {
        return (userId == null || userId.isBlank()) ? CurrentUser.ANONYMOUS : userId;
    }
}
