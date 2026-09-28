package com.example.agent.model;

/**
 * How a tool call ended. Three outcomes, not two: a far system that says <em>no</em> — the
 * pod owner's rule, a permission the caller lacks — has answered, and that answer is neither
 * a success to count nor a fault to retry. Telling the three apart is the point of the audit
 * trail (#8): a refused read and a granted one must never look the same in it.
 */
public enum ToolOutcome {
    /** The tool did what was asked. */
    OK("ok"),
    /** The far system declined: a decision by whoever owns the resource, not a malfunction. */
    REFUSED("refused"),
    /** The tool could not do what was asked: bad arguments, a fault, an unreachable system. */
    ERROR("error");

    private final String metricLabel;

    ToolOutcome(String metricLabel) {
        this.metricLabel = metricLabel;
    }

    /** The {@code outcome} label on {@code tool_calls_total}. */
    public String metricLabel() {
        return metricLabel;
    }
}
