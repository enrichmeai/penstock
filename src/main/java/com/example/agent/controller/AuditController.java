package com.example.agent.controller;

import com.example.agent.config.AgentProperties;
import com.example.agent.memory.PatternCatalog;
import com.example.agent.memory.PatternManifest;
import com.example.agent.service.AgentService;
import com.example.agent.service.persistence.AuditEventEntity;
import com.example.agent.service.persistence.AuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
     * last (by {@code lastLoadedAt} descending, nulls last).
     *
     * <p>Returns {@code []} while {@code agent.memory.enabled} is off — the same posture as
     * the rest of pattern retrieval. Returns {@code 503} in memory storage mode, where there
     * is no audit table to aggregate (the {@code pattern.loaded} INFO log line is the trail
     * there; see {@code AuditLogger.patternLoaded}).
     */
    @GetMapping("/audit/patterns")
    public ResponseEntity<?> getPatternAudit() {
        if (!props.getMemory().isEnabled()) {
            return ResponseEntity.ok(new ArrayList<>());
        }
        if (auditRepo == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "the audit table does not exist in memory storage mode");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
        }

        Map<String, PatternUsage> usage = new LinkedHashMap<>();
        for (AuditEventEntity event : auditRepo.findByEventTypeOrderByTimestampDesc("pattern.loaded")) {
            try {
                Map<?, ?> detail = mapper.readValue(event.getDetailJson(), Map.class);
                String patternId = String.valueOf(detail.get("patternId"));
                String version = String.valueOf(detail.get("version"));
                PatternUsage u = usage.computeIfAbsent(patternId, k -> new PatternUsage());
                u.loads++;
                // Events arrive ordered by timestamp descending, so the first one seen per id
                // is already the most recent.
                if (u.lastLoadedAt == null) {
                    u.lastLoadedAt = event.getTimestamp();
                }
                u.versions.add(version);
            } catch (Exception e) {
                log.warn("Failed to parse detail JSON for pattern audit event {}", event.getId(), e);
            }
        }

        for (PatternManifest p : patternCatalog.patterns()) {
            usage.putIfAbsent(p.id(), new PatternUsage());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, PatternUsage> entry : usage.entrySet()) {
            PatternUsage u = entry.getValue();
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("patternId", entry.getKey());
            dto.put("loads", u.loads);
            dto.put("lastLoadedAt", u.lastLoadedAt);
            dto.put("versions", List.copyOf(u.versions));
            result.add(dto);
        }
        result.sort(Comparator.comparing(
                (Map<String, Object> dto) -> (Instant) dto.get("lastLoadedAt"),
                Comparator.nullsLast(Comparator.reverseOrder())));
        return ResponseEntity.ok(result);
    }

    private static class PatternUsage {
        int loads = 0;
        Instant lastLoadedAt;
        Set<String> versions = new LinkedHashSet<>();
    }
}
