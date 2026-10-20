package com.example.agent.acp;

import com.example.agent.model.ToolCall;
import com.example.agent.service.PendingApproval;
import com.example.agent.service.ToolApprover;
import com.example.agent.tools.ToolContext;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.example.agent.model.ToolResult;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * In the {@code acp} profile, a tool under policy {@code ask} (#179) is put to the editor through
 * {@link PermissionGate#ask}, the same {@code session/request_permission} prompt the mutating
 * tools use, so ACP and web share one policy model. Nothing is announced on a stream: the
 * editor's prompt is the announcement.
 */
@Component
@Primary
@Profile("acp")
class AcpToolApprover implements ToolApprover {

    private final PermissionGate gate;

    AcpToolApprover(PermissionGate gate) {
        this.gate = gate;
    }

    @Override
    public Answer ask(ToolContext context, ToolCall call, Consumer<PendingApproval> announce) {
        Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();
        Optional<ToolResult> refused = gate.ask(context.sessionId(), call.id(), call.name(), args);
        // An error result means the editor was never asked (no prompt context): nobody answered.
        boolean editorAnswered = refused.map(r -> !r.isError()).orElse(true);
        return new Answer(refused.isEmpty(), editorAnswered ? "editor" : null, false);
    }

    @Override
    public void finished(ToolContext context, ToolCall call) {
        gate.forget(context.sessionId(), call.id());
    }
}
