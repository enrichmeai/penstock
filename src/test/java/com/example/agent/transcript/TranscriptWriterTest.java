package com.example.agent.transcript;

import com.example.agent.model.ChatMessage;
import com.example.agent.model.Role;
import com.example.agent.model.Session;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptWriterTest {

    @Test
    void writesUserAssistantToolSequence() {
        TranscriptWriter writer = new TranscriptWriter(new ObjectMapper());

        // A session with a known id/createdAt so we can assert byte-for-byte.
        Session s = new RecordingSession("sess-123", Instant.parse("2026-05-23T10:15:30Z"));
        s.setTitle("Hello world");
        s.add(new ChatMessage(Role.USER, "Read build.gradle", null, null,
                Instant.parse("2026-05-23T10:15:31Z")));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", "build.gradle");
        ToolCall tc = new ToolCall("call-1", "read_file", args);
        s.add(new ChatMessage(Role.ASSISTANT, "I'll read it.",
                List.of(tc), null, Instant.parse("2026-05-23T10:15:32Z")));

        ToolResult tr = ToolResult.ok("call-1", "plugins { id 'java' }");
        s.add(new ChatMessage(Role.TOOL, null, null, List.of(tr),
                Instant.parse("2026-05-23T10:15:33Z")));

        String md = writer.write(s);

        String expected =
                "# Hello world\n" +
                "Session: sess-123\n" +
                "Created: 2026-05-23T10:15:30Z\n" +
                "\n" +
                "## User\n" +
                "Read build.gradle\n" +
                "\n" +
                "## Assistant\n" +
                "I'll read it.\n" +
                "```tool_call\n" +
                "{\"id\":\"call-1\",\"name\":\"read_file\",\"arguments\":{\"path\":\"build.gradle\"}}\n" +
                "```\n" +
                "\n" +
                "## Tool\n" +
                "```tool_result\n" +
                "{\"callId\":\"call-1\",\"content\":\"plugins { id 'java' }\",\"isError\":false,\"outcome\":\"OK\"}\n" +
                "```\n";

        assertEquals(expected, md);
        // Sanity: every section heading we expect is present.
        assertTrue(md.contains("## User"));
        assertTrue(md.contains("## Assistant"));
        assertTrue(md.contains("## Tool"));
    }

    /** Test double that lets us pin a session's id + createdAt for assertions. */
    static final class RecordingSession extends Session {
        private final String id;
        private final Instant createdAt;
        RecordingSession(String id, Instant createdAt) {
            super("anonymous");
            this.id = id;
            this.createdAt = createdAt;
        }
        @Override public String getId() { return id; }
        @Override public Instant getCreatedAt() { return createdAt; }
    }
}
