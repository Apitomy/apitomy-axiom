package io.apitomy.axiom.app.summary;

import io.apitomy.axiom.agents.spi.AgentRequest;
import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.app.AgentLease;
import io.apitomy.axiom.app.AgentPool;
import io.apitomy.axiom.app.HistoryCleanup;
import io.apitomy.axiom.agents.spi.AgentRegistry;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.SystemConfigEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunSummaryEntity;
import io.apitomy.axiom.core.events.RunSummaryChanged;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Queues, requests, and generates AI summaries of workflow runs. */
@ApplicationScoped
public class RunSummaryService {

    /** Run statuses that are summarized automatically. */
    static final Set<String> AUTO_STATUSES = Set.of("completed", "failed");

    @ConfigProperty(name = "axiom.run-summary.enabled", defaultValue = "true")
    boolean enabled;

    /** Maximum attempts before a summary is marked failed. */
    static final int MAX_ATTEMPTS = 2;

    /** Action/invocation type recorded for summary generation. */
    static final String INVOCATION_TYPE = "run-summary";

    private static final Logger LOG = Logger.getLogger(RunSummaryService.class);

    @ConfigProperty(name = "axiom.run-summary.max-steps", defaultValue = "10")
    int maxSteps;

    @ConfigProperty(name = "axiom.run-summary.timeout-seconds", defaultValue = "300")
    int timeoutSeconds;

    /** Extra seconds to wait beyond the agent timeout before cancelling; package-visible for tests. */
    long waitGraceSeconds = 30L;

    @Inject
    Event<RunSummaryChanged> changes;

    @Inject
    AgentPool agentPool;

    @Inject
    AgentRegistry agentRegistry;

    @Inject
    RunSummaryMcp runSummaryMcp;

    /** @return whether run summaries are enabled globally */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Queues an automatic summary when the run finished completed/failed and its workflow opted in.
     * Must run inside the transaction that finished the run.
     *
     * @param run the finished run
     */
    public void enqueueIfEnabled(WorkflowRunEntity run) {
        if (!enabled || !AUTO_STATUSES.contains(run.status)) {
            return;
        }
        WorkflowDefinitionEntity definition = WorkflowDefinitionEntity.findById(run.definitionId);
        if (definition == null || !definition.summarizeRuns) {
            return;
        }
        if (WorkflowRunSummaryEntity.findById(run.id) != null) {
            return;
        }
        WorkflowRunSummaryEntity.newPending(run, WorkflowRunSummaryEntity.AUTO).persist();
        markChanged(run);
    }

    /**
     * Notifies the UI (after commit) that the run's summary changed.
     *
     * @param run the run
     */
    public void markChanged(WorkflowRunEntity run) {
        changes.fire(new RunSummaryChanged(run.subjectId, run.id, run.status));
    }

    /**
     * Queues a manual (re)generation of a finished run's summary.
     *
     * @param runId the run
     * @throws WebApplicationException 404 unknown run, 409 active run or generation in progress, 503 disabled
     */
    @Transactional
    public void request(long runId) {
        if (!enabled) {
            throw new WebApplicationException("Run summaries are disabled", 503);
        }
        WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
        if (run == null) {
            throw new WebApplicationException("Workflow run not found: " + runId, Response.Status.NOT_FOUND);
        }
        if (!HistoryCleanup.FINISHED_WORKFLOW_RUN_STATUSES.contains(run.status)) {
            throw conflict("The run has not finished");
        }
        WorkflowRunSummaryEntity summary = WorkflowRunSummaryEntity.findById(runId, LockModeType.PESSIMISTIC_WRITE);
        if (summary == null) {
            summary = WorkflowRunSummaryEntity.newPending(run, WorkflowRunSummaryEntity.MANUAL);
            summary.persist();
        } else if (WorkflowRunSummaryEntity.GENERATING.equals(summary.status)) {
            throw conflict("A summary is already being generated");
        } else {
            summary.status = WorkflowRunSummaryEntity.PENDING;
            summary.triggerType = WorkflowRunSummaryEntity.MANUAL;
            summary.attempts = 0;
            summary.error = null;
            summary.createdOn = Instant.now();
            summary.completedOn = null;
        }
        markChanged(run);
    }

    /**
     * Atomically claims pending summaries (pending to generating), oldest first.
     *
     * @param max maximum rows to claim
     * @return claimed run ids
     */
    public List<Long> claimPending(int max) {
        List<Long> candidates = QuarkusTransaction.requiringNew().call(() -> WorkflowRunSummaryEntity
                .getEntityManager()
                .createQuery("select s.runId from WorkflowRunSummaryEntity s where s.status = ?1 order by s.createdOn",
                        Long.class)
                .setParameter(1, WorkflowRunSummaryEntity.PENDING)
                .setMaxResults(max)
                .getResultList());
        List<Long> claimed = new ArrayList<>();
        for (Long runId : candidates) {
            int updated = QuarkusTransaction.requiringNew().call(() -> WorkflowRunSummaryEntity.update(
                    "status = ?1 where runId = ?2 and status = ?3",
                    WorkflowRunSummaryEntity.GENERATING, runId, WorkflowRunSummaryEntity.PENDING));
            if (updated == 1) {
                claimed.add(runId);
            }
        }
        return claimed;
    }

