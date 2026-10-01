package com.example.agent.acp;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.example.agent.service.AgentService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code AcpStreamAdapter.classify}/{@code kindOf} in isolation — no process, no Spring
 * context. Pins the stopReason mapping CLAUDE.md's issue #61 evidence checklist asks for:
 * model finished -&gt; end_turn, {@code max-turns-per-request} hit -&gt; max_turn_requests,
 * {@code max-tokens-per-request}/session hit -&gt; max_tokens. (The real ACP enum constant is
 * {@code MAX_TURN_REQUESTS} / wire value {@code max_turn_requests} — confirmed via the SDK's
 * own {@code @JsonProperty} annotations, not the issue text's {@code max_requests}.)
 */
class AcpStreamAdapterTest {

    @Test
    void modelFinishingNormallyClassifiesAsEndTurn() {
        assertEquals(AcpSchema.StopReason.END_TURN, AcpStreamAdapter.classify("All done."));
        assertEquals(AcpSchema.StopReason.END_TURN, AcpStreamAdapter.classify(null));
    }

    @Test
    void maxTurnsReachedClassifiesAsMaxTurnRequests() {
        String message = AgentService.MAX_TURNS_REACHED_PREFIX + " (25). The task may be incomplete.";
        assertEquals(AcpSchema.StopReason.MAX_TURN_REQUESTS, AcpStreamAdapter.classify(message));
    }

    @Test
    void perRequestTokenBudgetReachedClassifiesAsMaxTokens() {
        String message = AgentService.REQUEST_BUDGET_REACHED_PREFIX + " (limit: 150000 tokens).";
        assertEquals(AcpSchema.StopReason.MAX_TOKENS, AcpStreamAdapter.classify(message));
    }

    @Test
    void sessionTokenBudgetExceededClassifiesAsMaxTokens() {
        String message = AgentService.SESSION_BUDGET_EXCEEDED_PREFIX + " (limit: 600000 tokens).";
        assertEquals(AcpSchema.StopReason.MAX_TOKENS, AcpStreamAdapter.classify(message));
    }

    @Test
    void toolKindMapping() {
        assertEquals(AcpSchema.ToolKind.READ, AcpStreamAdapter.kindOf("read_file"));
        assertEquals(AcpSchema.ToolKind.READ, AcpStreamAdapter.kindOf("list_dir"));
        assertEquals(AcpSchema.ToolKind.SEARCH, AcpStreamAdapter.kindOf("glob"));
        assertEquals(AcpSchema.ToolKind.SEARCH, AcpStreamAdapter.kindOf("grep"));
        assertEquals(AcpSchema.ToolKind.FETCH, AcpStreamAdapter.kindOf("pod"));
        assertEquals(AcpSchema.ToolKind.OTHER, AcpStreamAdapter.kindOf("shell"));
    }
}
