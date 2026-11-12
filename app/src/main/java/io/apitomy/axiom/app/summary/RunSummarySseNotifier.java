package io.apitomy.axiom.app.summary;

import io.apitomy.axiom.core.events.RunSummaryChanged;
import io.apitomy.axiom.core.events.SseEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;

/** Sends a workflow-updated SSE event after a run summary change commits. */
@ApplicationScoped
public class RunSummarySseNotifier {

    @Inject
    Event<SseEvent> sseEvents;

    void onChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) RunSummaryChanged changed) {
        sseEvents.fire(SseEvent.workflowUpdated(changed.subjectId(), changed.runId(), changed.runStatus()));
    }
}
