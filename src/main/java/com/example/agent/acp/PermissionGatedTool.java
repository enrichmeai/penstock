package com.example.agent.acp;

/**
 * Marker for a {@code Tool} bean that already consults {@link PermissionGate} itself (an ACP
 * wrapper around the real tool — see {@code AcpToolPermissionConfig}).
 *
 * <p>In {@code acp} profile, {@code ToolRegistry} sees both the always-registered raw tool
 * bean (e.g. {@code WriteFileTool}) and its gated wrapper for the same {@code name()}; Spring's
 * {@code List<Tool>} injection order between two beans with no {@code @Order} is otherwise
 * unspecified. This marker makes the override deterministic regardless of that order: a plain
 * tool never replaces an already-registered gated one, so the gated wrapper always wins in
 * {@code acp} profile, and the raw tool is registered unchanged everywhere else.
 */
public interface PermissionGatedTool extends com.example.agent.tools.Tool {
}
