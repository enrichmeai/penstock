package com.example.agent.transcript;

import com.example.agent.model.ChatMessage;
import com.example.agent.model.Session;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolOutcome;
import com.example.agent.model.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TranscriptReaderTest {

    @Test
    void roundTripsThroughWriterAndReader() {
        ObjectMapper mapper = new ObjectMapper();
        TranscriptWriter writer = new TranscriptWriter(mapper);
        TranscriptReader reader = new TranscriptReader(mapper);

        Session s = new TranscriptWriterTest.RecordingSession(
                "sess-abc", Instant.parse("2026-05-23T12:00:00Z"));
        s.setTitle("Round trip");

        s.add(ChatMessage.user("Hi"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("x", 1);
        ToolCall tc = new ToolCall("c1", "do_it", args);
        s.add(ChatMessage.assistant("Working on it", List.of(tc)));
        s.add(ChatMessage.tool(List.of(ToolResult.ok("c1", "done"))));

        String md = writer.write(s);
        TranscriptReader.Parsed parsed = reader.parse(md);

        assertEquals("Round trip", parsed.title());
        assertEquals(3, parsed.messages().size());

        // USER message — compare role + text (timestamps drift on import).
        ChatMessage origUser = s.getHistory().get(0);
        ChatMessage roundUser = parsed.messages().get(0);
        assertEquals(origUser.role(), roundUser.role());
        assertEquals(origUser.text(), roundUser.text());
        assertEquals(List.of(), roundUser.toolCalls());
        assertEquals(List.of(), roundUser.toolResults());

        // ASSISTANT message with tool call.
        ChatMessage origAsst = s.getHistory().get(1);
        ChatMessage roundAsst = parsed.messages().get(1);
        assertEquals(origAsst.role(), roundAsst.role());
        assertEquals(origAsst.text(), roundAsst.text());
        assertEquals(1, roundAsst.toolCalls().size());
        ToolCall got = roundAsst.toolCalls().get(0);
        assertEquals("c1", got.id());
        assertEquals("do_it", got.name());
        // Jackson reads `1` as Integer; original Map also held Integer.
        assertEquals(1, ((Number) got.arguments().get("x")).intValue());

        // TOOL message with result.
        ChatMessage origTool = s.getHistory().get(2);
        ChatMessage roundTool = parsed.messages().get(2);
        assertEquals(origTool.role(), roundTool.role());
        assertEquals(1, roundTool.toolResults().size());
        ToolResult tr = roundTool.toolResults().get(0);
        assertEquals("c1", tr.callId());
        assertEquals("done", tr.content());
        assertEquals(false, tr.isError());

        // Per-message timestamp is regenerated on import — just assert it's present.
        for (ChatMessage m : parsed.messages()) {
            assertNotNull(m.timestamp());
        }
    }

    @Test
    void aRefusalSurvivesTheRoundTrip() {
        ObjectMapper mapper = new ObjectMapper();
        Session s = new TranscriptWriterTest.RecordingSession("sess-ref", Instant.parse("2026-09-10T06:00:00Z"));
        s.setTitle("Refused");
        s.add(ChatMessage.user("read the playbook"));
        s.add(ChatMessage.assistant("Reading.", List.of(new ToolCall("c1", "pod", Map.of("type", "read")))));
        s.add(ChatMessage.tool(List.of(ToolResult.refused("c1", "Refused: the owner has not granted access."))));

        String md = new TranscriptWriter(mapper).write(s);
        TranscriptReader.Parsed parsed = new TranscriptReader(mapper).parse(md);

        ToolResult tr = parsed.messages().get(2).toolResults().get(0);
        assertEquals(ToolOutcome.REFUSED, tr.outcome(), md);
        assertEquals(false, tr.isError());
        assertEquals("Refused: the owner has not granted access.", tr.content());
    }

    @Test
    void aTranscriptWrittenBeforeOutcomesExistedReadsTheFlag() {
        String md = "# Old\n"
                + "Session: sess-old\n"
                + "Created: 2026-05-23T10:15:30Z\n"
                + "\n"
                + "## User\n"
                + "go\n"
                + "\n"
                + "## Assistant\n"
                + "```tool_call\n"
                + "{\"id\":\"c1\",\"name\":\"shell\",\"arguments\":{}}\n"
                + "```\n"
                + "\n"
                + "## Tool\n"
                + "```tool_result\n"
                + "{\"callId\":\"c1\",\"content\":\"boom\",\"isError\":true}\n"
                + "```\n"
                + "```tool_result\n"
                + "{\"callId\":\"c1\",\"content\":\"fine\",\"isError\":false}\n"
                + "```\n";

        TranscriptReader.Parsed parsed = new TranscriptReader(new ObjectMapper()).parse(md);

        List<ToolResult> results = parsed.messages().get(2).toolResults();
        assertEquals(ToolOutcome.ERROR, results.get(0).outcome());
        assertEquals(ToolOutcome.OK, results.get(1).outcome());
    }
}
