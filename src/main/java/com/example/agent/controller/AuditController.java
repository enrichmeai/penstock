package com.example.agent.controller;

import com.example.agent.config.AgentProperties;
import com.example.agent.memory.EpisodeCard;
import com.example.agent.memory.FactCard;
import com.example.agent.memory.PatternCatalog;
import com.example.agent.memory.PatternManifest;
import com.example.agent.service.AgentService;
import com.example.agent.service.persistence.AuditEventEntity;
import com.example.agent.service.persistence.AuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST API for audit logs (tool calls and LLM calls).
 */
@RestController
@RequestMapping("/api")
public class AuditController {

    private static final Logger log = LoggerFactory.getLogger(AuditController.class);

    private final AgentService agent;
    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final PatternCatalog patternCatalog;
    private AuditEventRepository auditRepo;

    public AuditController(AgentService agent, ObjectMapper mapper, AgentProperties props, PatternCatalog patternCatalog) {
        this.agent = agent;
        this.mapper = mapper;
        this.props = props;
        this.patternCatalog = patternCatalog;
    }

    @Autowired(required = false)
    public void setAuditEventRepository(AuditEventRepository auditRepo) {
        this.auditRepo = auditRepo;
    }

    /**
     * GET /api/sessions/{id}/audit
     *
     * Returns audit events for a session, owner-scoped via AgentService.requireSession.
     * If audit is not available (no repository), returns an empty list.
     *
     * Response: [ { "timestamp": "...", "eventType": "tool_call|llm_call", "detail": {...} }, ... ]
     */
    @GetMapping("/sessions/{id}/audit")
    public List<Map<String, Object>> getSessionAudit(@PathVariable String id) {
        // Verify ownership via requireSession (throws SessionNotFoundException on cross-user access)
        agent.requireSession(id);

        // If audit not available, return empty list
        if (auditRepo == null) {
            return new ArrayList<>();
        }

        // Fetch events for this session, ordered by timestamp ascending
        List<AuditEventEntity> events = auditRepo.findBySessionIdOrderByTimestampAsc(id);

        // Transform to response DTO
        List<Map<String, Object>> result = new ArrayList<>();
        for (AuditEventEntity event : events) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("timestamp", event.getTimestamp());
            dto.put("eventType", event.getEventType());

            // Parse detailJson back to a Map
            try {
                Object detail = mapper.readValue(event.getDetailJson(), Object.class);
                dto.put("detail", detail);
            } catch (Exception e) {
                log.warn("Failed to parse detail JSON for audit event {}", event.getId(), e);
                dto.put("detail", event.getDetailJson());
            }

            result.add(dto);
        }
        return result;
    }

    /**
     * GET /api/audit/patterns
     *
     * Aggregates {@code pattern.loaded} audit events into per-pattern usage, so pruning
     * {@code patterns/} is a reading of this endpoint rather than a memory exercise (#81).
     * Every id currently in {@link PatternCatalog} is listed even with zero loads, sorted
     * last (by {@code lastLoadedAt} descending, nulls last). Kept as is for compatibility: it is
     * the pattern rows of {@link #getRecallAudit()}, with {@code patternId} for {@code id} (#90).
     *
     * <p>Returns {@code []} while {@code agent.memory.enabled} is off — the same posture as
     * the rest of pattern retrieval. Returns {@code 503} in memory storage mode, where there
     * is no audit table to aggregate (the {@code pattern.loaded} INFO log line is the trail
     * there; see {@code AuditLogger.patternLoaded}).
     */
    @GetMapping("/audit/patterns")
    public List<Map<String, Object>> getPatternAudit() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : getRecallAudit()) {
            if (!"pattern".equals(row.get("kind"))) {
                continue;
            }
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("patternId", row.get("id"));
            dto.put("loads", row.get("loads"));
            dto.put("lastLoadedAt", row.get("lastLoadedAt"));
            dto.put("versions", row.get("versions"));
            result.add(dto);
        }
        return result;
    }

    /**
     * GET /api/audit/recall
     *
     * Aggregates {@code pattern.loaded}, {@code fact.loaded} and {@code episode.loaded} audit
     * events per card (#90): {@code [{kind, id, loads, lastLoadedAt, versions}]}, where
     * {@code kind} is {@code pattern}, {@code fact} or {@code episode} and {@code versions} is
     * the pattern versions loaded ({@code []} for a fact or an episode, which carry none). Every
     * card in {@link PatternCatalog} is listed even with zero loads, so a pattern never loaded,
     * a fact never recalled and an episode never read show up for pruning. Sorted by
     * {@code lastLoadedAt} descending, never-loaded cards last in catalogue order (patterns,
     * facts, episodes). Same flag-off and memory-storage posture as {@link #getPatternAudit()}.
     */
    @GetMapping("/audit/recall")
    public List<Map<String, Object>> getRecallAudit() {
        if (!props.getMemory().isEnabled()) {
            return new ArrayList<>();
        }
        if (auditRepo == null) {
            throw new AuditStoreUnavailableException("the audit table does not exist in memory storage mode");
        }

        Map<String, CardUsage> usage = new LinkedHashMap<>();
        for (AuditEventEntity event : auditRepo.findByEventTypeInOrderByTimestampDesc(KINDS.keySet())) {
            Kind kind = KINDS.get(event.getEventType());
            if (kind == null) {
                continue;
            }
            try {
                Map<?, ?> detail = mapper.readValue(event.getDetailJson(), Map.class);
                Object rawId = detail.get(kind.idField());
                if (rawId == null) {
                    log.warn("Skipping {} audit event {} with no {}", event.getEventType(), event.getId(), kind.idField());
                    continue;
                }
                CardUsage u = usage.computeIfAbsent(kind.name() + ":" + rawId, k -> new CardUsage(kind.name(), String.valueOf(rawId)));
                u.loads++;
                // Events arrive ordered by timestamp descending, so the first one seen per card
                // is already the most recent.
                if (u.lastLoadedAt == null) {
                    u.lastLoadedAt = event.getTimestamp();
                }
                if ("pattern".equals(kind.name())) {
                    u.versions.add(String.valueOf(detail.get("version")));
                }
            } catch (Exception e) {
                log.warn("Failed to parse detail JSON for {} audit event {}", event.getEventType(), event.getId(), e);
            }
        }

        for (PatternManifest p : patternCatalog.patterns()) {
            usage.putIfAbsent("pattern:" + p.id(), new CardUsage("pattern", p.id()));
        }
        for (FactCard f : patternCatalog.facts()) {
            usage.putIfAbsent("fact:" + f.id(), new CardUsage("fact", f.id()));
        }
        for (EpisodeCard e : patternCatalog.episodes()) {
            usage.putIfAbsent("episode:" + e.id(), new CardUsage("episode", e.id()));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (CardUsage u : usage.values()) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("kind", u.kind);
            dto.put("id", u.id);
            dto.put("loads", u.loads);
            dto.put("lastLoadedAt", u.lastLoadedAt);
            dto.put("versions", List.copyOf(u.versions));
            result.add(dto);
        }
        // Newest load first; two cards loaded at the same instant go by kind then id, so the order
        // is the same on every call. A stable sort keeps never-loaded cards in catalogue order.
        result.sort((a, b) -> {
            Instant ta = (Instant) a.get("lastLoadedAt");
            Instant tb = (Instant) b.get("lastLoadedAt");
            if (ta == null || tb == null) {
                return ta == null ? (tb == null ? 0 : 1) : -1;
            }
            int byTime = tb.compareTo(ta);
            if (byTime != 0) {
                return byTime;
            }
            int byKind = String.valueOf(a.get("kind")).compareTo(String.valueOf(b.get("kind")));
            return byKind != 0 ? byKind : String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id")));
        });
        return result;
    }

    /** A memory card kind: the audit event that records a load, and the detail field naming the card. */
    private record Kind(String name, String idField) {
    }

    private static final Map<String, Kind> KINDS = Map.of(
            "pattern.loaded", new Kind("pattern", "patternId"),
            "fact.loaded", new Kind("fact", "factId"),
            "episode.loaded", new Kind("episode", "episodeId"));

    private static class CardUsage {
        final String kind;
        final String id;
        int loads = 0;
        Instant lastLoadedAt;
        Set<String> versions = new LinkedHashSet<>();

        CardUsage(String kind, String id) {
            this.kind = kind;
            this.id = id;
        }
    }
}
