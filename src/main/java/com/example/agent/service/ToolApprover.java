package com.example.agent.service;

import com.example.agent.model.ToolCall;
import com.example.agent.tools.ToolContext;

import java.util.function.Consumer;

/**
 * Puts one tool call to the user when its policy is {@code ask} (#179) and blocks until they
 * answer or the wait times out. Web and REST use {@link ToolApprovals}; the ACP profile puts the
 * question to the editor through its own permission prompt instead.
 */
public interface ToolApprover {

    /**
     * @param approved   whether the call may run
     * @param answeredBy who answered ({@code null} when nobody did)
     * @param timedOut   no answer arrived in time; {@code approved} is then false
     */
    record Answer(boolean approved, String answeredBy, boolean timedOut) {
        public static Answer timeout() { return new Answer(false, null, true); }
    }

    /**
     * @param announce told about the pending call before the wait starts, so the front end can
     *                 show it (the chat stream's {@code approval} event)
     */
    Answer ask(ToolContext context, ToolCall call, Consumer<PendingApproval> announce);

    /** Called once an approved call has run (or thrown), so nothing about it outlives the call. */
    default void finished(ToolContext context, ToolCall call) {
    }
}
