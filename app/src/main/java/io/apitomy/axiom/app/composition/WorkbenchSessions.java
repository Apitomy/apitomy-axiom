package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.assistant.AssistantContextBuilder;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import io.apitomy.axiom.app.assistant.AssistantSession;
import io.apitomy.axiom.app.assistant.AssistantSessionManager;
import io.apitomy.axiom.app.assistant.HistoryJournal;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Binds each workbench composition to one agent session, restores its conversation after a restart and
 * translates session events into {@link WorkbenchSessionEvent}s.
 * <p>
 * {@link #ensure}, {@link #send} and {@link #live} read and write the composition's session binding in their own
 * new transactions. They must be called outside any transaction: inside one they could miss the caller's
 * uncommitted rows, block on its row locks, or have the caller's stale entity overwrite the new binding.
 * {@code ensure} and {@code send} enforce this.
 * <p>
 * {@link WorkbenchSessionEvent}s are fired on a single dedicated thread, never on the session's reader thread:
 * the reader thread dispatches session events while holding the session's event lock, and the observers take
 * their own locks, so firing inline could deadlock with a thread that holds an observer lock and sends a message.
 * One thread preserves the event order of each composition.
 */
@ApplicationScoped
public class WorkbenchSessions {

    private static final Logger LOG = Logger.getLogger(WorkbenchSessions.class);
    private static final String TEMPLATE_ID = "axiom-workbench";
    private static final int RECAP_ENTRIES = 20;
    private static final int RECAP_ENTRY_LENGTH = 500;

    @Inject
    AssistantSessionManager manager;

    @Inject
    CompositionService compositions;

    @Inject
    AssistantContextBuilder contextBuilder;

    @Inject
    Event<WorkbenchSessionEvent> events;

    @Inject
    ObjectMapper mapper;

    private final ConcurrentHashMap<Long, String> recaps = new ConcurrentHashMap<>();

    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "workbench-session-events");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @param compositionId the composition id
     * @return the composition's running session, or null
     * <p>
     * Must be called outside a transaction (it reads the binding in a new one, see the class docs).
     */
    public AssistantSession live(long compositionId) {
        String sessionId = QuarkusTransaction.requiringNew().call(() -> {
            CompositionEntity composition = CompositionEntity.findById(compositionId);
            return composition != null ? composition.assistantSessionId : null;
        });
        return sessionId != null ? manager.getSession(sessionId) : null;
    }

    /**
     * Returns the composition's running session, starting one (and restoring a previous conversation) if needed.
     *
     * @param compositionId the composition id
     * @return the session
     * @throws AgentUnavailableException if the agent could not be started
     * @throws IllegalStateException if called inside a transaction (the binding is read and written in new
     *         transactions, see the class docs)
     */
    public synchronized AssistantSession ensure(long compositionId) {
        requireNoTransaction("ensure");
        // Read in a fresh transaction so a caller's persistence context cannot hand us a stale binding.
        CompositionEntity composition = QuarkusTransaction.requiringNew().call(() -> compositions.get(compositionId));
        String previousId = composition.assistantSessionId;
        if (previousId != null) {
            AssistantSession existing = manager.getSession(previousId);
            if (existing != null) {
                return existing;
            }
        }
        try {
            List<SseEvent> seed = List.of();
            Path previousDir = previousId != null ? AssistantSessionManager.sessionDirectory(previousId) : null;
            if (previousDir != null && Files.isDirectory(previousDir)) {
                seed = HistoryJournal.load(previousDir.resolve(HistoryJournal.FILE_NAME), mapper);
            }
            AssistantSession session = manager.createSession(composition.name, TEMPLATE_ID, null, compositionId, seed);
            session.addListener(event -> onEvent(compositionId, session, event));
            String sessionId = session.getId();
            QuarkusTransaction.requiringNew().run(() -> {
                CompositionEntity entity = CompositionEntity.findById(compositionId);
                entity.assistantSessionId = sessionId;
                entity.updatedOn = Instant.now();
            });
            if (previousDir != null) {
                contextBuilder.deleteSessionDirectory(previousDir);
            }
            if (!seed.isEmpty()) {
                recaps.put(compositionId, recap(composition.goal, seed));
            }
            return session;
        } catch (IOException | RuntimeException e) {
            throw new AgentUnavailableException("Could not start the workbench agent: " + e.getMessage(), e);
        }
    }

    /**
     * Returns and disarms the recap armed when a conversation was restored.
     *
     * @param compositionId the composition id
     * @return the recap text, or null
     */
    public String takeRecap(long compositionId) {
        return recaps.remove(compositionId);
    }

    /**
     * Starts the composition's agent if needed and returns the index the next session event will get. Every
     * event of an exchange started by a following {@link #send} has at least this index.
     *
     * @param compositionId the composition id
     * @return the session's current event count
     * @throws AgentUnavailableException if the agent could not be started
     * @throws IllegalStateException if called inside a transaction (see the class docs)
     */
    public int nextEventIndex(long compositionId) {
        return ensure(compositionId).getEventCount();
    }

    /**
     * Sends a user message to the composition's agent, starting it if needed.
     *
     * @param compositionId the composition id
     * @param displayText the text recorded in the conversation
     * @param agentText the text sent to the agent
     * @throws IOException if the message could not be sent
     * @throws AgentUnavailableException if the agent could not be started
     * @throws IllegalStateException if called inside a transaction (see the class docs)
     */
    public void send(long compositionId, String displayText, String agentText) throws IOException {
        requireNoTransaction("send");
        ensure(compositionId).sendMessage(displayText, agentText);
    }

    /**
     * Shows a notice in the composition's conversation without sending it to the agent.
     *
     * @param compositionId the composition id
     * @param text the notice text
     */
    public void notice(long compositionId, String text) {
        AssistantSession session = live(compositionId);
        if (session != null) {
            session.addEvent(new SseEvent("assistant_text",
                    mapper.createObjectNode().put("text", text).put("notice", true)));
        }
    }

    /**
     * Interrupts the agent's current turn, if a session is running.
     *
     * @param compositionId the composition id
     */
    public void interrupt(long compositionId) {
        AssistantSession session = live(compositionId);
        if (session != null) {
            session.interrupt();
        }
    }

    /**
     * Destroys the composition's session and deletes any directory it left behind.
     *
     * @param compositionId the composition id
     */
    public void discard(long compositionId) {
        CompositionEntity composition = CompositionEntity.findById(compositionId);
        discard(compositionId, composition == null ? null : composition.assistantSessionId);
    }

    /**
     * Destroys a session that was bound to a composition and deletes its directory. Does not touch the database,
     * so it can run after the composition was deleted.
     *
     * @param compositionId the composition id
     * @param sessionId the bound session id, or null
     */
    public void discard(long compositionId, String sessionId) {
        recaps.remove(compositionId);
        if (sessionId == null) {
            return;
        }
        manager.destroySession(sessionId, false);
        contextBuilder.deleteSessionDirectory(AssistantSessionManager.sessionDirectory(sessionId));
    }

    /**
     * Waits until every session event handed off so far has been delivered. For tests.
     */
    void awaitEventsForTests() {
        try {
            eventExecutor.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Stops the event thread.
     */
    @PreDestroy
    void shutdown() {
        eventExecutor.shutdownNow();
    }

    private static void requireNoTransaction(String method) {
        if (QuarkusTransaction.isActive()) {
            throw new IllegalStateException("WorkbenchSessions." + method + " must be called outside a transaction");
        }
    }

    /**
     * Runs on the thread that added the event, inside the session's event lock: translates the event, captures
     * its index and hands it to the event thread.
     */
    private void onEvent(long compositionId, AssistantSession session, SseEvent event) {
        WorkbenchSessionEvent translated = translate(compositionId, session.getEventCount() - 1, event);
        if (translated == null) {
            return;
        }
        try {
            eventExecutor.execute(() -> {
                try {
                    events.fire(translated);
                } catch (RuntimeException e) {
                    LOG.warnf(e, "Could not deliver workbench session event %s for composition %d", event.type(),
                            compositionId);
                }
            });
        } catch (RuntimeException e) {
            LOG.warnf(e, "Could not queue workbench session event %s for composition %d", event.type(),
                    compositionId);
        }
    }

    private static WorkbenchSessionEvent translate(long compositionId, int eventIndex, SseEvent event) {
        return switch (event.type()) {
            case "turn_complete" -> new WorkbenchSessionEvent(compositionId, WorkbenchSessionEvent.Kind.TURN_COMPLETE,
                    null, eventIndex);
            case "session_ended" -> new WorkbenchSessionEvent(compositionId, WorkbenchSessionEvent.Kind.FAILED,
                    event.data().path("message").asText("The agent session ended"), eventIndex);
            case "session_error" -> "ProviderRetry".equals(event.data().path("name").asText()) ? null
                    : new WorkbenchSessionEvent(compositionId, WorkbenchSessionEvent.Kind.FAILED,
                            event.data().path("message").asText(null), eventIndex);
            default -> null;
        };
    }

    private static String recap(String goal, List<SseEvent> history) {
        List<String> entries = new ArrayList<>();
        for (SseEvent event : history) {
            if (event.data() == null || event.data().path("notice").asBoolean()) {
                continue;
            }
            if ("user_message".equals(event.type())) {
                entries.add("- user: " + truncate(event.data().path("content").asText()));
            } else if ("assistant_text".equals(event.type())) {
                entries.add("- assistant: " + truncate(event.data().path("text").asText()));
            }
        }
        List<String> recent = entries.subList(Math.max(0, entries.size() - RECAP_ENTRIES), entries.size());
        StringBuilder text = new StringBuilder();
        text.append("This conversation is being resumed after Axiom restarted. ")
                .append("Your previous process ended, so here is a recap.\n");
        text.append("Goal: ").append(goal == null || goal.isBlank() ? "(none given)" : goal).append('\n');
        text.append("Recent conversation (oldest first):\n");
        recent.forEach(line -> text.append(line).append('\n'));
        text.append("Call get_composition before making changes.");
        return text.toString();
    }

    private static String truncate(String text) {
        return text.length() > RECAP_ENTRY_LENGTH ? text.substring(0, RECAP_ENTRY_LENGTH) + "…" : text;
    }
}
