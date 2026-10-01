package com.example.agent.acp;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
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
 * {@code CurrentUser}, which resolves to "anonymous" with no request in flight.
 * {@code AgentService.chatStreaming} still calls {@code sessionStore.appendMessage}/
 * {@code update} per turn against whichever store is configured, with no row for this
 * session to attach to — harmless for the in-memory store (the {@code Session} object
 * itself is the source of truth), but {@code JpaSessionStore.appendMessage} would write
 * permanently orphaned rows with no FK to catch it. {@code AgentApplication.main} fails
 * fast if {@code --acp} is combined with anything other than
 * {@code agent.storage.type=memory}, so that case cannot reach here.
 */
@Component
public class AcpSessionBridge {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelled = new ConcurrentHashMap<>();
    private final Map<String, SyncPromptContext> promptContexts = new ConcurrentHashMap<>();

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

    /** Cleared once a turn finishes (by {@code AcpMode.prompt}'s {@code finally}), so a stale
     * cancel from a prior turn can never leak into the next one on this session. */
    public void clearCancelled(String sessionId) {
        AtomicBoolean flag = cancelled.get(sessionId);
        if (flag != null) flag.set(false);
    }

    /**
     * The live {@link SyncPromptContext} for the turn currently in flight on this session, set
     * by {@code AcpMode.prompt} for the duration of one {@code session/prompt} call. {@link
     * com.example.agent.tools.Tool#execute} runs deep inside that call's stack (through {@code
     * AgentService.runTurn} and {@code ToolRegistry.invoke}, neither of which knows about ACP),
     * so this session-keyed lookup — not a parameter threaded through those shared signatures —
     * is how {@link PermissionGate} reaches the client that can answer {@code
     * session/request_permission}. Same lifecycle as {@link #cancel}/{@link #clearCancelled}.
     */
    public void setPromptContext(String sessionId, SyncPromptContext ctx) {
        promptContexts.put(sessionId, ctx);
    }

    public SyncPromptContext promptContext(String sessionId) {
        return promptContexts.get(sessionId);
    }

    public void clearPromptContext(String sessionId) {
        promptContexts.remove(sessionId);
    }
}
