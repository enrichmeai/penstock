package com.example.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three outcomes, on the wire and back — including the payload written before {@code outcome} existed. */
class ToolResultTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aRefusalIsNeitherOkNorAnError() {
        ToolResult refused = ToolResult.refused("c1", "Refused: the owner has not granted access.");

        assertEquals(ToolOutcome.REFUSED, refused.outcome());
        assertFalse(refused.isError(), "a refusal is an answer, not a malfunction");
        assertEquals(ToolOutcome.OK, ToolResult.ok("c2", "doc").outcome());
        assertEquals(ToolOutcome.ERROR, ToolResult.error("c3", "boom").outcome());
        assertTrue(ToolResult.error("c3", "boom").isError());
    }

    @Test
    void theWireCarriesTheOutcomeAndTheLegacyFlag() throws Exception {
        assertEquals("{\"callId\":\"c1\",\"content\":\"no\",\"outcome\":\"REFUSED\",\"isError\":false}",
                mapper.writeValueAsString(ToolResult.refused("c1", "no")));
        assertEquals("{\"callId\":\"c2\",\"content\":\"boom\",\"outcome\":\"ERROR\",\"isError\":true}",
                mapper.writeValueAsString(ToolResult.error("c2", "boom")));
        assertEquals("{\"callId\":\"c3\",\"content\":\"doc\",\"outcome\":\"OK\",\"isError\":false}",
                mapper.writeValueAsString(ToolResult.ok("c3", "doc")));
    }

    @Test
    void todaysPayloadRoundTrips() throws Exception {
        for (ToolResult original : new ToolResult[] {
                ToolResult.ok("c1", "doc"), ToolResult.refused("c2", "no"), ToolResult.error("c3", "boom") }) {
            ToolResult read = mapper.readValue(mapper.writeValueAsString(original), ToolResult.class);
            assertEquals(original, read);
        }
    }

    @Test
    void aPayloadWrittenBeforeOutcomesExistedIsReadByItsFlag() throws Exception {
        assertEquals(ToolOutcome.ERROR,
                mapper.readValue("{\"callId\":\"c1\",\"content\":\"boom\",\"isError\":true}", ToolResult.class).outcome());
        assertEquals(ToolOutcome.OK,
                mapper.readValue("{\"callId\":\"c1\",\"content\":\"doc\",\"isError\":false}", ToolResult.class).outcome());
        assertEquals(ToolOutcome.OK,
                mapper.readValue("{\"callId\":\"c1\",\"content\":\"doc\"}", ToolResult.class).outcome());
    }

    @Test
    void theOutcomeIsTheFieldOfRecordWhenBothArePresent() throws Exception {
        ToolResult read = mapper.readValue(
                "{\"callId\":\"c1\",\"content\":\"no\",\"outcome\":\"REFUSED\",\"isError\":false}", ToolResult.class);
        assertEquals(ToolOutcome.REFUSED, read.outcome());
    }
}
