package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.filters.EventFilterEvaluator;
import io.apitomy.axiom.core.filters.EventSourceFilters;
import io.apitomy.axiom.core.filters.FilterResult;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.core.tracing.TraceService;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.EventQueueEntity;
import io.apitomy.axiom.core.entities.EventSourceEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.axiom.core.lifecycle.ProjectStatus;
import io.apitomy.axiom.core.services.WorkspaceService;
import io.apitomy.axiom.manager.ManagerDecision;
import io.apitomy.axiom.manager.ManagerService;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * The central event processing pipeline. Dequeues events, invokes the AI Manager
 * to evaluate them, and dispatches the resulting decisions (create tasks, ignore,
 * system actions, escalations).
 *
 * <p>This is the heart of Axiom — the end-to-end flow:</p>
 * <pre>
 * Event Queue → Manager → Decisions → Project auto-creation → Task creation → Agent execution
 * </pre>
 */
@ApplicationScoped
public class PipelineOrchestrator {

    private static final Logger LOG = Logger.getLogger(PipelineOrchestrator.class);

    @Inject
    EventFilterEvaluator filterEvaluator;

    @Inject
    ManagerService managerService;

    @Inject
    WorkspaceService workspaceService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Event<SseEvent> sseEvents;

    @Inject
    ScriptExecutionService scriptExecutionService;

    @Inject
    TraceService traceService;

    private volatile boolean shuttingDown = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    /**
     * Polls the event queue every 5 seconds and processes one pending event at a time.
     * Events are processed sequentially to avoid race conditions in the Manager.
     *
     * <p>The pipeline is split into three transactional phases so that the AI engine
     * call in {@code ManagerService.evaluate()} does not hold a database connection:</p>
     * <ol>
     *   <li><b>Dequeue</b> — short transaction to mark the next pending event as "processing"</li>
     *   <li><b>AI call</b> — no transaction held while the Manager evaluates the event</li>
     *   <li><b>Persist results</b> — short transaction to process decisions and mark completion</li>
     * </ol>
     */
    @Scheduled(every = "${axiom.pipeline.poll-interval:5s}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void processNextEvent() {
        if (shuttingDown) {
            return;
        }
        EventQueueEntity queueEntry = findNextPendingEvent();
        if (queueEntry == null) {
            return;
        }

        processEvent(queueEntry);
    }

    /**
     * Finds the next pending event in the queue and marks it as "processing" in a
     * short independent transaction. Returns {@code null} if the queue is empty.
     */
    EventQueueEntity findNextPendingEvent() {
        return QuarkusTransaction.requiringNew().call(() -> {
            EventQueueEntity entry = EventQueueEntity.find(
                    "status = 'pending' order by enqueuedAt asc").firstResult();
            if (entry != null) {
                entry.status = "processing";
            }
            return entry;
        });
    }

    /**
     * Evaluates event against the filters configured on its source.
     *
     * @param event the event to evaluate
     * @return the filter evaluation result
     */
    private FilterResult evaluateFilters(EventEntity event) {
        if (event.eventSourceId == null) {
            return FilterResult.ALLOWED;
        }
        EventSourceEntity source = EventSourceEntity.findById(event.eventSourceId);
        if (source == null || source.filters == null) {
            return FilterResult.ALLOWED;
        }
        try {
            EventSourceFilters filters = objectMapper.readValue(source.filters, EventSourceFilters.class);
            JsonNode payload = objectMapper.readTree(event.payload);
            return filterEvaluator.evaluate(filters, event.eventType, payload);
        } catch (Exception e) {
            LOG.warnf("Failed to evaluate filters for event %d: %s", event.id, e.getMessage());
            return FilterResult.ALLOWED;
        }
    }

