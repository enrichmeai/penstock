package com.example.agent.service.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.agent.config.CurrentUser;
import com.example.agent.controller.dto.SessionSummary;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.Role;
import com.example.agent.model.Session;
import com.example.agent.service.SessionStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * JPA-backed {@link SessionStore}, used for both SQLite (dev) and Postgres
 * (production). Owners get filtered access; cross-user access returns empty
 * (same shape as "not found") so we don't leak existence.
 */
@Component
@Conditional(JpaSessionStoreCondition.class)
public class JpaSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(JpaSessionStore.class);

    private final SessionRepository sessionRepo;
    private final MessageRepository messageRepo;
    private final ObjectMapper mapper;
    private final CurrentUser currentUser;

    @Autowired
    public JpaSessionStore(SessionRepository sessionRepo,
                           MessageRepository messageRepo,
                           ObjectMapper mapper,
                           CurrentUser currentUser) {
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.mapper = mapper;
        this.currentUser = currentUser;
    }

    /** Test-only convenience constructor: behaves as anonymous user. */
    public JpaSessionStore(SessionRepository sessionRepo,
                           MessageRepository messageRepo,
                           ObjectMapper mapper) {
        this(sessionRepo, messageRepo, mapper, null);
    }

    private String me() {
        return currentUser == null ? CurrentUser.ANONYMOUS : currentUser.name();
    }

    @Override
    @Transactional
    public Session create() {
        Session s = new Session(me());
        sessionRepo.save(new SessionEntity(s.getId(), s.getTitle(), s.getCreatedAt(), s.getUserId()));
        return s;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Session> get(String id) {
        return sessionRepo.findByIdAndUserId(id, me()).map(this::hydrate);
    }

    @Override
    @Transactional(readOnly = true)
    public Collection<Session> list() {
        List<Session> out = new ArrayList<>();
        for (SessionEntity e : sessionRepo.findAllByUserId(me())) out.add(hydrate(e));
        return out;
    }

    @Override
    @Transactional
    public void delete(String id) {
        // Only delete if the caller owns the session.
        sessionRepo.findByIdAndUserId(id, me()).ifPresent(e -> {
            messageRepo.deleteBySessionId(id);
            sessionRepo.deleteById(id);
        });
    }

    @Override
    @Transactional
    public void appendMessage(Session session, ChatMessage message) {
        // Defense in depth: reject if ownership ever mismatches.
        if (!me().equals(session.getUserId()) && !CurrentUser.ANONYMOUS.equals(me())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Cannot append to another user's session");
        }
        List<MessageEntity> existing = messageRepo.findBySessionIdOrderByPositionAsc(session.getId());
        int nextPos = existing.size();
        try {
            String json = mapper.writeValueAsString(message);
            messageRepo.save(new MessageEntity(
                    session.getId(),
                    nextPos,
                    message.role().name(),
                    json,
                    message.timestamp() == null ? Instant.now() : message.timestamp()));
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to serialise message", ex);
        }
    }

    @Override
    @Transactional
    public void update(Session session) {
        // Scope by the session's own userId, not me(): update is called from
        // the agent loop, which on the SSE path runs on a pooled thread where
        // the SecurityContext is absent (me() would be "anonymous" and the
        // owner-filtered query would silently drop the update). Ownership was
        // already enforced when the session was loaded on the request thread.
        sessionRepo.findByIdAndUserId(session.getId(), session.getUserId()).ifPresent(e -> {
            e.setTitle(session.getTitle());
            try {
                e.setPlanJson(session.getPlan().isEmpty() ? null : mapper.writeValueAsString(session.getPlan()));
            } catch (Exception ex) {
                // A plan that cannot be written is not worth failing the turn over; it lives on in memory.
                log.warn("Could not save the plan for session {}: {}", session.getId(), ex.toString());
            }
            sessionRepo.save(e);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionSummary> search(String q, int limit) {
        if (q == null || limit <= 0) return List.of();
        String like = "%" + q.toLowerCase() + "%";
        List<SessionEntity> rows = sessionRepo.searchOwned(me(), like, PageRequest.of(0, limit));
        List<SessionSummary> out = new ArrayList<>(rows.size());
        for (SessionEntity e : rows) out.add(SessionSummary.of(hydrate(e)));
        return out;
    }

    private Session hydrate(SessionEntity e) {
        Session s = RestoredSession.from(e.getId(), e.getTitle(), e.getCreatedAt(), e.getUserId());
        if (e.getPlanJson() != null && !e.getPlanJson().isBlank()) {
            try {
                s.setPlan(java.util.Arrays.asList(mapper.readValue(e.getPlanJson(), com.example.agent.model.PlanItem[].class)));
            } catch (Exception ex) {
                // An unreadable plan is dropped, not fatal: the history still loads.
                log.warn("Could not read the stored plan for session {}: {}", e.getId(), ex.toString());
            }
        }
        for (MessageEntity m : messageRepo.findBySessionIdOrderByPositionAsc(e.getId())) {
            try {
                ChatMessage cm = mapper.readValue(m.getPayloadJson(), ChatMessage.class);
                s.add(cm);
            } catch (Exception ex) {
                s.add(new ChatMessage(Role.valueOf(m.getRole()),
                        "[failed to deserialise stored message]", null, null, m.getTimestamp()));
            }
        }
        return s;
    }

    private static final class RestoredSession extends Session {
        private final String id;
        private final Instant createdAt;

        private RestoredSession(String id, Instant createdAt, String userId) {
            super(userId);
            this.id = id;
            this.createdAt = createdAt;
        }

        @Override public String getId() { return id; }
        @Override public Instant getCreatedAt() { return createdAt; }

        static Session from(String id, String title, Instant createdAt, String userId) {
            RestoredSession s = new RestoredSession(id, createdAt, userId);
            s.setTitle(title);
            return s;
        }
    }
}
