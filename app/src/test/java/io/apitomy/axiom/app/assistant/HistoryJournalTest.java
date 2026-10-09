package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the history journal round-trips events and mirrors in-memory reset semantics.
 */
class HistoryJournalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SseEvent event(String type, String text) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("text", text);
        return new SseEvent(type, data);
    }

    @Test
    void roundTripsEventsInOrder(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(HistoryJournal.FILE_NAME);
        HistoryJournal journal = new HistoryJournal(file, MAPPER);
        journal.accept(event("user_message", "hi"));
        journal.accept(event("assistant_text", "hello\nthere"));

        List<SseEvent> loaded = HistoryJournal.load(file, MAPPER);
        assertEquals(2, loaded.size());
        assertEquals("assistant_text", loaded.get(1).type());
        assertEquals("hello\nthere", loaded.get(1).data().path("text").asText());
    }

    @Test
    void loadStartsAtLastConversationReset(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(HistoryJournal.FILE_NAME);
        HistoryJournal journal = new HistoryJournal(file, MAPPER);
        journal.accept(event("user_message", "old"));
        journal.accept(event("conversation_reset", ""));
        journal.accept(event("user_message", "new"));

        List<SseEvent> loaded = HistoryJournal.load(file, MAPPER);
        assertEquals(List.of("conversation_reset", "user_message"),
                loaded.stream().map(SseEvent::type).toList());
    }

    @Test
    void skipsMalformedLinesAndMissingFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(HistoryJournal.FILE_NAME);
        assertTrue(HistoryJournal.load(file, MAPPER).isEmpty());
        new HistoryJournal(file, MAPPER).accept(event("user_message", "ok"));
        Files.writeString(file, "{not json\n", StandardOpenOption.APPEND);
        assertEquals(1, HistoryJournal.load(file, MAPPER).size());
    }

    @Test
    void writeFailureDoesNotThrow(@TempDir Path dir) {
        HistoryJournal journal = new HistoryJournal(dir.resolve("missing-dir/history.jsonl"), MAPPER);
        assertDoesNotThrow(() -> journal.accept(event("user_message", "x")));
    }
}