    /**
     * Processes a single queued event through the full pipeline: load the event,
     * invoke the AI Manager, and persist the resulting decisions.
     *
     * <p>The method is intentionally <b>not</b> {@code @Transactional} — each phase
     * uses a short programmatic transaction so the AI engine call runs without
     * holding a database connection.</p>
     *
     * @param queueEntry the dequeued event (may be detached)
     */
    void processEvent(EventQueueEntity queueEntry) {
        Long eventId = queueEntry.eventId;
        Long queueEntryId = queueEntry.id;

        // Phase 1: Load event (short transaction)
        EventEntity event = QuarkusTransaction.requiringNew().call(() ->
                EventEntity.findById(eventId));
        if (event == null) {
            LOG.warnf("Event %d not found for queue entry %d", eventId, queueEntryId);
            QuarkusTransaction.requiringNew().run(() ->
                    markQueueEntry(queueEntryId, "failed"));
            return;
        }

        LOG.infof("Processing event %d: %s [%s] from %s",
                event.id, event.eventType, event.issueRef, event.source);

        // Pre-filter: evaluate event against source filters before invoking the AI Manager
        FilterResult filterResult = evaluateFilters(event);
        if (!filterResult.allowed()) {
            LOG.infof("Pre-filtered event %d: %s", event.id, filterResult.matchedRule());
            QuarkusTransaction.requiringNew().run(() -> {
                EventEntity managed = EventEntity.findById(eventId);
                if (managed != null) {
                    managed.filterStatus = "blocked";
                    managed.filterMatchedRule = filterResult.matchedRule();
                }
                logActivity(null, null, event.id, "event-pre-filtered",
                        "Event pre-filtered: " + event.eventType + " — " + filterResult.matchedRule());
                markQueueEntry(queueEntryId, "completed");
            });
            return;
        }

        // Mark event as allowed through filters
        QuarkusTransaction.requiringNew().run(() -> {
            EventEntity managed = EventEntity.findById(eventId);
            if (managed != null) {
                managed.filterStatus = "allowed";
            }
        });

        // Trace creation (TraceService manages its own transactions)
        TraceContext traceCtx = null;
        try {
            traceCtx = traceService.createTrace("event-pipeline",
                    "Processing event #" + event.id + ": " + event.eventType,
                    event.id, null, null,
                    "event-ingested", "Event received: " + event.eventType,
                    "event", event.id);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to create trace for event %d", event.id);
        }

        // Phase 2: AI call (no transaction held — database connection released)
        try {
            List<ManagerDecision> decisions = managerService.evaluate(event, traceCtx);

            // Phase 3: Persist results (short transaction)
            persistResults(queueEntryId, event, decisions, traceCtx);

        } catch (Exception e) {
            LOG.errorf(e, "Pipeline failed for event %d", event.id);
            persistFailure(queueEntryId, event.id, e.getMessage(), traceCtx);
        }
    }

    /**
     * Persists the Manager's evaluation results in a single short transaction.
     * Re-loads the event as a managed entity so that field updates (traceId,
     * projectId) are tracked by JPA dirty checking.
     */
    private void persistResults(Long queueEntryId, EventEntity detachedEvent,
            List<ManagerDecision> decisions, TraceContext traceCtx) {
        QuarkusTransaction.requiringNew().run(() -> {
            EventEntity event = EventEntity.findById(detachedEvent.id);
            if (traceCtx != null) {
                event.traceId = traceCtx.traceId();
            }

            if (decisions.isEmpty()) {
                LOG.infof("Manager returned no decisions for event %d", event.id);
                logActivity(null, null, event.id, "manager-no-decision",
                        "Manager returned no decisions for " + event.eventType);
                popEvalNode(traceCtx);
                completeTraceIfSync(traceCtx, false);
                markQueueEntry(queueEntryId, "completed");
                return;
            }

            boolean hasAsyncTask = false;
            for (ManagerDecision decision : decisions) {
                boolean isAsync = processDecision(event, decision, traceCtx);
                if (isAsync) {
                    hasAsyncTask = true;
                }
            }
            popEvalNode(traceCtx);

            completeTraceIfSync(traceCtx, hasAsyncTask);
            markQueueEntry(queueEntryId, "completed");
        });
    }

