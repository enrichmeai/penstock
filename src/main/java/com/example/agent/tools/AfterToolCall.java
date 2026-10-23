package com.example.agent.tools;

import com.example.agent.model.ToolResult;

import java.util.Map;
import java.util.Optional;

/**
 * Text appended to a tool's successful result, after the tool ran (#190: a language server's
 * errors after write_file or edit_file). It never changes what the tool did, never runs for an
 * error result, and a failure inside it is logged and ignored: the tool call still succeeds.
 */
public interface AfterToolCall {

    /** The text to append, or empty to append nothing. */
    Optional<String> after(String toolName, Map<String, Object> arguments, ToolResult result);
}
