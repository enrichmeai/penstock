package com.example.agent.tools;

import com.example.agent.config.AgentMetrics;
import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolResult;
import com.example.agent.service.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry of all tools exposed to the LLM.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final AgentProperties props;
    private AgentMetrics metrics;
    private AuditLogger auditLogger;

    public ToolRegistry(List<Tool> discovered, AgentProperties props) {
        this.props = props;
        discovered.forEach(t -> tools.put(t.name(), t));
        log.info("Registered {} tools: {}", tools.size(), tools.keySet());
    }

    @Autowired(required = false)
    public void setMetrics(AgentMetrics metrics) {
        this.metrics = metrics;
    }

    @Autowired(required = false)
    public void setAuditLogger(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    public Collection<Tool> all() { return tools.values(); }

    public List<ToolSpec> specs() {
        return tools.values().stream().map(Tool::spec).toList();
    }

    /**
     * Convenience overload for callers (mostly tests) that don't have a session id.
     */
    public ToolResult invoke(ToolCall call) {
        return invoke(call, null, null);
    }

    /**
     * Convenience overload for callers that don't know the acting user; audit
     * events are attributed to "anonymous".
     */
    public ToolResult invoke(ToolCall call, String sessionId) {
        return invoke(call, sessionId, null);
    }

    /**
     * Invoke a tool on behalf of a session. {@code sessionId} and {@code userId}
     * feed audit logging and the {@link ToolContext} handed to the tool; either
     * may be {@code null}. The user must be resolved by the caller — neither
     * audit writes nor tool executions can read the SecurityContext of the
     * invoking thread (the agent loop runs on pooled threads without one).
     */
    public ToolResult invoke(ToolCall call, String sessionId, String userId) {
        return invoke(call, new ToolContext(userId, sessionId));
    }

    /**
     * Invoke a tool with a fully resolved {@link ToolContext} — identity plus what
     * the request that started the turn carried (its request ID, and the caller's
     * bearer for tools that act as the signed-in user). The agent loop uses this
     * form; the context is handed to the tool as is and attributed in the audit log.
     */
    public ToolResult invoke(ToolCall call, ToolContext context) {
        Tool t = tools.get(call.name());
        if (t == null) {
            return ToolResult.error(call.id(), "Unknown tool: " + call.name());
        }
        String userId = context.userId();
        String sessionId = context.sessionId();
        long start = System.nanoTime();
        try {
            ToolResult r = t.execute(call.id(), call.arguments() == null ? Map.of() : call.arguments(), context);
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.info("Tool {} ({} ms) isError={}", call.name(), durationMs, r.isError());

            // Record metrics for successful call
            if (metrics != null) {
                metrics.recordToolCall(call.name(), !r.isError(), durationMs);
            }

            // Audit the tool call
            if (auditLogger != null) {
                int contentBytes = r.content() == null ? 0 : r.content().getBytes(StandardCharsets.UTF_8).length;
                auditLogger.toolCall(userId, sessionId, call.name(), call.arguments() == null ? Map.of() : call.arguments(), !r.isError(), contentBytes);
            }

            // Check if output needs truncation
            if (r.content() != null) {
                int maxBytes = props.getTools().getMaxOutputBytes();
                byte[] contentBytes = r.content().getBytes(StandardCharsets.UTF_8);
                if (contentBytes.length > maxBytes) {
                    r = truncateToolResult(r, maxBytes);
                }
            }

            return r;
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("Tool {} threw", call.name(), e);

            // Record metrics for failed call
            if (metrics != null) {
                metrics.recordToolCall(call.name(), false, durationMs);
            }

            // Audit the failed tool call
            if (auditLogger != null) {
                auditLogger.toolCall(userId, sessionId, call.name(), call.arguments() == null ? Map.of() : call.arguments(), false, 0);
            }

            return ToolResult.error(call.id(), "Tool threw: " + e.getMessage());
        }
    }

    /**
     * Truncate tool output to maxBytes, preserving 80% head and 20% tail
     * with an ellipsis marker in the middle.
     */
    private ToolResult truncateToolResult(ToolResult r, int maxBytes) {
        String content = r.content();
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        int omittedBytes = contentBytes.length - maxBytes;

        // Ellipsis marker (approximately 80 characters)
        String marker = "\n\n... [output truncated, " + omittedBytes + " bytes omitted] ...\n\n";
        byte[] markerBytes = marker.getBytes(StandardCharsets.UTF_8);

        // Calculate available space for head and tail
        int availableBytes = maxBytes - markerBytes.length;
        int headBytes = (int) (availableBytes * 0.8);
        int tailBytes = availableBytes - headBytes;

        // Extract head (first headBytes of UTF-8)
        String head = new String(contentBytes, 0, headBytes, StandardCharsets.UTF_8);
        // Ensure we don't split a multibyte character; trim to valid UTF-8 boundary
        head = head.substring(0, head.length());

        // Extract tail (last tailBytes of UTF-8)
        int tailStartIdx = Math.max(0, contentBytes.length - tailBytes);
        String tail = new String(contentBytes, tailStartIdx, contentBytes.length - tailStartIdx, StandardCharsets.UTF_8);
        tail = tail.substring(0, tail.length());

        String truncated = head + marker + tail;
        log.info("Tool output truncated from {} to {} bytes", contentBytes.length, truncated.getBytes(StandardCharsets.UTF_8).length);

        return new ToolResult(r.callId(), truncated, r.outcome());
    }
}
