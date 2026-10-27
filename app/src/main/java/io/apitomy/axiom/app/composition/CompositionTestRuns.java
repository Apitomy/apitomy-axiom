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
import io.apitomy.axiom.core.services.EnvironmentResolver;
import io.apitomy.axiom.core.services.MissingSecretException;
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
import java.util.Optional;
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

    private static final java.util.regex.Pattern RUN_ID = java.util.regex.Pattern.compile("[A-Za-z0-9-]{8,64}");

    /** Runs kept per composition. */
    public static final int MAX_RUNS = 50;

    /** Test runs allowed in progress at once per composition. */
    public static final int MAX_CONCURRENT_RUNS = 2;

    private final Map<Long, Deque<CompositionTestRun>> runs = new ConcurrentHashMap<>();

    private final Map<Long, Semaphore> permits = new ConcurrentHashMap<>();

    /** The raw (unresolved) environment of each run, by composition and run id; never sent to the UI. */
    private final Map<Long, Map<String, Map<String, String>>> environments = new ConcurrentHashMap<>();

    @Inject
    EnvironmentResolver environmentResolver;

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
        return run(compositionId, item, inputs, Map.of(), subjectId, author, turnId, null);
    }

    /**
     * Runs an action type or tool draft and records the run under the caller's run id, so the caller can follow
     * the run (e.g. show it) before this method returns.
     *
     * @param compositionId the composition id
     * @param item          the draft (not the workflow)
     * @param inputs        input or parameter values
     * @param environment   tools only: environment variables for the run ({@code ${secret:NAME}} allowed);
     *                      action types use their own environment
     * @param subjectId     the subject to run in, or {@code null}
     * @param author        {@link CompositionService#AUTHOR_USER} or {@link CompositionService#AUTHOR_AI}
     * @param turnId        the AI turn id, or {@code null}
     * @param runId         the id to use; a new id is generated when it is null, invalid or already used
     * @return the finished run
     * @throws IllegalArgumentException for the workflow or an unknown subject
     * @throws TooManyTestRunsException when the composition already has the maximum number of runs in progress
     */
    public CompositionTestRun run(long compositionId, CompositionItemEntity item, Map<String, Object> inputs,
                                  Map<String, String> environment, Long subjectId, String author, String turnId,
                                  String runId) {
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
            return execute(compositionId, item, tool, inputs != null ? inputs : Map.of(),
                    environment != null ? environment : Map.of(), subject, subjectId, author, turnId,
                    runIdFor(compositionId, runId));
        } finally {
            semaphore.release();
        }
    }

    /**
     * Records a test the AI suggests for the user to run. Nothing is executed and no concurrency permit is
     * taken.
     *
     * @param compositionId the composition id
     * @param item          the action type or tool draft
     * @param inputs        suggested input or parameter values
     * @param turnId        the AI turn id, or {@code null}
     * @return the stored suggested run
     * @throws IllegalArgumentException for anything but an action type or tool
     */
    public CompositionTestRun suggest(long compositionId, CompositionItemEntity item, Map<String, Object> inputs,
                                      String turnId) {
        if (!"tool".equals(item.itemType) && !"action-type".equals(item.itemType)) {
            throw new IllegalArgumentException("Only action types and tools can be test-run");
        }
        CompositionTestRun run = new CompositionTestRun();
        run.setId(UUID.randomUUID().toString());
        run.setItemId(item.id);
        run.setItemType(CompositionTestRun.ItemType.fromValue(item.itemType));
        run.setItemName(item.name);
        run.setAuthor(CompositionTestRun.Author.fromValue(CompositionService.AUTHOR_AI));
        run.setTurnId(turnId);
        run.setStatus(CompositionTestRun.Status.SUGGESTED);
        run.setExecutionMode(executionModeOf(item));
        run.setStartedAt(Date.from(Instant.now()));
        run.setInputs(values(inputs != null ? inputs : Map.of()));
        remember(compositionId, run);
        fire(compositionId, run);
        return run;
    }

    private CompositionTestRun execute(long compositionId, CompositionItemEntity item, boolean tool,
                                       Map<String, Object> values, Map<String, String> environment,
                                       SubjectEntity subject, Long subjectId, String author, String turnId,
                                       String runId) {
        CompositionTestRun run = new CompositionTestRun();
        run.setId(runId);
        run.setItemId(item.id);
        run.setItemType(CompositionTestRun.ItemType.fromValue(item.itemType));
        run.setItemName(item.name);
        run.setAuthor(CompositionTestRun.Author.fromValue(author));
        run.setTurnId(turnId);
        run.setSubjectId(subjectId);
        run.setStatus(CompositionTestRun.Status.RUNNING);
        run.setExecutionMode(executionModeOf(item));
        run.setStartedAt(Date.from(Instant.now()));
        run.setInputs(values(values));
        remember(compositionId, run);
        fire(compositionId, run);

        CompositionTestRun finished = copyStart(run);
        try {
            JsonNode content = service.contentOf(item);
            rememberEnvironment(compositionId, runId, tool ? environment : environmentOf(content));
            DraftTestRunner.Result result = tool
                    ? runner.runTool(content, values, environment, subject)
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
     * Gets one of a composition's recent runs.
     *
     * @param compositionId the composition id
     * @param runId         the run id
     * @return the run, or empty when the composition has no such run
     */
    public Optional<CompositionTestRun> get(long compositionId, String runId) {
        return list(compositionId).stream().filter(r -> r.getId().equals(runId)).findFirst();
    }

    /**
     * Gets a run as it is shared with the workbench AI: secrets redacted and logs truncated (see
     * {@link TestRunRedactor}).
     *
     * @param compositionId the composition id
     * @param runId         the run id
     * @return the redacted run, or empty when the composition has no such run
     */
    public Optional<CompositionTestRun> agentView(long compositionId, String runId) {
        return get(compositionId, runId).map(run -> {
            Map<String, String> environment = environments.getOrDefault(compositionId, Map.of()).get(runId);
            return TestRunRedactor.redact(run, environment, secretValues(environment));
        });
    }

    /** Resolves the secrets an environment references, skipping any that cannot be resolved. */
    private List<String> secretValues(Map<String, String> environment) {
        List<String> values = new ArrayList<>();
        for (String name : TestRunRedactor.referencedSecrets(environment)) {
            try {
                String json = mapper.writeValueAsString(Map.of("v", "${secret:" + name + "}"));
                values.add(environmentResolver.resolve(json).get("v"));
            } catch (JsonProcessingException | MissingSecretException e) {
                LOG.debugf("Secret %s not resolved for redaction: %s", name, e.getMessage());
            }
        }
        return values;
    }

    /** The environment of an action type draft as a map, or an empty map. */
    private Map<String, String> environmentOf(JsonNode content) {
        JsonNode env = content.path("environment");
        try {
            if (env.isTextual() && !env.asText().isBlank()) {
                env = mapper.readTree(env.asText());
            }
            if (env.isObject()) {
                Map<String, String> result = new java.util.LinkedHashMap<>();
                env.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText()));
                return result;
            }
        } catch (JsonProcessingException e) {
            LOG.debugf("Unreadable action type environment: %s", e.getMessage());
        }
        return Map.of();
    }

    /** Drops the remembered environments of removed runs. */
    private void forgetEnvironments(long compositionId, List<String> runIds) {
        Map<String, Map<String, String>> byRun = environments.get(compositionId);
        if (byRun != null) {
            runIds.forEach(byRun::remove);
        }
    }

    private void rememberEnvironment(long compositionId, String runId, Map<String, String> environment) {
        Map<String, Map<String, String>> byRun = environments.computeIfAbsent(compositionId,
                k -> new ConcurrentHashMap<>());
        byRun.put(runId, new java.util.HashMap<>(environment));
        if (byRun.size() > MAX_RUNS) {
            java.util.Set<String> kept = new java.util.HashSet<>();
            list(compositionId).forEach(r -> kept.add(r.getId()));
            byRun.keySet().retainAll(kept);
        }
    }

    /**
     * Removes one finished run or suggestion and tells the workbench about it over SSE.
     *
     * @param compositionId the composition id
     * @param runId         the run id
     * @throws CompositionNotFoundException when the composition has no such run
     * @throws TestRunRunningException      when the run is still running
     */
    public void remove(long compositionId, String runId) {
        Deque<CompositionTestRun> deque = runs.get(compositionId);
        if (deque == null) {
            throw new CompositionNotFoundException("Test run not found: " + runId);
        }
        synchronized (deque) {
            CompositionTestRun run = deque.stream()
                    .filter(r -> r.getId().equals(runId))
                    .findFirst()
                    .orElseThrow(() -> new CompositionNotFoundException("Test run not found: " + runId));
            if (run.getStatus() == CompositionTestRun.Status.RUNNING) {
                throw new TestRunRunningException(runId);
            }
            deque.remove(run);
        }
        forgetEnvironments(compositionId, List.of(runId));
        sseEvents.fire(SseEvent.compositionTestRunsRemoved(compositionId, List.of(runId)));
    }

    /**
     * Removes every run and suggestion of a composition that isn't running, and tells the workbench about the
     * removed ones over SSE.
     *
     * @param compositionId the composition id
     * @return the ids of the removed runs
     */
    public List<String> clear(long compositionId) {
        Deque<CompositionTestRun> deque = runs.get(compositionId);
        if (deque == null) {
            return List.of();
        }
        List<String> removed = new ArrayList<>();
        synchronized (deque) {
            deque.removeIf(r -> {
                boolean finished = r.getStatus() != CompositionTestRun.Status.RUNNING;
                if (finished) {
                    removed.add(r.getId());
                }
                return finished;
            });
        }
        forgetEnvironments(compositionId, removed);
        if (!removed.isEmpty()) {
            sseEvents.fire(SseEvent.compositionTestRunsRemoved(compositionId, removed));
        }
        return removed;
    }

    /**
     * Drops the runs of a deleted composition.
     *
     * @param compositionId the composition id
     */
    public void forget(long compositionId) {
        runs.remove(compositionId);
        permits.remove(compositionId);
        environments.remove(compositionId);
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

    /** Uses the caller's run id when it is well-formed and not already used by this composition. */
    private String runIdFor(long compositionId, String requested) {
        if (requested != null && RUN_ID.matcher(requested).matches()
                && list(compositionId).stream().noneMatch(r -> requested.equals(r.getId()))) {
            return requested;
        }
        return UUID.randomUUID().toString();
    }

    /** The execution mode of an action type draft, or null for a tool or an unknown mode. */
    private CompositionTestRun.ExecutionMode executionModeOf(CompositionItemEntity item) {
        if (!"action-type".equals(item.itemType)) {
            return null;
        }
        String mode = service.contentOf(item).path("executionMode").asText("");
        return switch (mode) {
            case "agent", "script", "node" -> CompositionTestRun.ExecutionMode.fromValue(mode);
            default -> null;
        };
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
        copy.setExecutionMode(run.getExecutionMode());
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