    /**
     * Persists pipeline failure state in a single short transaction.
     */
    private void persistFailure(Long queueEntryId, Long eventId,
            String errorMessage, TraceContext traceCtx) {
        QuarkusTransaction.requiringNew().run(() -> {
            if (traceCtx != null) {
                EventEntity event = EventEntity.findById(eventId);
                if (event != null) {
                    event.traceId = traceCtx.traceId();
                }
            }
            logActivity(null, null, eventId, "pipeline-error",
                    "Pipeline error: " + errorMessage);
            completeTraceFailed(traceCtx);
            markQueueEntry(queueEntryId, "failed");
        });
    }

    /**
     * Processes a single manager decision, returning {@code true} if the decision
     * involves an async task (create_task or script_action).
     */
    private boolean processDecision(EventEntity event, ManagerDecision decision,
            TraceContext traceCtx) {
        String decisionLabel = switch (decision.decision()) {
            case "create_task" -> "Create Task: " + decision.actionType();
            case "script_action" -> "Script Action: " + decision.actionType();
            case "ignore" -> "Ignore";
            case "escalate" -> "Escalate";
            default -> decision.decision() + ": " + decision.actionType();
        };

        Long decisionNodeId = null;
        if (traceCtx != null) {
            try {
                decisionNodeId = traceService.addNode(traceCtx, "decision-processed", "in-progress",
                        decisionLabel, null, null);
                traceCtx.push(decisionNodeId);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to add decision trace node");
            }
        }

        boolean isAsync = false;
        Long decisionLogId = null;
        try {
            if (!managerService.meetsConfidenceThreshold(decision)) {
                LOG.infof("Decision below confidence threshold (%.2f): %s — escalating",
                        decision.confidence(), decision.decision());
                handleEscalation(event, decision,
                        "Low confidence (" + String.format("%.0f%%", decision.confidence() * 100)
                                + "): " + decision.reasoning(), traceCtx);
            } else if (decision.isCreateTask()) {
                decisionLogId = handleCreateTask(event, decision, traceCtx);
                isAsync = true;
            } else if (decision.isIgnore()) {
                handleIgnore(event, decision, traceCtx);
            } else if (decision.isScriptAction()) {
                decisionLogId = handleScriptAction(event, decision, traceCtx);
                isAsync = true;
            } else if (decision.isEscalate()) {
                handleEscalation(event, decision, decision.reasoning(), traceCtx);
            } else {
                LOG.warnf("Unknown decision type: %s", decision.decision());
            }
        } finally {
            if (traceCtx != null && decisionNodeId != null) {
                try {
                    if (decisionLogId != null) {
                        traceService.completeNode(decisionNodeId, "completed",
                                "activity-log", decisionLogId);
                    } else {
                        traceService.completeNode(decisionNodeId, "completed");
                    }
                    traceCtx.pop();
                } catch (Exception e) {
                    LOG.warnf(e, "Failed to complete decision trace node");
                }
            }
        }

        return isAsync;
    }

