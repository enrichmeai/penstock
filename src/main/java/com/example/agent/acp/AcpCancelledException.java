package com.example.agent.acp;

/**
 * Thrown by {@link AcpStreamAdapter} from inside the {@code onMessage}/{@code onToken}
 * consumers {@code AgentService.chatStreaming} invokes, to unwind an in-progress turn once
 * {@code session/cancel} has marked it cancelled. Mirrors how the web UI's Stop button
 * already works: the consumer throws, {@code AgentService.appendAndEmit} lets the exception
 * propagate, and the loop stops before starting its next step (CLAUDE.md, "Architecture" —
 * "Consumer exceptions propagate so the loop unwinds promptly"). It does not interrupt a
 * provider call already in flight; it stops the loop from taking its next step once the
 * call returns, exactly as client disconnection does today.
 */
final class AcpCancelledException extends RuntimeException {
    AcpCancelledException() {
        super("Cancelled by session/cancel", null, false, false);
    }
}
