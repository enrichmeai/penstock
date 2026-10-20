package com.example.agent.tools;

import com.example.agent.model.ToolResult;

import java.util.Map;

/**
 * A tool exposed to the LLM. Each tool is a single capability
 * (read_file, write_file, shell, etc).
 */
public interface Tool {

    /** Unique tool name, e.g. "read_file". */
    String name();

    /** Description shown to the LLM. */
    String description();

    /** JSON-Schema for {@code arguments}. */
    Map<String, Object> inputSchema();

    /**
     * Execute the tool with the given arguments and return a result.
     *
     * {@code context} says who this call acts for; it is resolved on the
     * request thread and passed explicitly (never read thread-local security
     * state here — the agent loop runs on pooled threads without one). Tools
     * that don't need identity ignore it.
     */
    ToolResult execute(String callId, Map<String, Object> arguments, ToolContext context);

    /**
     * A line this tool adds to the system prompt while it is registered, or {@code null}. Used by
     * {@code web_fetch} (#176) to say that fetched content is data, not instructions.
     */
    default String systemPromptNote() {
        return null;
    }

    /** Provider-neutral spec for the LLM. */
    default ToolSpec spec() {
        return new ToolSpec(name(), description(), inputSchema());
    }
}
