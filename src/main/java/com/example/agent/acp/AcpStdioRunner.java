package com.example.agent.acp;

import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Builds the ACP stdio transport around {@link AcpMode} and blocks until the client
 * disconnects. Only registered on the {@code acp} profile; {@code AgentApplication} calls
 * {@link #run()} explicitly after the context starts — it is not a {@code CommandLineRunner},
 * since the web and ACP entry points must not both race to read stdin/write stdout.
 */
@Component
@Profile("acp")
public class AcpStdioRunner {

    private final AcpMode acpMode;

    public AcpStdioRunner(AcpMode acpMode) {
        this.acpMode = acpMode;
    }

    /** Blocks until the client disconnects (closes stdin), as {@code AcpAgentSupport.run()} does. */
    public void run() {
        AcpAgentSupport support = AcpAgentSupport.create(acpMode)
                .transport(new StdioAcpAgentTransport())
                .build();
        support.run();
    }
}
