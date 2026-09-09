package com.example.agent.service;

import com.example.agent.config.CurrentUser;
import com.example.agent.controller.dto.SessionSummary;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "agent.storage.type", havingValue = "memory", matchIfMissing = true)
public class InMemorySessionStore implements SessionStore {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final CurrentUser currentUser;

    public InMemorySessionStore() {
        this(null);   // anonymous mode for unit tests without Spring
    }

    /**
     * The constructor Spring must use. With two constructors and no annotation Spring
     * picks the no-arg one, which left every session stamped "anonymous" in memory mode
     * and visible to every caller — {@code MemoryModeOwnershipIT} pins the fix.
     */
    @Autowired
    public InMemorySessionStore(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    private String me() {
        return currentUser == null ? CurrentUser.ANONYMOUS : currentUser.name();
    }

    @Override public Session create() {
        Session s = new Session(me());
        sessions.put(s.getId(), s);
        return s;
    }

    @Override public Optional<Session> get(String id) {
        Session s = sessions.get(id);
        if (s == null) return Optional.empty();
        // Cross-user access looks like "not found" — don't leak existence.
        return s.getUserId().equals(me()) ? Optional.of(s) : Optional.empty();
    }

    @Override public Collection<Session> list() {
        String me = me();
        return sessions.values().stream()
                .filter(s -> s.getUserId().equals(me))
                .toList();
    }

    @Override public void delete(String id) {
        // Silently no-op on cross-user delete (same "not found" semantics as get).
        Session s = sessions.get(id);
        if (s != null && s.getUserId().equals(me())) sessions.remove(id);
    }

    @Override public void appendMessage(Session session, ChatMessage message) {
        // no-op: caller has already added it to the in-memory Session
    }

    @Override public void update(Session session) {
        // no-op: the Session object is the source of truth
    }

    @Override
    public List<SessionSummary> search(String q, int limit) {
        if (q == null || limit <= 0) return List.of();
        final String needle = q.toLowerCase();
        final String me = me();
        return sessions.values().stream()
                .filter(s -> s.getUserId().equals(me))
                .filter(s -> {
                    String title = s.getTitle();
                    if (title != null && title.toLowerCase().contains(needle)) return true;
                    for (ChatMessage m : s.getHistory()) {
                        String t = m.text();
                        if (t != null && t.toLowerCase().contains(needle)) return true;
                    }
                    return false;
                })
                .sorted(Comparator.comparing(Session::getCreatedAt).reversed())
                .limit(limit)
                .map(SessionSummary::of)
                .toList();
    }
}
