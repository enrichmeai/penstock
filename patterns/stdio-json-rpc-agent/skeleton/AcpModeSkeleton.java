// Skeleton: the ACP agent's protocol handlers — initialize, session/new, and the
// session/new.cwd == workspace guard. Compiles against the acp-agent-support SDK jar
// (see manifest.yaml `verified-against`); the @Prompt/@Cancel handlers that actually run a
// turn are deliberately left out of this skeleton — they call into *your* agent loop
// (Penstock's AgentService), which has nothing generic left to extract.
//
// What varies per use (see manifest.yaml `inputs`):
//   <your-agent-name>, <your-protocol-version>   the @AcpAgent identity
//   <YourWorkspaceType>, <your-workspace-field>  however your app already resolves its
//                                                 sandboxed root (Penstock: java.nio.file.Path)
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import java.io.IOException;
import java.nio.file.Path;

@AcpAgent(name = "<your-agent-name>", version = "<your-protocol-version>")
public class AcpModeSkeleton {

    private final Path workspace;

    public AcpModeSkeleton(Path workspace) throws IOException {
        this.workspace = workspace.toRealPath();
    }

    @Initialize
    AcpSchema.InitializeResponse initialize() {
        return AcpSchema.InitializeResponse.ok();
    }

    @NewSession
    AcpSchema.NewSessionResponse newSession(AcpSchema.NewSessionRequest request) {
        requireWorkspaceCwd(request.cwd());
        // APPLY: create your own session object here (Penstock: AcpSessionBridge.create) and
        // return its id — there is no HTTP principal in stdio mode to stamp it with, so this
        // identity is whatever the OS user running the editor already is.
        String sessionId = "<your-session-id>";
        return new AcpSchema.NewSessionResponse(sessionId, null, null);
    }

    /**
     * Protocol v1 requires {@code session/new.cwd} to resolve to this agent's configured
     * workspace — a per-session workspace would move the sandbox root, so any other cwd is
     * refused rather than honoured.
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