    /**
     * Resets summaries left generating by a previous process back to pending.
     *
     * @return rows reset
     */
    public int recoverInterrupted() {
        return QuarkusTransaction.requiringNew().call(() -> WorkflowRunSummaryEntity.update(
                "status = ?1 where status = ?2",
                WorkflowRunSummaryEntity.PENDING, WorkflowRunSummaryEntity.GENERATING));
    }

    /**
     * Generates the summary of a claimed run (blocking; call off the request and scheduler threads).
     * Never changes the run itself.
     *
     * @param runId a run whose summary row is {@code generating}
     */
    public void generate(long runId) {
        Prepared prepared;
        try {
            prepared = QuarkusTransaction.requiringNew().call(() -> prepare(runId));
        } catch (RuntimeException e) {
            // Reading the run is deterministic: count it as an attempt so a broken run ends up failed.
            LOG.warnf(e, "Failed to prepare the summary of run %d", runId);
            QuarkusTransaction.requiringNew().run(() -> storeFailure(runId, describe(e)));
            return;
        }
        if (prepared == null) {
            return;
        }
        AgentLease lease;
        try {
            lease = agentPool.tryLease(null, prepared.engine(), null, INVOCATION_TYPE, runId).orElse(null);
        } catch (RuntimeException e) {
            // Leasing problems are transient (pool/database), not the run's fault: retry without an attempt.
            LOG.warnf(e, "Failed to lease an agent for the summary of run %d", runId);
            lease = null;
        }
        if (lease == null) {
            resetToPending(runId);
            return;
        }
        String agentType = lease.agent().getType();
        boolean openCode = "opencode".equals(agentType);
        String executionId = INVOCATION_TYPE + "-" + runId;
        long startedAt = System.currentTimeMillis();
        boolean interrupted = false;
        CompletableFuture<AgentResult> future = null;
        try {
            AgentRequest.Builder builder = AgentRequest.builder()
                    .executionId(executionId)
                    .prompt(prepared.prompt())
                    .model(prepared.model())
                    .allowedTools(RunSummaryMcp.ALLOWED_TOOLS)
                    .maxSteps(maxSteps)
                    .timeoutSeconds(timeoutSeconds)
                    .environment(Map.of())
                    .actionType(INVOCATION_TYPE);
            if (openCode) {
                builder.disallowedTools(RunSummaryMcp.OPENCODE_DENIED_TOOLS);
                // Registration reads tool and MCP server definitions, which needs a transaction.
                QuarkusTransaction.requiringNew().run(() -> agentRegistry.getMcpManager(agentType)
                        .configureMcpServers(runId, Map.of(), RunSummaryMcp.ALLOWED_TOOLS));
            } else {
                builder.mcpConfigFile(runSummaryMcp.writeConfigFile(runId));
            }
            future = lease.agent().execute(builder.build());
            AgentResult result = future.get(timeoutSeconds + waitGraceSeconds, TimeUnit.SECONDS);
            if (!result.success()) {
                throw new IllegalStateException(result.errorMessage() != null
                        ? result.errorMessage() : "The agent failed");
            }
            RunSummaryResponseParser.ParsedSummary parsed = RunSummaryResponseParser.parse(result.output());
            long durationMs = System.currentTimeMillis() - startedAt;
            AgentLease leased = lease;
            QuarkusTransaction.requiringNew().run(() -> storeReady(runId, parsed, result, prepared, leased, durationMs));
        } catch (InterruptedException e) {
            // Shutdown: stop the agent and re-queue without counting an attempt. The interrupt flag is restored
            // after the database work below, which would otherwise fail on an interrupted thread.
            interrupted = true;
            cancel(lease, executionId, future, runId);
            LOG.infof("Run summary generation for run %d interrupted; re-queued", runId);
            resetToPending(runId);
        } catch (TimeoutException e) {
            cancel(lease, executionId, future, runId);
            String message = "The agent did not finish within " + timeoutSeconds + " seconds";
            LOG.warnf("Run summary generation failed for run %d: %s", runId, message);
            QuarkusTransaction.requiringNew().run(() -> storeFailure(runId, message));
        } catch (Exception e) {
            String message = describe(e);
            LOG.warnf("Run summary generation failed for run %d: %s", runId, message);
            QuarkusTransaction.requiringNew().run(() -> storeFailure(runId, message));
        } finally {
            agentPool.release(lease);
            if (openCode) {
                try {
                    agentRegistry.getMcpManager(agentType).cleanup(runId);
                } catch (RuntimeException e) {
                    LOG.debugf("Failed to clean up OpenCode MCP state for run %d: %s", runId, e.getMessage());
                }
            }
            runSummaryMcp.cleanup(runId);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void cancel(AgentLease lease, String executionId, CompletableFuture<AgentResult> future, long runId) {
        try {
            lease.agent().cancel(executionId);
        } catch (RuntimeException e) {
            LOG.debugf("Failed to cancel the summary agent for run %d: %s", runId, e.getMessage());
        }
        if (future != null) {
            future.cancel(true);
        }
    }

    private void resetToPending(long runId) {
        QuarkusTransaction.requiringNew().run(() -> WorkflowRunSummaryEntity.update(
                "status = ?1 where runId = ?2 and status = ?3",
                WorkflowRunSummaryEntity.PENDING, runId, WorkflowRunSummaryEntity.GENERATING));
    }

    private static String describe(Exception e) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    /**
     * Everything needed to call the agent, read in one transaction.
     *
     * @param prompt the prompt
     * @param engine the engine to lease
     * @param model the model, or null for the engine default
     */
    record Prepared(String prompt, String engine, String model) { }

    private Prepared prepare(long runId) {
        WorkflowRunSummaryEntity summary = WorkflowRunSummaryEntity.findById(runId);
        WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
        if (summary == null || run == null || !WorkflowRunSummaryEntity.GENERATING.equals(summary.status)) {
            return null;
        }
        WorkflowDefinitionEntity definition = WorkflowDefinitionEntity.findById(run.definitionId);
        List<RunSummaryPromptBuilder.StepDigest> steps = TaskEntity.<TaskEntity>list("workflowRunId = ?1 order by id", runId)
                .stream()
                .map(t -> new RunSummaryPromptBuilder.StepDigest(t.id, t.nodeId, t.status, t.output))
                .toList();
        Double cost = AiUsageEntity.<AiUsageEntity>list("workflowRunId", runId).stream()
                .map(u -> u.costUsd)
                .filter(Objects::nonNull)
                .reduce(Double::sum)
                .orElse(null);
        Long durationMs = run.completedOn != null && run.startedOn != null
                ? run.completedOn.toEpochMilli() - run.startedOn.toEpochMilli() : null;
        String prompt = RunSummaryPromptBuilder.build(new RunSummaryPromptBuilder.RunDigest(run.id,
                definition != null ? definition.name : "Unknown", run.title, run.status, durationMs,
                run.failureReason, cost, steps));
        SystemConfigEntity config = SystemConfigEntity.<SystemConfigEntity>findAll().firstResult();
        String engine = config != null && config.summaryEngine != null ? config.summaryEngine
                : config != null && config.defaultEngine != null ? config.defaultEngine
                : agentRegistry.getDefaultAgentType();
        String model = config != null ? config.summaryModel : null;
        return new Prepared(prompt, engine, model);
    }

    private void storeReady(long runId, RunSummaryResponseParser.ParsedSummary parsed, AgentResult result,
                            Prepared prepared, AgentLease lease, long durationMs) {
        WorkflowRunSummaryEntity summary = WorkflowRunSummaryEntity.findById(runId);
        WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
        if (summary == null || run == null) {
            return; // run deleted meanwhile
        }
        summary.status = WorkflowRunSummaryEntity.READY;
        summary.headline = parsed.headline();
        summary.body = parsed.body();
        summary.error = null;
        summary.engine = result.engine() != null ? result.engine() : lease.agent().getType();
        summary.model = result.model() != null ? result.model() : prepared.model();
        summary.completedOn = Instant.now();
        AiUsageEntity usage = new AiUsageEntity();
        usage.invocationType = INVOCATION_TYPE;
        usage.subjectId = run.subjectId;
        usage.workflowRunId = run.id;
        usage.workflowDefinitionId = run.definitionId;
        usage.traceId = run.traceId;
        usage.agentId = lease.agentEntityId();
        usage.engine = summary.engine;
        usage.model = summary.model;
        usage.costUsd = result.costUsd();
        usage.inputTokens = result.inputTokens();
        usage.outputTokens = result.outputTokens();
        usage.durationMs = durationMs;
        usage.createdOn = Instant.now();
        usage.persist();
        markChanged(run);
    }

    private void storeFailure(long runId, String message) {
        WorkflowRunSummaryEntity summary = WorkflowRunSummaryEntity.findById(runId);
        WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
        if (summary == null || run == null) {
            return;
        }
        summary.attempts++;
        summary.error = message;
        if (summary.attempts < MAX_ATTEMPTS) {
            summary.status = WorkflowRunSummaryEntity.PENDING;
        } else {
            summary.status = WorkflowRunSummaryEntity.FAILED;
            summary.completedOn = Instant.now();
        }
        markChanged(run);
    }

    private static WebApplicationException conflict(String message) {
        return new WebApplicationException(Response.status(409).entity(Map.of("message", message)).build());
    }
}
