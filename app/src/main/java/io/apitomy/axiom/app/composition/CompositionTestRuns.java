package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.CompositionTestRun;
import io.apitomy.axiom.api.beans.CompositionTestValues;
import io.apitomy.axiom.app.DraftTestRunner;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.events.SseEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs composition drafts for testing and keeps the most recent runs of each composition in memory, telling
 * the workbench about each run over SSE when it starts and when it finishes.
 */
@ApplicationScoped
public class CompositionTestRuns {

    private static final Logger LOG = Logger.getLogger(CompositionTestRuns.class);

    /** Runs kept per composition. */
    public static final int MAX_RUNS = 50;

    private final Map<Long, Deque<CompositionTestRun>> runs = new ConcurrentHashMap<>();

    @Inject
    CompositionService service;

    @Inject
    DraftTestRunner runner;

    @Inject
    ObjectMapper mapper;

    @Inject
    Event<SseEvent> sseEvents;

    /**
     * Runs an action type or tool draft and records the run.
     *
     * @param compositionId the composition id
     * @param item          the draft (not the workflow)
     * @param inputs        input or parameter values
     * @param subjectId     the subject to run in, or {@code null}
     * @param author        {@link CompositionService#AUTHOR_USER} or {@link CompositionService#AUTHOR_AI}
     * @param turnId        the AI turn id, or {@code null}
     * @return the finished run
     * @throws IllegalArgumentException for the workflow or an unknown subject
     */
    public CompositionTestRun run(long compositionId, CompositionItemEntity item, Map<String, Object> inputs,
                                  Long subjectId, String author, String turnId) {
        boolean tool = "tool".equals(item.itemType);
        if (!tool && !"action-type".equals(item.itemType)) {
            throw new IllegalArgumentException("Only action types and tools can be test-run");
        }
        SubjectEntity subject = null;
        if (subjectId != null) {
            subject = SubjectEntity.findById(subjectId);
            if (subject == null) {
                throw new IllegalArgumentException("Subject not found: " + subjectId);
            }
        }
        Map<String, Object> values = inputs != null ? inputs : Map.of();
        CompositionTestRun run = new CompositionTestRun();
        run.setId(UUID.randomUUID().toString());
        run.setItemId(item.id);
        run.setItemType(CompositionTestRun.ItemType.fromValue(item.itemType));
        run.setItemName(item.name);
        run.setAuthor(CompositionTestRun.Author.fromValue(author));
        run.setTurnId(turnId);
        run.setSubjectId(subjectId);
        run.setStatus(CompositionTestRun.Status.RUNNING);
        run.setStartedAt(Date.from(Instant.now()));
        run.setInputs(values(values));
        remember(compositionId, run);
        fire(compositionId, run);

        JsonNode content = service.contentOf(item);
        DraftTestRunner.Result result = tool
                ? runner.runTool(content, values, subject)
                : runner.runActionType(content, values, subject);
        run.setStatus(CompositionTestRun.Status.fromValue(result.status()));
        run.setDurationMs(result.durationMs());
        run.setOutputs(result.outputs() != null ? values(result.outputs()) : null);
        run.setStdout(result.stdout());
        run.setStderr(result.stderr());
        run.setExitCode(result.exitCode());
        run.setProblems(new ArrayList<>(result.problems()));
        run.setMessage(result.message());
        run.setResolvedScript(result.resolvedScript());
        fire(compositionId, run);
        return run;
    }

    /**
     * Lists a composition's recent runs.
     *
     * @param compositionId the composition id
     * @return the runs, newest first
     */
    public List<CompositionTestRun> list(long compositionId) {
        Deque<CompositionTestRun> deque = runs.get(compositionId);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    /**
     * Drops the runs of a deleted composition.
     *
     * @param compositionId the composition id
     */
    public void forget(long compositionId) {
        runs.remove(compositionId);
    }

    private void remember(long compositionId, CompositionTestRun run) {
        Deque<CompositionTestRun> deque = runs.computeIfAbsent(compositionId, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addFirst(run);
            while (deque.size() > MAX_RUNS) {
                deque.removeLast();
            }
        }
    }

    private void fire(long compositionId, CompositionTestRun run) {
        try {
            sseEvents.fire(SseEvent.compositionTestRun(compositionId, mapper.writeValueAsString(run)));
        } catch (JsonProcessingException e) {
            LOG.warnf("Could not serialize test run %s: %s", run.getId(), e.getMessage());
        }
    }

    private static CompositionTestValues values(Map<String, Object> map) {
        CompositionTestValues values = new CompositionTestValues();
        map.forEach(values::setAdditionalProperty);
        return values;
    }
}
