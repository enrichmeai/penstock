package com.example.agent.config;

import com.example.agent.model.ToolOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentMetricsTest {

    @Test
    void recordLlmCallIncrementCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        // Record a successful LLM call
        metrics.recordLlmCall("anthropic", true, 50, 100);

        // Check llm_calls_total counter
        Counter callCounter = registry.find("llm_calls_total")
                .tag("provider", "anthropic")
                .tag("outcome", "ok")
                .counter();
        assertNotNull(callCounter);
        assertEquals(1.0, callCounter.count());

        // Check input tokens counter
        Counter inputCounter = registry.find("llm_tokens_total")
                .tag("provider", "anthropic")
                .tag("kind", "input")
                .counter();
        assertNotNull(inputCounter);
        assertEquals(50.0, inputCounter.count());

        // Check output tokens counter
        Counter outputCounter = registry.find("llm_tokens_total")
                .tag("provider", "anthropic")
                .tag("kind", "output")
                .counter();
        assertNotNull(outputCounter);
        assertEquals(100.0, outputCounter.count());
    }

    @Test
    void recordLlmCallErrorOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        // Record a failed LLM call with no tokens
        metrics.recordLlmCall("openai", false, 0, 0);

        // Check llm_calls_total for error outcome
        Counter callCounter = registry.find("llm_calls_total")
                .tag("provider", "openai")
                .tag("outcome", "error")
                .counter();
        assertNotNull(callCounter);
        assertEquals(1.0, callCounter.count());

        // Input and output should still be registered but with 0 count
        Counter inputCounter = registry.find("llm_tokens_total")
                .tag("provider", "openai")
                .tag("kind", "input")
                .counter();
        assertNotNull(inputCounter);
        assertEquals(0.0, inputCounter.count());
    }

    @Test
    void recordToolCallIncrementCountersAndTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        // Record a successful tool call
        metrics.recordToolCall("read_file", ToolOutcome.OK, 150);

        // Check tool_calls_total counter
        Counter callCounter = registry.find("tool_calls_total")
                .tag("tool", "read_file")
                .tag("outcome", "ok")
                .counter();
        assertNotNull(callCounter);
        assertEquals(1.0, callCounter.count());

        // Check timer
        Timer timer = registry.find("tool_call_duration_ms")
                .tag("tool", "read_file")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertEquals(150, timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 0.1);
    }

    @Test
    void recordToolCallError() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        // Record a failed tool call
        metrics.recordToolCall("write_file", ToolOutcome.ERROR, 75);

        // Check tool_calls_total for error outcome
        Counter callCounter = registry.find("tool_calls_total")
                .tag("tool", "write_file")
                .tag("outcome", "error")
                .counter();
        assertNotNull(callCounter);
        assertEquals(1.0, callCounter.count());

        // Timer should still be registered
        Timer timer = registry.find("tool_call_duration_ms")
                .tag("tool", "write_file")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void recordToolCallRefusedOutcomeIsItsOwnLabel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        metrics.recordToolCall("pod", ToolOutcome.REFUSED, 12);

        Counter refused = registry.find("tool_calls_total").tag("tool", "pod").tag("outcome", "refused").counter();
        assertNotNull(refused, "a refusal is counted as refused");
        assertEquals(1.0, refused.count());
        assertNull(registry.find("tool_calls_total").tag("tool", "pod").tag("outcome", "ok").counter(),
                "a refusal is not a success");
        assertEquals(1, registry.find("tool_call_duration_ms").tag("tool", "pod").timer().count());
    }

    @Test
    void sseStreamsActiveGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        // Initially no streams active
        Gauge gauge = registry.find("sse_streams_active").gauge();
        assertNotNull(gauge);
        assertEquals(0.0, gauge.value());

        // Start one stream
        metrics.sseStreamStarted();
        assertEquals(1.0, gauge.value());

        // Start another stream
        metrics.sseStreamStarted();
        assertEquals(2.0, gauge.value());

        // Finish one stream
        metrics.sseStreamFinished();
        assertEquals(1.0, gauge.value());

        // Finish the other stream
        metrics.sseStreamFinished();
        assertEquals(0.0, gauge.value());
    }

    @Test
    void multipleCallsIncrementCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        // Record multiple LLM calls
        metrics.recordLlmCall("anthropic", true, 50, 100);
        metrics.recordLlmCall("anthropic", true, 60, 120);
        metrics.recordLlmCall("anthropic", false, 0, 0);

        // Check final counts
        Counter callCounter = registry.find("llm_calls_total")
                .tag("provider", "anthropic")
                .tag("outcome", "ok")
                .counter();
        assertEquals(2.0, callCounter.count());

        Counter errorCounter = registry.find("llm_calls_total")
                .tag("provider", "anthropic")
                .tag("outcome", "error")
                .counter();
        assertEquals(1.0, errorCounter.count());

        Counter inputCounter = registry.find("llm_tokens_total")
                .tag("provider", "anthropic")
                .tag("kind", "input")
                .counter();
        assertEquals(110.0, inputCounter.count()); // 50 + 60
    }

    @Test
    void toolTimerMultipleObservations() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        // Record multiple tool calls
        metrics.recordToolCall("read_file", ToolOutcome.OK, 100);
        metrics.recordToolCall("read_file", ToolOutcome.OK, 200);
        metrics.recordToolCall("read_file", ToolOutcome.ERROR, 50);

        Timer timer = registry.find("tool_call_duration_ms")
                .tag("tool", "read_file")
                .timer();
        assertEquals(3, timer.count());
        assertEquals(350, timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 0.1);
    }
}
