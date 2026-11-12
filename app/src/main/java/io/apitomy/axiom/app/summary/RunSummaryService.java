package io.apitomy.axiom.app.summary;

import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunSummaryEntity;
import io.apitomy.axiom.core.events.RunSummaryChanged;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
}
