package com.example.agent.service;

import com.example.agent.config.CurrentUser;
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

    /**
     * Log a tool call event.
     *
     * @param userId acting user, resolved on the request thread (null/blank → "anonymous")
     * @param sessionId ID of the session (may be null)
     * @param toolName name of the tool invoked
     * @param args tool arguments
     * @param ok true if the tool succeeded
     * @param contentBytes size of the tool output
     */
    @Async
    public void toolCall(String userId, String sessionId, String toolName, Map<String, Object> args, boolean ok, int contentBytes) {
        if (repo == null) {
            log.debug("Audit repository not available; skipping tool_call event");
            return;
        }

        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("tool", toolName);
            detail.put("args", args);
            detail.put("ok", ok);
            detail.put("contentBytes", contentBytes);

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

    /**
     * Records that a fact card joined a turn's context block (#88), as {@code fact.loaded}
     * with {@code factId}, {@code status} and {@code confidence}; also an INFO line.
     */
    @Async
    public void factLoaded(String userId, String sessionId, String factId, String status, double confidence) {
        String user = normalise(userId);
        log.info("fact.loaded user={} session={} factId={} status={} confidence={}", user, sessionId, factId, status, confidence);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("factId", factId);
        detail.put("status", status);
        detail.put("confidence", confidence);
        saveMemoryEvent(user, sessionId, "fact.loaded", detail);
    }

    /**
     * Records that an episode card joined a turn's context block (#88), as
     * {@code episode.loaded} with {@code episodeId} and {@code date}; also an INFO line.
     */
    @Async
    public void episodeLoaded(String userId, String sessionId, String episodeId, String date) {
        String user = normalise(userId);
        log.info("episode.loaded user={} session={} episodeId={} date={}", user, sessionId, episodeId, date);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("episodeId", episodeId);
        detail.put("date", date);
        saveMemoryEvent(user, sessionId, "episode.loaded", detail);
    }

    /**
     * Records which memory section a turn drew from or was refused (#112), as
     * {@code section.loaded} or {@code section.refused} with {@code section} and {@code reason};
     * also an INFO line.
     */
    @Async
    public void sectionDecided(String userId, String sessionId, String section, boolean loaded, String reason) {
        String user = normalise(userId);
        String type = loaded ? "section.loaded" : "section.refused";
        log.info("{} user={} session={} section={} reason={}", type, user, sessionId, section, reason);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("section", section);
        detail.put("reason", reason);
        saveMemoryEvent(user, sessionId, type, detail);
    }

    /**
     * Records a call to one of the MCP server's tools (#116), as {@code mcp.<tool>} with the project
     * and its outcome ({@code ok}, {@code empty}, {@code refused} or {@code error}); also an INFO line.
     */
    @Async
    public void mcpCalled(String userId, String sessionId, String tool, String project, String outcome) {
        String user = normalise(userId);
        String type = "mcp." + tool;
        log.info("{} user={} session={} project={} outcome={}", type, user, sessionId, project, outcome);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("tool", tool);
        detail.put("project", project);
        detail.put("outcome", outcome);
        detail.put("ok", !"refused".equals(outcome) && !"error".equals(outcome));
        saveMemoryEvent(user, sessionId, type, detail);
    }

    private void saveMemoryEvent(String user, String sessionId, String eventType, Map<String, Object> detail) {
        if (repo == null) {
            log.debug("Audit repository not available; skipping {} event", eventType);
            return;
        }
        try {
            repo.save(new AuditEventEntity(Instant.now(), user, sessionId, eventType, mapper.writeValueAsString(detail)));
        } catch (Exception e) {
            log.warn("Failed to log {} event", eventType, e);
        }
    }

    /**
     * Log a pattern-retrieval event: {@code ContextAssembler} loaded this pattern into the
     * turn's system prompt. Logged at INFO regardless of whether a repository is available —
     * in {@code memory} storage mode (ACP's only supported mode) there is no audit table, so
     * the log line is the only trail of what was loaded.
     *
     * @param userId acting user, resolved on the request thread (null/blank → "anonymous")
     * @param sessionId ID of the session (may be null)
     * @param patternId id of the pattern loaded, from its manifest.yaml
     * @param version version of the pattern loaded, from its manifest.yaml
     */
    @Async
    public void patternLoaded(String userId, String sessionId, String patternId, String version) {
        String user = normalise(userId);
        log.info("pattern.loaded user={} session={} patternId={} version={}", user, sessionId, patternId, version);

        if (repo == null) {
            log.debug("Audit repository not available; skipping pattern.loaded event");
            return;
        }

        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("patternId", patternId);
            detail.put("version", version);

            String detailJson = mapper.writeValueAsString(detail);
            AuditEventEntity event = new AuditEventEntity(
                    Instant.now(),
                    user,
                    sessionId,
                    "pattern.loaded",
                    detailJson
            );
            repo.save(event);
        } catch (Exception e) {
            log.warn("Failed to log pattern.loaded event", e);
        }
    }

    private static String normalise(String userId) {
        return (userId == null || userId.isBlank()) ? CurrentUser.ANONYMOUS : userId;
    }
}
