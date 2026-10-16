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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Appends every event of an assistant session to a JSON Lines file so that the conversation can be shown again
 * after a server restart. Each line is {@code {"type": ..., "data": ...}}.
 *
 * <p>Writing is asynchronous: {@link #accept} only serializes the event and enqueues it, and a single daemon
 * writer thread per journal appends lines in the order they were accepted. Disk I/O never runs on the caller's
 * thread, so it is never performed while the session's event lock is held.
 */
public final class HistoryJournal implements Consumer<SseEvent> {

    /** File name of the journal inside the session directory. */
    public static final String FILE_NAME = "history.jsonl";

    private static final Logger LOG = Logger.getLogger(HistoryJournal.class);
    private static final Object STOP = new Object();
    private static final long WAIT_SECONDS = 10;

    private final Path file;
    private final ObjectMapper mapper;
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final Thread writer;
    private volatile boolean closed;

    /**
     * Creates a journal writing to {@code file} and starts its writer thread.
     *
     * @param file the journal file; its parent directory must exist
     * @param mapper the JSON mapper
     */
    public HistoryJournal(Path file, ObjectMapper mapper) {
        this.file = file;
        this.mapper = mapper;
        this.writer = new Thread(this::writeLoop, "history-journal-" + file.getFileName());
        this.writer.setDaemon(true);
        this.writer.start();
    }

    /**
     * Enqueues one event for appending. Never blocks and never throws; write failures are logged by the writer
     * thread, so a full disk cannot break a session. Events accepted after {@link #close()} are dropped.
     *
     * @param event the event to record
     */
    @Override
    public void accept(SseEvent event) {
        if (closed) {
            return;
        }
        try {
            ObjectNode line = mapper.createObjectNode();
            line.put("type", event.type());
            line.set("data", event.data());
            queue.offer(line.toString());
        } catch (RuntimeException e) {
            LOG.warnf("Failed to serialize history event for %s: %s", file, e.getMessage());
        }
    }

    /**
     * Blocks (up to 10 seconds) until every event enqueued so far has been written. Returns immediately if the
     * journal is closed.
     */
    public void flush() {
        if (closed) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        queue.offer(latch);
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Writes the remaining events and stops the writer thread, waiting up to 10 seconds. Idempotent.
     */
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        queue.offer(STOP);
        try {
            writer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeLoop() {
        while (true) {
            Object item;
            try {
                item = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            if (item == STOP) {
                return;
            }
            if (item instanceof CountDownLatch latch) {
                latch.countDown();
            } else if (item instanceof String line) {
                try {
                    Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    LOG.warnf("Failed to append to history journal %s: %s", file, e.getMessage());
                }
            }
        }
    }

    /**
     * Loads a journal. Like the in-memory history, a {@code conversation_reset} discards earlier events.
     * Malformed lines are skipped, and a missing or non-object {@code data} becomes an empty object.
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
            JsonNode data = line.path("data");
            events.add(new SseEvent(type, data.isObject() ? data : mapper.createObjectNode()));
        }
        return events;
    }
}
