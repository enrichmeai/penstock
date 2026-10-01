package com.example.agent.acp;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.example.agent.model.ToolResult;
import com.example.agent.tools.Tool;
import com.example.agent.tools.ToolContext;
import com.example.agent.tools.ToolSpec;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Decorates a mutating {@code Tool} so that, in {@code acp} profile, every call is put to
 * {@link PermissionGate} first. See {@code AcpToolPermissionConfig} for the five instances
 * (write_file, edit_file, shell, git, pod) and CLAUDE.md "Autonomous build loop" issue #62.
 *
 * <p>Delegates {@link #name()}/{@link #description()}/{@link #inputSchema()} unchanged, so the
 * model sees exactly the same tool spec it would without this wrapper — only {@link #execute}
 * differs.
 */
final class AcpGatedTool implements Tool, PermissionGatedTool {

    private final Tool delegate;
    private final PermissionGate gate;
    private final AcpSchema.ToolKind kind;
    private final Predicate<Map<String, Object>> requiresPermission;
    private final Function<Map<String, Object>, String> title;
    private final Function<Map<String, Object>, List<AcpSchema.ToolCallLocation>> locations;

    AcpGatedTool(Tool delegate,
                PermissionGate gate,
                AcpSchema.ToolKind kind,
                Predicate<Map<String, Object>> requiresPermission,
                Function<Map<String, Object>, String> title,
                Function<Map<String, Object>, List<AcpSchema.ToolCallLocation>> locations) {
        this.delegate = delegate;
        this.gate = gate;
        this.kind = kind;
        this.requiresPermission = requiresPermission;
        this.title = title;
        this.locations = locations;
    }

    @Override public String name() { return delegate.name(); }
    @Override public String description() { return delegate.description(); }
    @Override public Map<String, Object> inputSchema() { return delegate.inputSchema(); }
    @Override public ToolSpec spec() { return delegate.spec(); }

    @Override
    public ToolResult execute(String callId, Map<String, Object> arguments, ToolContext context) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        if (!requiresPermission.test(args)) {
            return delegate.execute(callId, arguments, context);
        }
        Optional<ToolResult> refused = gate.check(context.sessionId(), callId, name(), kind,
                title.apply(args), locations.apply(args), args);
        return refused.orElseGet(() -> delegate.execute(callId, arguments, context));
    }
}
