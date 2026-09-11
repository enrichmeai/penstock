package com.example.agent.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.example.agent.model.ToolOutcome;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central metrics recording for the agent.
 * Tracks LLM calls, tool calls, and SSE streaming activity.
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();
    private final AtomicInteger sseStreamsActive = new AtomicInteger(0);

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Register the gauge for active SSE streams
        Gauge.builder("sse_streams_active", sseStreamsActive, AtomicInteger::get)
                .register(registry);
    }

    /**
     * Record an LLM call with token usage.
     *
     * @param provider     The LLM provider name (e.g., "anthropic", "openai")
     * @param ok           True if the call succeeded, false if it threw an exception
     * @param inputTokens  Number of input tokens consumed
     * @param outputTokens Number of output tokens produced
     */
    /**
     * How a model delivered its tool calls: structured | recovered | none.
     * A model stuck on "none" produces turns that look successful and do nothing.
     */
    public void recordToolCallFormat(String provider, String model, String format) {
        String key = "llm_tool_call_format_total|provider=" + provider
                + "|model=" + model + "|format=" + format;
        counterCache.computeIfAbsent(key, k ->
                Counter.builder("llm_tool_call_format_total")
                        .tag("provider", provider)
                        .tag("model", model)
                        .tag("format", format)
                        .register(registry)
        ).increment();
    }

    public void recordLlmCall(String provider, boolean ok, int inputTokens, int outputTokens) {
        String outcome = ok ? "ok" : "error";

        // Increment llm_calls_total counter
        String callCounterKey = "llm_calls_total|provider=" + provider + "|outcome=" + outcome;
        counterCache.computeIfAbsent(callCounterKey, key ->
                Counter.builder("llm_calls_total")
                        .tag("provider", provider)
                        .tag("outcome", outcome)
                        .register(registry)
        ).increment();

        // Increment input tokens counter
        String inputCounterKey = "llm_tokens_total|provider=" + provider + "|kind=input";
        counterCache.computeIfAbsent(inputCounterKey, key ->
                Counter.builder("llm_tokens_total")
                        .tag("provider", provider)
                        .tag("kind", "input")
                        .register(registry)
        ).increment(inputTokens);

        // Increment output tokens counter
        String outputCounterKey = "llm_tokens_total|provider=" + provider + "|kind=output";
        counterCache.computeIfAbsent(outputCounterKey, key ->
                Counter.builder("llm_tokens_total")
                        .tag("provider", provider)
                        .tag("kind", "output")
                        .register(registry)
        ).increment(outputTokens);
    }

    /**
     * Record a tool call with duration.
     *
     * @param tool        The tool name
     * @param outcome     How the call ended; the {@code outcome} label is {@code ok}, {@code refused}
     *                    or {@code error} — a refusal (the far system's decision) is counted apart
     *                    from both a success and an error
     * @param durationMs  Duration in milliseconds
     */
    public void recordToolCall(String tool, ToolOutcome outcome, long durationMs) {
        String label = outcome.metricLabel();

        // Increment tool_calls_total counter
        String callCounterKey = "tool_calls_total|tool=" + tool + "|outcome=" + label;
        counterCache.computeIfAbsent(callCounterKey, key ->
                Counter.builder("tool_calls_total")
                        .tag("tool", tool)
                        .tag("outcome", label)
                        .register(registry)
        ).increment();

        // Record duration in the timer
        String timerKey = "tool_call_duration_ms|tool=" + tool;
        timerCache.computeIfAbsent(timerKey, key ->
                Timer.builder("tool_call_duration_ms")
                        .tag("tool", tool)
                        .register(registry)
        ).record(java.time.Duration.ofMillis(durationMs));
    }

    /**
     * Record the start of an SSE stream.
     */
    public void sseStreamStarted() {
        sseStreamsActive.incrementAndGet();
    }

    /**
     * Record the end of an SSE stream.
     */
    public void sseStreamFinished() {
        sseStreamsActive.decrementAndGet();
    }

    /**
     * Record an SSE heartbeat write. {@code ok} indicates whether the write
     * succeeded; an error usually means the underlying response was closed
     * mid-stream (proxy timeout, client disconnect) and the emitter is about
     * to be removed from the registry.
     */
    public void recordSseHeartbeat(boolean ok) {
        String outcome = ok ? "ok" : "error";
        String key = "sse_heartbeats_sent_total|outcome=" + outcome;
        counterCache.computeIfAbsent(key, k ->
                Counter.builder("sse_heartbeats_sent_total")
                        .tag("outcome", outcome)
                        .register(registry)
        ).increment();
    }
}
