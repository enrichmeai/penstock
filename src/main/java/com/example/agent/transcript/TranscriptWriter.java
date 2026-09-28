package com.example.agent.transcript;

import com.example.agent.model.ChatMessage;
import com.example.agent.model.Role;
import com.example.agent.model.Session;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serialises a {@link Session} to the Markdown transcript format consumed
 * by {@link TranscriptReader}.
 *
 * Layout:
 * <pre>
 * # {title}
 * Session: {id}
 * Created: {iso8601}
 *
 * ## User
 * ...text...
 *
 * ## Assistant
 * ...text...
 * ```tool_call
 * {"name":"...", "arguments": {...}}
 * ```
 *
 * ## Tool
 * ```tool_result
 * {"callId":"...", "content":"...", "isError":false, "outcome":"OK"}
 * ```
 * </pre>
 *
 * SYSTEM messages are skipped — they are configured server-side and not
 * part of the user-visible transcript.
 */
@Component
public class TranscriptWriter {

    /** Keys of a {@code tool_result} block; {@link TranscriptReader} reads the same. */
    static final String RESULT_CALL_ID = "callId";
    static final String RESULT_CONTENT = "content";
    static final String RESULT_IS_ERROR = "isError";
    static final String RESULT_OUTCOME = "outcome";

    private final ObjectMapper mapper;

    public TranscriptWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Session session) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(session.getTitle() == null ? "" : session.getTitle()).append('\n');
        sb.append("Session: ").append(session.getId()).append('\n');
        sb.append("Created: ")
                .append(DateTimeFormatter.ISO_INSTANT.format(session.getCreatedAt()))
                .append('\n');

        for (ChatMessage m : session.getHistory()) {
            if (m.role() == Role.SYSTEM) continue;
            sb.append('\n');
            appendMessage(sb, m);
        }
        return sb.toString();
    }

    private void appendMessage(StringBuilder sb, ChatMessage m) {
        switch (m.role()) {
            case USER      -> sb.append("## User\n");
            case ASSISTANT -> sb.append("## Assistant\n");
            case TOOL      -> sb.append("## Tool\n");
            default        -> sb.append("## ").append(m.role().name()).append('\n');
        }

        if (m.text() != null && !m.text().isEmpty()) {
            sb.append(m.text());
            if (!m.text().endsWith("\n")) sb.append('\n');
        }

        if (m.toolCalls() != null) {
            for (ToolCall tc : m.toolCalls()) {
                sb.append("```tool_call\n");
                sb.append(writeJson(toolCallJson(tc))).append('\n');
                sb.append("```\n");
            }
        }
        if (m.toolResults() != null) {
            for (ToolResult tr : m.toolResults()) {
                sb.append("```tool_result\n");
                sb.append(writeJson(toolResultJson(tr))).append('\n');
                sb.append("```\n");
            }
        }
    }

    private static Map<String, Object> toolCallJson(ToolCall tc) {
        // Preserve key order: id, name, arguments.
        Map<String, Object> m = new LinkedHashMap<>();
        if (tc.id() != null) m.put("id", tc.id());
        m.put("name", tc.name());
        m.put("arguments", tc.arguments() == null ? Map.of() : tc.arguments());
        return m;
    }

    private static Map<String, Object> toolResultJson(ToolResult tr) {
        // isError stays, before outcome, so a transcript reads as it did; outcome is the
        // field of record and is what a re-import restores.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(RESULT_CALL_ID, tr.callId());
        m.put(RESULT_CONTENT, tr.content());
        m.put(RESULT_IS_ERROR, tr.isError());
        m.put(RESULT_OUTCOME, tr.outcome());
        return m;
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to serialise transcript JSON", ex);
        }
    }
}
