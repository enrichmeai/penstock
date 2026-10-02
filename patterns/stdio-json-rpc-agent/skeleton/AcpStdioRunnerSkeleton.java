// Skeleton: the stdio transport wiring. Compiles against the acp-agent-support SDK jar.
// Keep this out of your framework's normal startup path (a CommandLineRunner, etc.) — the
// stdio agent entry point and any other entry point (an HTTP server, for instance) must not
// both race to read stdin/write stdout. Call run() explicitly, once, after your app context
// is otherwise ready.
//
// What varies per use: <YourAgent> — whatever you named the class carrying @AcpAgent
// (AcpModeSkeleton here; Penstock's real one is AcpMode).
import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;

public class AcpStdioRunnerSkeleton {

    private final AcpModeSkeleton agent;

    public AcpStdioRunnerSkeleton(AcpModeSkeleton agent) {
        this.agent = agent;
    }

    /** Blocks until the client disconnects (closes stdin), as {@code AcpAgentSupport.run()} does. */
    public void run() {
        AcpAgentSupport support = AcpAgentSupport.create(agent)
                .transport(new StdioAcpAgentTransport())
                .build();
        support.run();
    }
}
