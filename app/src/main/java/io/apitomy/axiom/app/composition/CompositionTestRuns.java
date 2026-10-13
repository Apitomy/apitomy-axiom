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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Runs composition drafts for testing and keeps the most recent runs of each composition in memory, telling
 * the workbench about each run over SSE when it starts and when it finishes.
 */
@ApplicationScoped
public class CompositionTestRuns {

    private static final Logger LOG = Logger.getLogger(CompositionTestRuns.class);

    /** Runs kept per composition. */
    public static final int MAX_RUNS = 50;

    /** Test runs allowed in progress at once per composition. */
    public static final int MAX_CONCURRENT_RUNS = 2;

    private final Map<Long, Deque<CompositionTestRun>> runs = new ConcurrentHashMap<>();

    private final Map<Long, Semaphore> permits = new ConcurrentHashMap<>();

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
     * @throws TooManyTestRunsException when {@link #MAX_CONCURRENT_RUNS} runs are already in progress
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
        Semaphore semaphore = permits.computeIfAbsent(compositionId, k -> new Semaphore(MAX_CONCURRENT_RUNS));
        if (!semaphore.tryAcquire()) {
            throw new TooManyTestRunsException();
        }
        try {
            return execute(compositionId, item, tool, inputs != null ? inputs : Map.of(), subject, subjectId,
                    author, turnId);
        } finally {
            semaphore.release();
        }
    }

    private CompositionTestRun execute(long compositionId, CompositionItemEntity item, boolean tool,
                                       Map<String, Object> values, SubjectEntity subject, Long subjectId,
                                       String author, String turnId) {
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

        CompositionTestRun finished = copyStart(run);
        try {
            JsonNode content = service.contentOf(item);
            DraftTestRunner.Result result = tool
                    ? runner.runTool(content, values, subject)
                    : runner.runActionType(content, values, subject);
            finished.setStatus(CompositionTestRun.Status.fromValue(result.status()));
            finished.setDurationMs(result.durationMs());
            finished.setOutputs(result.outputs() != null ? values(result.outputs()) : null);
            finished.setStdout(result.stdout());
            finished.setStderr(result.stderr());
            finished.setExitCode(result.exitCode());
            finished.setProblems(new ArrayList<>(result.problems()));
            finished.setMessage(result.message());
            finished.setResolvedScript(result.resolvedScript());
        } catch (RuntimeException e) {
            LOG.warnf(e, "Test run %s of %s failed unexpectedly", run.getId(), item.name);
            finished = copyStart(run);
            finished.setStatus(CompositionTestRun.Status.ERROR);
            finished.setDurationMs(Duration.between(run.getStartedAt().toInstant(), Instant.now()).toMillis());
            finished.setProblems(new ArrayList<>());
            finished.setMessage("The test run failed unexpectedly: " + e.getMessage());
        } finally {
            replace(compositionId, finished);
            fire(compositionId, finished);
        }
        return finished;
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
        permits.remove(compositionId);
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

    private void replace(long compositionId, CompositionTestRun finished) {
        Deque<CompositionTestRun> deque = runs.get(compositionId);
        if (deque == null) {
            return;
        }
        synchronized (deque) {
            List<CompositionTestRun> copy = new ArrayList<>(deque);
            for (int i = 0; i < copy.size(); i++) {
                if (copy.get(i).getId().equals(finished.getId())) {
                    copy.set(i, finished);
                    deque.clear();
                    deque.addAll(copy);
                    return;
                }
            }
        }
    }

    private static CompositionTestRun copyStart(CompositionTestRun run) {
        CompositionTestRun copy = new CompositionTestRun();
        copy.setId(run.getId());
        copy.setItemId(run.getItemId());
        copy.setItemType(run.getItemType());
        copy.setItemName(run.getItemName());
        copy.setAuthor(run.getAuthor());
        copy.setTurnId(run.getTurnId());
        copy.setSubjectId(run.getSubjectId());
        copy.setStartedAt(run.getStartedAt());
        copy.setInputs(run.getInputs());
        return copy;
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
