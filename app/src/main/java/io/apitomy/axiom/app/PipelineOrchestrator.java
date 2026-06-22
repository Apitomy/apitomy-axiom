package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.EventQueueEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.axiom.core.lifecycle.ProjectStatus;
import io.apitomy.axiom.core.services.WorkspaceService;
import io.apitomy.axiom.manager.ManagerDecision;
import io.apitomy.axiom.manager.ManagerService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

/**
 * The central event processing pipeline. Dequeues events, invokes the AI Manager
 * to evaluate them, and dispatches the resulting decisions (create tasks, ignore,
 * system actions, escalations).
 *
 * <p>This is the heart of Axiom — the end-to-end flow:</p>
 * <pre>
 * Event Queue → Manager → Decisions → Project auto-creation → Task creation → Actor execution
 * </pre>
 */
@ApplicationScoped
public class PipelineOrchestrator {

    private static final Logger LOG = Logger.getLogger(PipelineOrchestrator.class);

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

    /**
     * Polls the event queue every 5 seconds and processes one pending event at a time.
     * Events are processed sequentially to avoid race conditions in the Manager.
     */
    @Scheduled(every = "${axiom.pipeline.poll-interval:5s}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void processNextEvent() {
        EventQueueEntity queueEntry = findNextPendingEvent();
        if (queueEntry == null) {
            return;
        }

        processEvent(queueEntry);
    }

    @Transactional
    EventQueueEntity findNextPendingEvent() {
        EventQueueEntity entry = EventQueueEntity.find(
                "status = 'pending' order by enqueuedAt asc").firstResult();
        if (entry != null) {
            entry.status = "processing";
        }
        return entry;
    }

    @Transactional
    void processEvent(EventQueueEntity queueEntry) {
        EventEntity event = EventEntity.findById(queueEntry.eventId);
        if (event == null) {
            LOG.warnf("Event %d not found for queue entry %d", queueEntry.eventId, queueEntry.id);
            markQueueEntry(queueEntry.id, "failed");
            return;
        }

        LOG.infof("Processing event %d: %s [%s] from %s",
                event.id, event.eventType, event.issueRef, event.source);

        try {
            // Invoke the Manager
            List<ManagerDecision> decisions = managerService.evaluate(event);

            if (decisions.isEmpty()) {
                LOG.infof("Manager returned no decisions for event %d", event.id);
                logActivity(null, null, event.id, "manager-no-decision",
                        "Manager returned no decisions for " + event.eventType);
                markQueueEntry(queueEntry.id, "completed");
                return;
            }

            // Process each decision
            for (ManagerDecision decision : decisions) {
                processDecision(event, decision);
            }

            markQueueEntry(queueEntry.id, "completed");

        } catch (Exception e) {
            LOG.errorf(e, "Pipeline failed for event %d", event.id);
            logActivity(null, null, event.id, "pipeline-error",
                    "Pipeline error: " + e.getMessage());
            markQueueEntry(queueEntry.id, "failed");
        }
    }

    private void processDecision(EventEntity event, ManagerDecision decision) {
        if (!managerService.meetsConfidenceThreshold(decision)) {
            LOG.infof("Decision below confidence threshold (%.2f): %s — escalating",
                    decision.confidence(), decision.decision());
            handleEscalation(event, decision,
                    "Low confidence (" + String.format("%.0f%%", decision.confidence() * 100)
                            + "): " + decision.reasoning());
            return;
        }

        if (decision.isCreateTask()) {
            handleCreateTask(event, decision);
        } else if (decision.isIgnore()) {
            handleIgnore(event, decision);
        } else if (decision.isScriptAction()) {
            handleScriptAction(event, decision);
        } else if (decision.isEscalate()) {
            handleEscalation(event, decision, decision.reasoning());
        } else {
            LOG.warnf("Unknown decision type: %s", decision.decision());
        }
    }

    private void handleCreateTask(EventEntity event, ManagerDecision decision) {
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
        task.createdOn = Instant.now();
        task.persist();

        LOG.infof("Created task %d (%s) for project %d from event %d",
                task.id, task.actionType, project.id, event.id);

        // Update event with project reference
        event.projectId = project.id;

        // Log to activity
        logActivity(project.id, task.id, event.id, "task-created",
                "Manager created task: " + task.actionType + " — " + decision.reasoning());

        // Log to thread
        addThreadEntry(project.id, "manager", "decision",
                "Created task: " + task.actionType + "\n\nReasoning: " + decision.reasoning());

        // Fire SSE events
        sseEvents.fire(SseEvent.taskUpdated(project.id, task.id, "Pending"));
        sseEvents.fire(SseEvent.projectUpdated(project.id));
        sseEvents.fire(SseEvent.threadEntry(project.id));
    }

    private void handleIgnore(EventEntity event, ManagerDecision decision) {
        LOG.infof("Ignoring event %d: %s", event.id, decision.reasoning());
        logActivity(null, null, event.id, "event-ignored",
                "Event ignored: " + event.eventType + " — " + decision.reasoning());
    }

    private void handleScriptAction(EventEntity event, ManagerDecision decision) {
        ProjectEntity project = findOrCreateProject(event);

        TaskEntity task = new TaskEntity();
        task.projectId = project.id;
        task.eventId = event.id;
        task.actionType = decision.actionType();
        task.createdBy = "manager";
        task.status = "Pending";
        task.input = decision.inputContext();
        task.createdOn = Instant.now();
        task.persist();

        LOG.infof("Created script task %d (%s) for project %d from event %d",
                task.id, task.actionType, project.id, event.id);

        event.projectId = project.id;

        logActivity(project.id, task.id, event.id, "task-created",
                "Manager created script task: " + task.actionType
                        + " — " + decision.reasoning());
        addThreadEntry(project.id, "manager", "decision",
                "Script action: " + task.actionType
                        + "\n\nReasoning: " + decision.reasoning());

        sseEvents.fire(SseEvent.taskUpdated(project.id, task.id, "Pending"));
        sseEvents.fire(SseEvent.projectUpdated(project.id));
        sseEvents.fire(SseEvent.threadEntry(project.id));

        scriptExecutionService.executeScript(task);
    }

    private void handleEscalation(EventEntity event, ManagerDecision decision, String reason) {
        LOG.infof("Escalating event %d to user: %s", event.id, reason);
        logActivity(null, null, event.id, "manager-escalation",
                "Manager escalation: " + reason);

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
            return ProjectEntity.find("issueRef", event.issueRef).firstResult();
        }
        if (event.projectId != null) {
            return ProjectEntity.findById(event.projectId);
        }
        return null;
    }

    private ProjectEntity createProjectFromEvent(EventEntity event) {
        ProjectEntity project = new ProjectEntity();
        project.name = extractIssueTitle(event);
        project.type = determineProjectType(event.eventType);
        project.status = ProjectStatus.Created.name();
        project.issueSource = event.source;
        project.issueRef = event.issueRef != null ? event.issueRef : "unknown";
        project.repository = event.repository != null ? event.repository : "unknown";
        project.createdOn = Instant.now();
        project.updatedOn = Instant.now();
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
}
