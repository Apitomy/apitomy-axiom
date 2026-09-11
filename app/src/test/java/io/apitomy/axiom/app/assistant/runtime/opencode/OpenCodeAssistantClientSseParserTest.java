package io.apitomy.axiom.app.assistant.runtime.opencode;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenCodeAssistantClientSseParserTest {

    @Test
    void emitsDataOnlyEventUsingDefaultType() throws Exception {
        String sse = """
                data:{"sessionID":"s1","part":{"type":"text","text":"hello"}}

                """;
        List<OpenCodeAssistantClient.OpenCodeRawEvent> events = new ArrayList<>();

        OpenCodeAssistantClient.parseSseEvents(new BufferedReader(new StringReader(sse)), events::add);

        assertEquals(1, events.size());
        assertEquals("message", events.get(0).eventName());
        assertEquals("s1", events.get(0).payload().path("sessionID").asText());
        assertEquals("hello", events.get(0).payload().path("part").path("text").asText());
    }

    @Test
    void preservesMultiLineDataPayloadJsonSemantics() throws Exception {
        String sse = """
                event:session.message.part
                data:{
                data:"sessionID":"s1",
                data:"part":{"type":"text","text":"hello"}
                data:}

                """;
        List<OpenCodeAssistantClient.OpenCodeRawEvent> events = new ArrayList<>();

        OpenCodeAssistantClient.parseSseEvents(new BufferedReader(new StringReader(sse)), events::add);

        assertEquals(1, events.size());
        assertEquals("session.message.part", events.get(0).eventName());
        assertEquals("s1", events.get(0).payload().path("sessionID").asText());
        assertEquals("text", events.get(0).payload().path("part").path("type").asText());
        assertEquals("hello", events.get(0).payload().path("part").path("text").asText());
    }

    @Test
    void flushesPendingEventAtEofWithoutTrailingBlankLine() throws Exception {
        String sse = """
                event:session.turn.completed
                data:{"sessionID":"s1","costUsd":0.02,"inputTokens":11,"outputTokens":7,"success":true}
                """;
        List<OpenCodeAssistantClient.OpenCodeRawEvent> events = new ArrayList<>();

        OpenCodeAssistantClient.parseSseEvents(new BufferedReader(new StringReader(sse)), events::add);

        assertEquals(1, events.size());
        assertEquals("session.turn.completed", events.get(0).eventName());
        assertEquals(true, events.get(0).payload().path("success").asBoolean(false));
        assertEquals(11, events.get(0).payload().path("inputTokens").asLong());
    }
}
