package com.example.agent.acp;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Cancel;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.example.agent.model.Session;
import com.example.agent.service.AgentService;
import com.example.agent.service.TurnContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The ACP stdio entry point (CLAUDE.md "Autonomous build loop" §61): answers
 * {@code initialize}, opens sessions, streams a prompt's assistant text, and honours
 * {@code session/cancel}. Wired to stdio by {@link AcpStdioRunner}, which only exists on
 * the {@code acp} profile — {@code AgentApplication} activates it for {@code --acp}.
 *
 * <p>No HTTP request ever starts a turn here, so there is no bearer to carry and no
 * {@code TurnContext} beyond {@link TurnContext#none()} — this mode's tool set is read-only
 * (see {@code agent.tools.enabled} on the {@code acp} profile), so forwarding credentials
 * never arises in this part.
 */
@Component
@Profile("acp")
@AcpAgent(name = "penstock", version = "1")
public class AcpMode {

    private static final Logger log = LoggerFactory.getLogger(AcpMode.class);

    private final AgentService agentService;
    private final AcpSessionBridge sessions;
    private final Path workspace;

    public AcpMode(AgentService agentService, AcpSessionBridge sessions, Path agentWorkspace) throws IOException {
        this.agentService = agentService;
        this.sessions = sessions;
        this.workspace = agentWorkspace.toRealPath();
    }

    @Initialize
    AcpSchema.InitializeResponse initialize() {
        return AcpSchema.InitializeResponse.ok();
    }

    @NewSession
    AcpSchema.NewSessionResponse newSession(AcpSchema.NewSessionRequest request) {
        requireWorkspaceCwd(request.cwd());
        if (request.mcpServers() != null && !request.mcpServers().isEmpty()) {
            log.info("session/new carried {} mcpServers; ignored in this part (not yet connected as an MCP client)",
                    request.mcpServers().size());
        }
        Session session = sessions.create(System.getProperty("user.name"));
        return new AcpSchema.NewSessionResponse(session.getId(), null, null);
    }

    @Prompt
    AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext ctx) {
        Session session = sessions.require(request.sessionId());
        // Deliberately not cleared here: the previous turn's own `finally` block (below)
        // already clears the flag once it finishes, and a session only ever has one prompt
        // in flight (the protocol errors on a second with CONCURRENT_PROMPT). Clearing again
        // on entry raced the session/cancel notification's own dispatch — cancel and prompt
        // are independent JSON-RPC messages the SDK can process concurrently, so a cancel
        // sent immediately after the prompt it targets could be set *before* this handler
        // starts, and clearing here erased it, losing the cancellation outright.
        AcpStreamAdapter adapter = new AcpStreamAdapter(ctx, session.getId(), () -> sessions.isCancelled(session.getId()));
        sessions.setPromptContext(session.getId(), ctx);
        try {
            agentService.chatStreaming(session, request.text(), TurnContext.none(), adapter::onMessage, adapter::onToken);
        } catch (AcpCancelledException cancelled) {
            return new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED);
        } finally {
            sessions.clearCancelled(session.getId());
            sessions.clearPromptContext(session.getId());
        }
        return new AcpSchema.PromptResponse(adapter.stopReason());
    }

    @Cancel
    void cancel(AcpSchema.CancelNotification notification) {
        sessions.cancel(notification.sessionId());
    }

    /**
     * v1 requires {@code session/new.cwd} to resolve to {@code agent.workspace} — per-session
     * workspaces would move {@code WorkspacePath}'s root, a sandbox invariant (CLAUDE.md
     * "Sandboxing invariants"), so any other cwd is refused rather than honoured.
     */
    private void requireWorkspaceCwd(String cwd) {
        if (cwd == null || cwd.isBlank()) {
            throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS,
                    "session/new requires cwd; this agent is configured for workspace " + workspace);
        }
        Path real;
        try {
            real = Path.of(cwd).toRealPath();
        } catch (IOException e) {
            throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS,
                    "cwd does not resolve to a real path: " + cwd);
        }
        if (!real.equals(workspace)) {
            throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS,
                    "cwd must be this agent's configured workspace (" + workspace + "), got " + real);
        }
    }
}