    private Long handleCreateTask(EventEntity event, ManagerDecision decision,
            TraceContext traceCtx) {
        // Find or create the project
        ProjectEntity project = findOrCreateProject(event);

        // Create the task
        TaskEntity task = new TaskEntity();
        task.projectId = project.id;
        task.eventId = event.id;
        task.actionType = decision.actionType();
        task.createdBy = "manager";
        task.status = "Pending";
        task.input = decision.inputContext();
        task.humanContext = decision.humanContext();
        task.outputSchema = decision.outputSchema();
        task.createdOn = Instant.now();
        if (traceCtx != null) {
            task.traceId = traceCtx.traceId();
        }
        task.persist();

        LOG.infof("Created task %d (%s) for project %d from event %d",
                task.id, task.actionType, project.id, event.id);

        // Update event with project reference
        event.projectId = project.id;

        // Log to activity
        logActivity(project.id, task.id, event.id, "task-created",
                "Manager created task: " + task.actionType + " — " + decision.reasoning());

        // Log the decision reasoning for trace node detail access
        ActivityLogEntity decisionLog = new ActivityLogEntity();
        decisionLog.projectId = project.id;
        decisionLog.taskId = task.id;
        decisionLog.eventId = event.id;
        decisionLog.entryType = "manager-decision";
        decisionLog.summary = decision.reasoning();
        decisionLog.details = decision.inputContext();
        decisionLog.createdOn = Instant.now();
        decisionLog.persist();

        // Add trace node for the task
        if (traceCtx != null) {
            try {
                traceService.addNode(traceCtx, "task", "in-progress",
                        "Task: " + task.actionType, "task", task.id);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to add task trace node");
            }
        }

        // Log to thread
        addThreadEntry(project.id, "manager", "decision",
                "Created task: " + task.actionType + "\n\nReasoning: " + decision.reasoning());

        // Fire SSE events
        sseEvents.fire(SseEvent.taskUpdated(project.id, task.id, "Pending"));
        sseEvents.fire(SseEvent.projectUpdated(project.id));
        sseEvents.fire(SseEvent.threadEntry(project.id));

        return decisionLog.id;
    }

    private void handleIgnore(EventEntity event, ManagerDecision decision,
            TraceContext traceCtx) {
        LOG.infof("Ignoring event %d: %s", event.id, decision.reasoning());
        logActivity(null, null, event.id, "event-ignored",
                "Event ignored: " + event.eventType + " — " + decision.reasoning());

        if (traceCtx != null) {
            try {
                ActivityLogEntity reasonLog = new ActivityLogEntity();
                reasonLog.eventId = event.id;
                reasonLog.entryType = "manager-decision";
                reasonLog.summary = decision.reasoning();
                reasonLog.createdOn = Instant.now();
                reasonLog.persist();

                traceService.addNode(traceCtx, "event-ignored", "completed",
                        "Ignored", "activity-log", reasonLog.id);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to add event-ignored trace node");
            }
        }
    }

    private Long handleScriptAction(EventEntity event, ManagerDecision decision,
            TraceContext traceCtx) {
        ProjectEntity project = findOrCreateProject(event);

        TaskEntity task = new TaskEntity();
        task.projectId = project.id;
        task.eventId = event.id;
        task.actionType = decision.actionType();
        task.createdBy = "manager";
        task.status = "Pending";
        task.input = decision.inputContext();
        task.humanContext = decision.humanContext();
        task.outputSchema = decision.outputSchema();
        task.createdOn = Instant.now();
        if (traceCtx != null) {
            task.traceId = traceCtx.traceId();
        }
        task.persist();

        LOG.infof("Created script task %d (%s) for project %d from event %d",
                task.id, task.actionType, project.id, event.id);

        event.projectId = project.id;

        logActivity(project.id, task.id, event.id, "task-created",
                "Manager created script task: " + task.actionType
                        + " — " + decision.reasoning());

        ActivityLogEntity decisionLog = new ActivityLogEntity();
        decisionLog.projectId = project.id;
        decisionLog.taskId = task.id;
        decisionLog.eventId = event.id;
        decisionLog.entryType = "manager-decision";
        decisionLog.summary = decision.reasoning();
        decisionLog.details = decision.inputContext();
        decisionLog.createdOn = Instant.now();
        decisionLog.persist();

        // Add trace node for the task
        if (traceCtx != null) {
            try {
                traceService.addNode(traceCtx, "task", "in-progress",
                        "Task: " + task.actionType, "task", task.id);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to add task trace node");
            }
        }

        addThreadEntry(project.id, "manager", "decision",
                "Script action: " + task.actionType
                        + "\n\nReasoning: " + decision.reasoning());

        sseEvents.fire(SseEvent.taskUpdated(project.id, task.id, "Pending"));
        sseEvents.fire(SseEvent.projectUpdated(project.id));
        sseEvents.fire(SseEvent.threadEntry(project.id));

        scriptExecutionService.executeScript(task, project);

        return decisionLog.id;
    }

