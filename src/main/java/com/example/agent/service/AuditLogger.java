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
     * {@code episode.loaded} with {@code episodeId}, {@code date} and {@code reason}
     * ({@code latest}, or {@code provenance} when a loaded fact brought it, #152); also an INFO line.
     */
    @Async
    public void episodeLoaded(String userId, String sessionId, String episodeId, String date, String reason) {
        String user = normalise(userId);
        log.info("episode.loaded user={} session={} episodeId={} date={} reason={}", user, sessionId, episodeId, date, reason);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("episodeId", episodeId);
        detail.put("date", date);
        detail.put("reason", reason);
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
     * Records a tool-policy decision (#179) as {@code tool.permission} with the tool, the call id,
     * the outcome ({@code denied} by policy, {@code asked}, then {@code allowed}, {@code denied} or
     * {@code timed-out}) and who answered ({@code policy} for a standing deny, the user's name for
     * an answer, null for a timeout); also an INFO line. A call its policy allows outright writes
     * no event here; its {@code tool_call} event is the record.
     */
    @Async
    public void toolPermission(String userId, String sessionId, String tool, String callId, String outcome, String answeredBy) {
        String user = normalise(userId);
        log.info("tool.permission user={} session={} tool={} call={} outcome={} answeredBy={}",
                user, sessionId, tool, callId, outcome, answeredBy);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("tool", tool);
        detail.put("callId", callId);
        detail.put("outcome", outcome);
        detail.put("answeredBy", answeredBy);
        saveMemoryEvent(user, sessionId, "tool.permission", detail);
    }

    /**
     * Records one {@code web_fetch} that went out (#176) as {@code web.fetched}: the URL as
     * requested, the final URL after redirects, the HTTP status and the bytes read. This is the
     * owner's record of exactly what left the machine.
     */
    @Async
    public void webFetched(String userId, String sessionId, String url, String finalUrl, int status, int bytes) {
        String user = normalise(userId);
        log.info("web.fetched user={} session={} url={} final={} status={} bytes={}", user, sessionId, url, finalUrl, status, bytes);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("url", url);
        detail.put("finalUrl", finalUrl);
        detail.put("status", status);
        detail.put("bytes", bytes);
        saveMemoryEvent(user, sessionId, "web.fetched", detail);
    }

    /**
     * Records a {@code web_fetch} refused before or during the request (#176) as {@code web.refused}:
     * the host and the reason. The URL itself is not recorded, since a refused URL may be the very
     * credential the guard stopped.
     */
    @Async
    public void webRefused(String userId, String sessionId, String host, String reason) {
        String user = normalise(userId);
        log.info("web.refused user={} session={} host={} reason={}", user, sessionId, host, reason);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("host", host);
        detail.put("reason", reason);
        saveMemoryEvent(user, sessionId, "web.refused", detail);
    }

    /**
     * Records one run of an owner hook (#184) as {@code hook.ran}: the event ({@code pre-tool},
     * {@code post-tool}, {@code turn-start}, {@code turn-end}), the hook's index in its list, the
     * tool (tool events only), the outcome ({@code allow}, {@code deny}, {@code pass}, {@code note},
     * {@code error} or {@code timeout}) and how long it took.
     */
    @Async
    public void hookRan(String userId, String sessionId, String event, int index, String tool, String outcome, long durationMs) {
        String user = normalise(userId);
        log.info("hook.ran user={} session={} event={} index={} tool={} outcome={} ms={}", user, sessionId, event, index, tool, outcome, durationMs);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("event", event);
        detail.put("index", index);
        detail.put("tool", tool);
        detail.put("outcome", outcome);
        detail.put("durationMs", durationMs);
        saveMemoryEvent(user, sessionId, "hook.ran", detail);
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

    /**
     * Records that a turn's older messages were folded into a summary before a model call (#173),
     * as {@code context.compacted} with how many messages were folded, the estimated request size
     * before and after, and the provider that wrote the summary; also an INFO line. The stored
     * history is unchanged by a compaction: only what the model was sent.
     */
    @Async
    public void contextCompacted(String userId, String sessionId, int folded,
                                 long tokensBefore, long tokensAfter, String provider) {
        String user = normalise(userId);
        log.info("context.compacted user={} session={} folded={} tokensBefore={} tokensAfter={} provider={}",
                user, sessionId, folded, tokensBefore, tokensAfter, provider);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("folded", folded);
        detail.put("tokensBefore", tokensBefore);
        detail.put("tokensAfter", tokensAfter);
        detail.put("provider", provider);
        saveMemoryEvent(user, sessionId, "context.compacted", detail);
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
        patternLoaded(userId, sessionId, patternId, version, null);
    }

    /**
     * As above, with why it was loaded: {@code on-demand} when the model asked for it with
     * {@code load_pattern} (#175); null (no {@code reason} field) for recall before the first call.
     */
    @Async
    public void patternLoaded(String userId, String sessionId, String patternId, String version, String reason) {
        String user = normalise(userId);
        log.info("pattern.loaded user={} session={} patternId={} version={} reason={}", user, sessionId, patternId, version, reason);

        if (repo == null) {
            log.debug("Audit repository not available; skipping pattern.loaded event");
            return;
        }

        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("patternId", patternId);
            detail.put("version", version);
            if (reason != null) {
                detail.put("reason", reason);
            }

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
