package com.example.agent.service;

import com.example.agent.config.AgentProperties;
import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.*;
import com.example.agent.tools.ToolRegistry;
import com.example.agent.tools.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Context compaction (#173): with {@code agent.llm.context.policy=compact}, a history that nears
 * the per-request token budget has its older messages folded into one summary carried in the
 * system prompt; the newest messages go to the model verbatim; the stored session history is
 * never changed; and each compaction is audited.
 */
class ContextCompactionTest {

    private static final String FILLER = "x".repeat(800);   // ~200 tokens by the chars/4 estimate

    /** What the main (non-summary) model call saw. */
    private static final class Seen {
        String system;
        List<ChatMessage> history;
        int summaryCalls;
    }

    private static LlmProvider scripted(Seen seen, String summaryText) {
        return new LlmProvider() {
            @Override public String name() { return "scripted"; }
            @Override public CompletionResult complete(String sys, List<ChatMessage> hist, List<ToolSpec> tools, String sessionId) {
                if (sys.startsWith(ContextCompactor.SUMMARY_INSTRUCTION)) {
                    seen.summaryCalls++;
                    assertTrue(tools.isEmpty(), "the summary call offers no tools");
                    return new CompletionResult(ChatMessage.assistantText(summaryText), new TokenUsage(50, 20));
                }
                seen.system = sys;
                seen.history = new ArrayList<>(hist);
                return new CompletionResult(ChatMessage.assistantText("done"), new TokenUsage(10, 5));
            }
        };
    }

    private static AgentProperties compactProps() {
        AgentProperties props = new AgentProperties();
        props.getLlm().setStreamingEnabled(false);
        props.getLlm().setMaxTokensPerRequest(2_000);           // threshold 0.5 → ~1,000 tokens
        props.getLlm().getContext().setPolicy("compact");
        props.getLlm().getContext().setCompactThresholdRatio(0.5);
        props.getLlm().getContext().setCompactKeepRecent(2);
        return props;
    }

    private static Session longSession(AgentService svc) {
        Session s = svc.createSession();
        for (int i = 0; i < 8; i++) {                           // 16 messages, ~3,200 tokens
            s.add(ChatMessage.user("question " + i + " " + FILLER));
            s.add(ChatMessage.assistantText("answer " + i + " " + FILLER));
        }
        return s;
    }

    @Test
    void foldsOlderMessagesIntoTheSystemPromptAndSendsOnlyTheRecentOnesVerbatim() {
        Seen seen = new Seen();
        AgentProperties props = compactProps();
        AgentService svc = new AgentService(scripted(seen, "Earlier: questions 0-6 were answered."),
                new ToolRegistry(List.of(), props), props, new InMemorySessionStore());
        Session s = longSession(svc);

        svc.chat(s, "next question");

        assertEquals(1, seen.summaryCalls);
        assertTrue(seen.system.contains("Earlier: questions 0-6 were answered."), seen.system);
        assertTrue(seen.history.size() <= 3, "only the recent messages go verbatim: " + seen.history.size());
        assertEquals("next question", seen.history.get(seen.history.size() - 1).text());
        assertFalse(seen.history.stream().anyMatch(m -> m.text() != null && m.text().startsWith("question 0 ")));
    }

    @Test
    void theStoredHistoryIsNeverChanged() {
        Seen seen = new Seen();
        AgentProperties props = compactProps();
        AgentService svc = new AgentService(scripted(seen, "summary"),
                new ToolRegistry(List.of(), props), props, new InMemorySessionStore());
        Session s = longSession(svc);
        List<ChatMessage> before = new ArrayList<>(s.getHistory());

        svc.chat(s, "next question");

        assertEquals(before.size() + 2, s.getHistory().size());
        assertEquals(before, s.getHistory().subList(0, before.size()));
    }

    @Test
    void belowTheThresholdNothingIsSummarised() {
        Seen seen = new Seen();
        AgentProperties props = compactProps();
        AgentService svc = new AgentService(scripted(seen, "summary"),
                new ToolRegistry(List.of(), props), props, new InMemorySessionStore());
        Session s = svc.createSession();
        s.add(ChatMessage.user("short"));
        s.add(ChatMessage.assistantText("short"));

        svc.chat(s, "next");

        assertEquals(0, seen.summaryCalls);
        assertEquals(3, seen.history.size());
        assertFalse(seen.system.contains(ContextCompactor.SUMMARY_HEADING));
    }

    @Test
    void aCredentialShapedLineInTheSummaryNeverReachesTheModel() {
        Seen seen = new Seen();
        AgentProperties props = compactProps();
        AgentService svc = new AgentService(scripted(seen, "progress so far\ntoken: abc123secretvalue\nnext step"),
                new ToolRegistry(List.of(), props), props, new InMemorySessionStore());
        Session s = longSession(svc);

        svc.chat(s, "next question");

        assertFalse(seen.system.contains("abc123secretvalue"), seen.system);
        assertTrue(seen.system.contains("progress so far"));
    }

    @Test
    void theRecentWindowNeverStartsWithAnOrphanedToolResult() {
        Seen seen = new Seen();
        AgentProperties props = compactProps();
        props.getLlm().getContext().setCompactKeepRecent(1);
        AgentService svc = new AgentService(scripted(seen, "summary"),
                new ToolRegistry(List.of(), props), props, new InMemorySessionStore());
        Session s = longSession(svc);
        s.add(ChatMessage.assistant("calling", List.of(new ToolCall("t1", "echo", java.util.Map.of()))));
        s.add(ChatMessage.tool(List.of(ToolResult.ok("t1", "echoed " + FILLER))));

        svc.chat(s, "next question");

        assertNotEquals(Role.TOOL, seen.history.get(0).role(), "a TOOL message must not lead the window");
    }

    @Test
    void eachCompactionIsAudited() {
        Seen seen = new Seen();
        AgentProperties props = compactProps();
        List<String> events = new ArrayList<>();
        AuditLogger audit = new AuditLogger(null, new ObjectMapper()) {
            @Override public void contextCompacted(String userId, String sessionId, int folded,
                                                   long tokensBefore, long tokensAfter, String provider) {
                events.add(folded + ":" + (tokensBefore > tokensAfter) + ":" + provider);
            }
        };
        AgentService svc = new AgentService(scripted(seen, "summary"),
                new ToolRegistry(List.of(), props), props, new InMemorySessionStore());
        svc.setAuditLogger(audit);
        Session s = longSession(svc);

        svc.chat(s, "next question");

        assertEquals(1, events.size(), events.toString());
        assertTrue(events.get(0).endsWith(":true:scripted"), events.toString());
    }

    @Test
    void aFailedSummaryCallSendsTheRequestUnfoldedAndAuditsTheFailure() {
        AgentProperties props = compactProps();
        List<String> calls = new ArrayList<>();
        Seen seen = new Seen();
        LlmProvider llm = new LlmProvider() {
            @Override public String name() { return "scripted"; }
            @Override public CompletionResult complete(String sys, List<ChatMessage> hist, List<ToolSpec> tools, String sessionId) {
                if (sys.startsWith(ContextCompactor.SUMMARY_INSTRUCTION)) {
                    throw new IllegalStateException("provider down for summaries");
                }
                seen.system = sys;
                seen.history = new ArrayList<>(hist);
                return new CompletionResult(ChatMessage.assistantText("done"), new TokenUsage(10, 5));
            }
        };
        AuditLogger audit = new AuditLogger(null, new ObjectMapper()) {
            @Override public void llmCall(String userId, String sessionId, String provider, int in, int out, boolean success) {
                calls.add(String.valueOf(success));
            }
            @Override public void contextCompacted(String userId, String sessionId, int folded,
                                                   long tokensBefore, long tokensAfter, String provider) {
                calls.add("compacted");
            }
        };
        AgentService svc = new AgentService(llm, new ToolRegistry(List.of(), props), props, new InMemorySessionStore());
        svc.setAuditLogger(audit);
        Session s = longSession(svc);

        svc.chat(s, "next question");

        assertEquals(17, seen.history.size(), "unfolded: the whole history goes to the model");
        assertFalse(seen.system.contains(ContextCompactor.SUMMARY_HEADING));
        assertEquals(List.of("false", "true"), calls, "a failed summary call, then the main call; no compaction row");
    }

    @Test
    void aBlankSummaryIsTreatedAsAFailureNotAsAnEmptyHistory() {
        Seen seen = new Seen();
        AgentProperties props = compactProps();
        AgentService svc = new AgentService(scripted(seen, "   "),
                new ToolRegistry(List.of(), props), props, new InMemorySessionStore());
        Session s = longSession(svc);

        svc.chat(s, "next question");

        assertEquals(17, seen.history.size());
        assertFalse(seen.system.contains(ContextCompactor.SUMMARY_HEADING));
    }

    @Test
    void theSummaryCallsTokensCountAgainstTheSessionBudget() {
        Seen seen = new Seen();
        AgentProperties props = compactProps();
        AgentService svc = new AgentService(scripted(seen, "summary"),
                new ToolRegistry(List.of(), props), props, new InMemorySessionStore());
        Session s = longSession(svc);

        svc.chat(s, "next question");

        assertEquals(50 + 10, s.getTotalUsage().inputTokens(), "summary call (50) plus main call (10)");
        assertEquals(20 + 5, s.getTotalUsage().outputTokens());
    }

    @Test
    void aLaterIterationFoldsAgainCarryingThePreviousSummaryForward() {
        AgentProperties props = compactProps();
        List<String> transcripts = new ArrayList<>();
        com.example.agent.tools.Tool big = new com.example.agent.tools.Tool() {
            @Override public String name() { return "big"; }
            @Override public String description() { return "returns a lot"; }
            @Override public java.util.Map<String, Object> inputSchema() { return java.util.Map.of("type", "object"); }
            @Override public ToolResult execute(String id, java.util.Map<String, Object> args, com.example.agent.tools.ToolContext c) {
                return ToolResult.ok(id, "y".repeat(2_400));
            }
        };
        LlmProvider llm = new LlmProvider() {
            int main = 0;
            @Override public String name() { return "scripted"; }
            @Override public CompletionResult complete(String sys, List<ChatMessage> hist, List<ToolSpec> tools, String sessionId) {
                if (sys.startsWith(ContextCompactor.SUMMARY_INSTRUCTION)) {
                    transcripts.add(hist.get(0).text());
                    return new CompletionResult(ChatMessage.assistantText("summary " + transcripts.size()), new TokenUsage(1, 1));
                }
                main++;
                if (main <= 3) {
                    return new CompletionResult(ChatMessage.assistant("again",
                            List.of(new ToolCall("c" + main, "big", java.util.Map.of()))), new TokenUsage(1, 1));
                }
                return new CompletionResult(ChatMessage.assistantText("done"), new TokenUsage(1, 1));
            }
        };
        AgentService svc = new AgentService(llm, new ToolRegistry(List.of(big), props), props, new InMemorySessionStore());
        Session s = longSession(svc);

        svc.chat(s, "next question");

        assertTrue(transcripts.size() >= 2, "folded more than once: " + transcripts.size());
        assertTrue(transcripts.get(1).startsWith("Summary so far:\nsummary 1"), transcripts.get(1));
    }

    @Test
    void anAssistantToolCallWhoseResultsNeverArrivedIsFoldedNotSent() {
        Seen seen = new Seen();
        AgentProperties props = compactProps();
        props.getLlm().getContext().setCompactKeepRecent(2);
        AgentService svc = new AgentService(scripted(seen, "summary"),
                new ToolRegistry(List.of(), props), props, new InMemorySessionStore());
        Session s = longSession(svc);
        // A turn that stopped on its budget right after asking for a tool.
        s.add(ChatMessage.assistant("calling", List.of(new ToolCall("t9", "echo", java.util.Map.of()))));

        svc.chat(s, "next question");

        assertFalse(seen.history.stream().anyMatch(ChatMessage::hasToolCalls), "no unanswered tool call is sent");
        assertEquals("next question", seen.history.get(seen.history.size() - 1).text());
    }

    @Test
    void theSettingsRefuseValuesThatCannotWork() {
        var c = new AgentProperties().getLlm().getContext();
        assertThrows(IllegalArgumentException.class, () -> c.setCompactThresholdRatio(0));
        assertThrows(IllegalArgumentException.class, () -> c.setCompactThresholdRatio(1.5));
        assertThrows(IllegalArgumentException.class, () -> c.setCompactKeepRecent(0));
    }

    @Test
    void aFailedSummaryIsNotRetriedForTheRestOfTheRequest() {
        AgentProperties props = compactProps();
        int[] summaryCalls = {0};
        com.example.agent.tools.Tool noop = new com.example.agent.tools.Tool() {
            @Override public String name() { return "noop"; }
            @Override public String description() { return "noop"; }
            @Override public java.util.Map<String, Object> inputSchema() { return java.util.Map.of("type", "object"); }
            @Override public ToolResult execute(String id, java.util.Map<String, Object> args, com.example.agent.tools.ToolContext c) {
                return ToolResult.ok(id, "ok");
            }
        };
        LlmProvider llm = new LlmProvider() {
            int main = 0;
            @Override public String name() { return "scripted"; }
            @Override public CompletionResult complete(String sys, List<ChatMessage> hist, List<ToolSpec> tools, String sessionId) {
                if (sys.startsWith(ContextCompactor.SUMMARY_INSTRUCTION)) {
                    summaryCalls[0]++;
                    throw new IllegalStateException("down");
                }
                main++;
                return main <= 3
                        ? new CompletionResult(ChatMessage.assistant("again", List.of(new ToolCall("n" + main, "noop", java.util.Map.of()))), new TokenUsage(1, 1))
                        : new CompletionResult(ChatMessage.assistantText("done"), new TokenUsage(1, 1));
            }
        };
        AgentService svc = new AgentService(llm, new ToolRegistry(List.of(noop), props), props, new InMemorySessionStore());
        Session s = longSession(svc);

        svc.chat(s, "next question");

        assertEquals(1, summaryCalls[0], "one failed attempt, not one per iteration");
    }
}
