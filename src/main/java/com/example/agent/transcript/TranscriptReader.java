package com.example.agent.transcript;

import com.example.agent.model.ChatMessage;
import com.example.agent.model.Role;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolOutcome;
import com.example.agent.model.ToolResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a transcript produced by {@link TranscriptWriter} back into a
 * sequence of {@link ChatMessage} plus a derived title.
 *
 * Format invariants we rely on:
 *  - Sections start with a line matching {@code ^## (User|Assistant|Tool)\s*$}.
 *  - Within an ASSISTANT section, text precedes any fenced ```tool_call blocks.
 *  - Within a TOOL section, content is one or more ```tool_result blocks.
 *
 * Per-message timestamps are not stored; readers stamp {@link Instant#now()}
 * onto each parsed message.
 */
@Component
public class TranscriptReader {

    private static final Pattern HEADING =
            Pattern.compile("^## (User|Assistant|Tool)\\s*$");
    private static final Pattern FENCE_OPEN =
            Pattern.compile("^```(tool_call|tool_result)\\s*$");

    private final ObjectMapper mapper;

    public TranscriptReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Parsed parse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Empty transcript");
        }
        String[] lines = body.split("\n", -1);

        String title = "Imported session";
        int i = 0;
        // Optional H1 title line.
        if (i < lines.length && lines[i].startsWith("# ")) {
            title = lines[i].substring(2).trim();
            i++;
        }
        // Skip header metadata until first "## " section heading or blank line.
        while (i < lines.length && !HEADING.matcher(lines[i]).matches()) {
            i++;
        }

        List<ChatMessage> messages = new ArrayList<>();
        while (i < lines.length) {
            Matcher h = HEADING.matcher(lines[i]);
            if (!h.matches()) { i++; continue; }
            String role = h.group(1);
            i++;
            // Collect lines until the next heading.
            List<String> section = new ArrayList<>();
            while (i < lines.length && !HEADING.matcher(lines[i]).matches()) {
                section.add(lines[i]);
                i++;
            }
            ChatMessage parsed = parseSection(role, section);
            if (parsed != null) messages.add(parsed);
        }

        if (messages.isEmpty()) {
            throw new IllegalArgumentException("Transcript contains no messages");
        }
        return new Parsed(title, messages);
    }

    private ChatMessage parseSection(String role, List<String> lines) {
        // Extract fenced ```tool_call / ```tool_result blocks; remaining lines are text.
        List<String> textLines = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        List<ToolResult> toolResults = new ArrayList<>();

        int i = 0;
        while (i < lines.size()) {
            Matcher fm = FENCE_OPEN.matcher(lines.get(i));
            if (fm.matches()) {
                String kind = fm.group(1);
                i++;
                StringBuilder json = new StringBuilder();
                while (i < lines.size() && !"```".equals(lines.get(i))) {
                    if (json.length() > 0) json.append('\n');
                    json.append(lines.get(i));
                    i++;
                }
                if (i >= lines.size()) {
                    throw new IllegalArgumentException("Unterminated ```" + kind + " block");
                }
                i++; // consume closing fence
                if ("tool_call".equals(kind)) {
                    toolCalls.add(parseToolCall(json.toString()));
                } else {
                    toolResults.add(parseToolResult(json.toString()));
                }
            } else {
                textLines.add(lines.get(i));
                i++;
            }
        }

        String text = joinTrim(textLines);
        Instant ts = Instant.now();
        return switch (role) {
            case "User"      -> new ChatMessage(Role.USER, text, null, null, ts);
            case "Assistant" -> new ChatMessage(Role.ASSISTANT, text, toolCalls, null, ts);
            case "Tool"      -> new ChatMessage(Role.TOOL, null, null, toolResults, ts);
            default          -> null;
        };
    }

    private ToolCall parseToolCall(String json) {
        try {
            Map<String, Object> m = mapper.readValue(json, new TypeReference<>() {});
            String id = (String) m.get("id");
            String name = (String) m.get("name");
            Object args = m.get("arguments");
            @SuppressWarnings("unchecked")
            Map<String, Object> argMap = (args instanceof Map<?, ?>) ? (Map<String, Object>) args : Map.of();
            if (name == null) throw new IllegalArgumentException("tool_call missing 'name'");
            return new ToolCall(id, name, argMap);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid tool_call JSON: " + ex.getMessage());
        }
    }

    private ToolResult parseToolResult(String json) {
        try {
            Map<String, Object> m = mapper.readValue(json, new TypeReference<>() {});
            String callId = (String) m.get("callId");
            String content = (String) m.get("content");
            Object err = m.get("isError");
            boolean isError = err instanceof Boolean && (Boolean) err;
            return new ToolResult(callId, content == null ? "" : content, isError ? ToolOutcome.ERROR : ToolOutcome.OK);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid tool_result JSON: " + ex.getMessage());
        }
    }

    private static String joinTrim(List<String> lines) {
        // Drop trailing empties so we don't accumulate the blank line before the next "##".
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).isEmpty()) end--;
        int start = 0;
        while (start < end && lines.get(start).isEmpty()) start++;
        if (start >= end) return "";
        StringBuilder sb = new StringBuilder();
        for (int k = start; k < end; k++) {
            if (k > start) sb.append('\n');
            sb.append(lines.get(k));
        }
        return sb.toString();
    }

    /** Result of parsing: a title plus the message stream. */
    public record Parsed(String title, List<ChatMessage> messages) { }
}