    private void handleEscalation(EventEntity event, ManagerDecision decision,
            String reason, TraceContext traceCtx) {
        LOG.infof("Escalating event %d to user: %s", event.id, reason);
        logActivity(null, null, event.id, "manager-escalation",
                "Manager escalation: " + reason);

        if (traceCtx != null) {
            try {
                ActivityLogEntity reasonLog = new ActivityLogEntity();
                reasonLog.eventId = event.id;
                reasonLog.entryType = "manager-escalation";
                reasonLog.summary = reason;
                reasonLog.createdOn = Instant.now();
                reasonLog.persist();

                traceService.addNode(traceCtx, "escalation", "completed",
                        "Escalated", "activity-log", reasonLog.id);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to add escalation trace node");
            }
        }

        // If there's a project, add to its thread
        ProjectEntity project = findProjectForEvent(event);
        if (project != null) {
            addThreadEntry(project.id, "manager", "question",
                    "Escalation: " + reason
                            + "\n\nThe Manager needs your input on how to handle this event.");
            sseEvents.fire(SseEvent.threadEntry(project.id));
        }

        sseEvents.fire(SseEvent.notification("Manager escalation: " + reason, "warning"));
    }

    /**
     * Finds an existing project for the event's issue, or creates a new one.
     */
    private ProjectEntity findOrCreateProject(EventEntity event) {
        ProjectEntity project = findProjectForEvent(event);
        if (project != null) {
            return project;
        }

        // Auto-create a new project
        return createProjectFromEvent(event);
    }

    private ProjectEntity findProjectForEvent(EventEntity event) {
        if (event.issueRef != null) {
            return ProjectEntity.find("ref", event.issueRef).firstResult();
        }
        if (event.projectId != null) {
            return ProjectEntity.findById(event.projectId);
        }
        return null;
    }

    private ProjectEntity createProjectFromEvent(EventEntity event) {
        ProjectEntity project = new ProjectEntity();
        project.name = extractIssueTitle(event);
        project.body = extractIssueBody(event);
        project.type = determineProjectType(event.eventType);
        project.status = ProjectStatus.Created.name();
        project.refSource = event.source;
        project.ref = event.issueRef != null ? event.issueRef : "unknown";
        project.repository = event.repository != null ? event.repository : null;
        project.createdOn = Instant.now();
        project.updatedOn = Instant.now();
        if (event.eventSourceId != null) {
            EventSourceEntity eventSource = EventSourceEntity.findById(event.eventSourceId);
            if (eventSource != null && eventSource.labels != null && !eventSource.labels.isEmpty()) {
                project.labels.addAll(eventSource.labels);
            }
        }
        project.persist();

        LOG.infof("Auto-created project %d for issue %s", project.id, event.issueRef);

        logActivity(project.id, null, event.id, "project-created",
                "Project auto-created from " + event.eventType + " event");
        addThreadEntry(project.id, "system", "message",
                "Project created from " + event.source + " event: " + event.eventType);

        // Clone the repository workspace
        try {
            workspaceService.ensureWorkspace(project);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to create workspace for project %d", project.id);
            addThreadEntry(project.id, "system", "message",
                    "Warning: workspace creation failed — " + e.getMessage());
        }

        return project;
    }

    /**
     * Determines the project type based on the event type prefix.
     */
    private String determineProjectType(String eventType) {
        if (eventType != null) {
            if (eventType.startsWith("issue-")) {
                return "issue";
            }
            if (eventType.startsWith("pr-")) {
                return "pull-request";
            }
        }
        return "other";
    }

