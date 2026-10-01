package com.example.agent.acp;

import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.example.agent.model.Session;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ACP {@code sessionId} &harr; {@link Session}, plus the cooperative cancel flag
 * {@code session/cancel} sets for {@link AcpStreamAdapter} to observe.
 *
 * <p>Deliberately does not go through {@code SessionStore}: an ACP session has no HTTP
 * principal to stamp it with (CLAUDE.md, "Identity on background threads" — the IDE
 * launched this process as the OS user, so that's the identity {@link Session} carries),
 * and {@code SessionStore.create()} always stamps the session it returns from
 * {@code CurrentUser}, which resolves to "anonymous" with no request in flight. ACP mode
 * targets {@code agent.storage.type=memory} in this part — {@code AgentService.chatStreaming}
 * still calls {@code sessionStore.appendMessage}/{@code update} per turn, which no-op for
 * the in-memory store; a session created here is invisible to the SQLite/Postgres stores
 * (no row exists for it), a known gap outside this part's scope.
 */
@Component
public class AcpSessionBridge {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelled = new ConcurrentHashMap<>();

    public Session create(String userId) {
        Session session = new Session(userId);
        sessions.put(session.getId(), session);
        cancelled.put(session.getId(), new AtomicBoolean(false));
        return session;
    }

    public Session require(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            throw new AcpProtocolException(AcpErrorCodes.SESSION_NOT_FOUND, "No such session: " + sessionId);
        }
        return session;
    }

    /** {@code session/cancel} is a notification — mark the flag; the in-flight turn observes it. */
    public void cancel(String sessionId) {
        AtomicBoolean flag = cancelled.get(sessionId);
        if (flag != null) flag.set(true);
    }

    public boolean isCancelled(String sessionId) {
        AtomicBoolean flag = cancelled.get(sessionId);
        return flag != null && flag.get();
    }

    /** Reset before each new prompt so a stale cancel from a prior turn doesn't leak into this one. */
    public void clearCancelled(String sessionId) {
        AtomicBoolean flag = cancelled.get(sessionId);
        if (flag != null) flag.set(false);
    }
}
