package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Appends every event of an assistant session to a JSON Lines file so that the conversation can be shown again
 * after a server restart. Each line is {@code {"type": ..., "data": ...}}.
 */
public final class HistoryJournal implements Consumer<SseEvent> {

    /** File name of the journal inside the session directory. */
    public static final String FILE_NAME = "history.jsonl";

    private static final Logger LOG = Logger.getLogger(HistoryJournal.class);

    private final Path file;
    private final ObjectMapper mapper;

    /**
     * Creates a journal writing to {@code file}.
     *
     * @param file the journal file; its parent directory must exist
     * @param mapper the JSON mapper
     */
    public HistoryJournal(Path file, ObjectMapper mapper) {
        this.file = file;
        this.mapper = mapper;
    }

    /**
     * Appends one event. Failures are logged and never propagated, so a full disk cannot break a session.
     *
     * @param event the event to record
     */
    @Override
    public synchronized void accept(SseEvent event) {
        ObjectNode line = mapper.createObjectNode();
        line.put("type", event.type());
        line.set("data", event.data());
        try {
            Files.writeString(file, line.toString() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warnf("Failed to append to history journal %s: %s", file, e.getMessage());
        }
    }

    /**
     * Loads a journal. Like the in-memory history, a {@code conversation_reset} discards earlier events.
     * Malformed lines are skipped.
     *
     * @param file the journal file
     * @param mapper the JSON mapper
     * @return the events, oldest first; empty if the file does not exist
     * @throws IOException if the file exists but cannot be read
     */
    public static List<SseEvent> load(Path file, ObjectMapper mapper) throws IOException {
        List<SseEvent> events = new ArrayList<>();
        if (!Files.exists(file)) {
            return events;
        }
        for (String text : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (text.isBlank()) {
                continue;
            }
            JsonNode line;
            try {
                line = mapper.readTree(text);
            } catch (IOException e) {
                LOG.debugf("Skipping malformed history line in %s", file);
                continue;
            }
            String type = line.path("type").asText("");
            if (type.isEmpty()) {
                continue;
            }
            if ("conversation_reset".equals(type)) {
                events.clear();
            }
            events.add(new SseEvent(type, line.path("data")));
        }
        return events;
    }
}
