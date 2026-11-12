package io.apitomy.axiom.app.summary;

import io.apitomy.axiom.app.HistoryCleanup;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Queues, requests, and generates AI summaries of workflow runs. */
@ApplicationScoped
public class RunSummaryService {

    /** Run statuses that are summarized automatically. */
    static final Set<String> AUTO_STATUSES = Set.of("completed", "failed");

    @ConfigProperty(name = "axiom.run-summary.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    Event<RunSummaryChanged> changes;

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

    private static WebApplicationException conflict(String message) {
        return new WebApplicationException(Response.status(409).entity(Map.of("message", message)).build());
    }
}
