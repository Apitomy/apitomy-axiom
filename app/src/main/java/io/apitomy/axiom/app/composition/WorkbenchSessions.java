package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.assistant.AssistantContextBuilder;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import io.apitomy.axiom.app.assistant.AssistantSession;
import io.apitomy.axiom.app.assistant.AssistantSessionManager;
import io.apitomy.axiom.app.assistant.HistoryJournal;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
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

/**
 * Binds each workbench composition to one agent session, restores its conversation after a restart and
 * translates session events into {@link WorkbenchSessionEvent}s.
 * <p>
 * {@link #ensure}, {@link #send} and {@link #live} read and write the composition's session binding in their own
 * new transactions. They must be called outside any transaction: inside one they could miss the caller's
 * uncommitted rows, block on its row locks, or have the caller's stale entity overwrite the new binding.
 * {@code ensure} and {@code send} enforce this.
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
     * @throws IllegalStateException if the agent could not be started, or if called inside a transaction (the
     *         binding is read and written in new transactions, see the class docs)
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
            session.addListener(event -> onEvent(compositionId, event));
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
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the workbench agent: " + e.getMessage(), e);
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
     * Sends a user message to the composition's agent, starting it if needed.
     *
     * @param compositionId the composition id
     * @param text the message
     * @throws IOException if the message could not be sent
     * @throws IllegalStateException if called inside a transaction (see the class docs)
     */
    public void send(long compositionId, String text) throws IOException {
        requireNoTransaction("send");
        ensure(compositionId).sendMessage(text);
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
        recaps.remove(compositionId);
        CompositionEntity composition = CompositionEntity.findById(compositionId);
        if (composition == null || composition.assistantSessionId == null) {
            return;
        }
        String sessionId = composition.assistantSessionId;
        manager.destroySession(sessionId, false);
        contextBuilder.deleteSessionDirectory(AssistantSessionManager.sessionDirectory(sessionId));
    }

    private static void requireNoTransaction(String method) {
        if (QuarkusTransaction.isActive()) {
            throw new IllegalStateException("WorkbenchSessions." + method + " must be called outside a transaction");
        }
    }

    private void onEvent(long compositionId, SseEvent event) {
        try {
            switch (event.type()) {
                case "turn_complete" -> events.fire(
                        new WorkbenchSessionEvent(compositionId, WorkbenchSessionEvent.Kind.TURN_COMPLETE, null));
                case "session_ended" -> events.fire(new WorkbenchSessionEvent(compositionId,
                        WorkbenchSessionEvent.Kind.FAILED,
                        event.data().path("message").asText("The agent session ended")));
                case "session_error" -> {
                    if (!"ProviderRetry".equals(event.data().path("name").asText())) {
                        events.fire(new WorkbenchSessionEvent(compositionId, WorkbenchSessionEvent.Kind.FAILED,
                                event.data().path("message").asText(null)));
                    }
                }
                default -> {
                    // not relevant to the turn loop
                }
            }
        } catch (RuntimeException e) {
            LOG.warnf(e, "Could not deliver workbench session event %s for composition %d", event.type(),
                    compositionId);
        }
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