    private void markQueueEntry(Long queueEntryId, String status) {
        EventQueueEntity entry = EventQueueEntity.findById(queueEntryId);
        if (entry != null) {
            entry.status = status;
            entry.processedAt = Instant.now();
        }
    }

    /**
     * Extracts the issue title from the event payload. Falls back to the
     * issue reference or a generic name if the title cannot be found.
     */
    private String extractIssueTitle(EventEntity event) {
        if (event.payload != null) {
            try {
                JsonNode root = objectMapper.readTree(event.payload);
                String title = root.path("issue").path("title").asText(null);
                if (title != null && !title.isBlank()) {
                    return title;
                }
                title = root.path("pull_request").path("title").asText(null);
                if (title != null && !title.isBlank()) {
                    return title;
                }
            } catch (Exception e) {
                LOG.debugf("Could not parse event payload for issue title: %s", e.getMessage());
            }
        }
        return event.issueRef != null ? event.issueRef : "Project from event " + event.id;
    }

    /**
     * Extracts the issue/PR body from the event payload. Returns {@code null}
     * if the body cannot be found.
     */
    private String extractIssueBody(EventEntity event) {
        if (event.payload != null) {
            try {
                JsonNode root = objectMapper.readTree(event.payload);
                String body = root.path("issue").path("body").asText(null);
                if (body != null && !body.isBlank()) {
                    return body;
                }
                body = root.path("pull_request").path("body").asText(null);
                if (body != null && !body.isBlank()) {
                    return body;
                }
            } catch (Exception e) {
                LOG.debugf("Could not parse event payload for issue body: %s", e.getMessage());
            }
        }
        return null;
    }

    private void logActivity(Long projectId, Long taskId, Long eventId,
                              String entryType, String summary) {
        ActivityLogEntity log = new ActivityLogEntity();
        log.projectId = projectId;
        log.taskId = taskId;
        log.eventId = eventId;
        log.entryType = entryType;
        log.summary = summary != null && summary.length() > 1024
                ? summary.substring(0, 1021) + "..."
                : summary;
        log.createdOn = Instant.now();
        log.persist();
    }

    private void addThreadEntry(Long projectId, String authorType, String entryType,
                                 String content) {
        ThreadEntryEntity entry = new ThreadEntryEntity();
        entry.projectId = projectId;
        entry.authorType = authorType;
        entry.entryType = entryType;
        entry.content = content;
        entry.createdOn = Instant.now();
        entry.persist();
    }

    /**
     * Pops the manager evaluation node from the trace context stack.
     */
    private void popEvalNode(TraceContext traceCtx) {
        if (traceCtx == null) {
            return;
        }
        try {
            traceCtx.pop();
        } catch (Exception e) {
            LOG.warnf(e, "Failed to pop eval node from trace context");
        }
    }

    /**
     * Completes the trace synchronously if no async tasks were created.
     */
    private void completeTraceIfSync(TraceContext traceCtx, boolean hasAsyncTask) {
        if (traceCtx == null || hasAsyncTask) {
            return;
        }
        try {
            traceService.completeNode(traceCtx.currentParentNodeId(), "completed");
            traceService.completeTrace(traceCtx.traceId(), "completed");
        } catch (Exception e) {
            LOG.warnf(e, "Failed to complete trace %s", traceCtx.traceId());
        }
    }

    /**
     * Completes the trace with a failed status on pipeline error.
     */
    private void completeTraceFailed(TraceContext traceCtx) {
        if (traceCtx == null) {
            return;
        }
        try {
            traceService.completeNode(traceCtx.currentParentNodeId(), "failed");
            traceService.completeTrace(traceCtx.traceId(), "failed");
        } catch (Exception e) {
            LOG.warnf(e, "Failed to complete trace %s on error", traceCtx.traceId());
        }
    }
}
