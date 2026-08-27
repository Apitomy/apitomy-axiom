package io.apitomy.axiom.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.axiom.core.tracing.TraceService;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.flow.engine.WorkflowEngine;
import io.apitomy.flow.engine.WorkflowValidationException;
import io.apitomy.flow.model.InstanceStatus;
import io.apitomy.flow.model.NodeType;
import io.apitomy.flow.model.Workflow;
import io.apitomy.flow.model.WorkflowInstance;
import io.apitomy.flow.model.WorkflowNode;
import io.apitomy.flow.model.ActionInfo;
import io.apitomy.flow.spi.NodeExecutionContext;
import io.apitomy.flow.spi.NodeExecutor;
import io.apitomy.flow.spi.NodeExecutorProvider;
import io.apitomy.flow.spi.NodeResult;
import io.apitomy.flow.spi.NodeResultStatus;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class WorkflowExecutionService {

    private static final Logger LOG = Logger.getLogger(WorkflowExecutionService.class);
    private static final Set<NodeType> SUPPORTED_NODE_TYPES =
            Set.of(NodeType.START, NodeType.END, NodeType.ACTION);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Event<SseEvent> sseEvents;

    @Inject
    TraceService traceService;

    private WorkflowEngine workflowEngine;

    @PostConstruct
    void init() {
        NodeExecutorProvider provider = actionType -> new NodeExecutor() {
            @Override
            public String actionType() {
                return actionType;
            }

            @Override
            public NodeResult execute(NodeExecutionContext context) {
                return new NodeResult(NodeResultStatus.PENDING, Map.of());
            }
        };
        this.workflowEngine = new WorkflowEngine(provider, List.of(), null);
    }

    /**
     * Triggers a workflow on a project.
     */
    @Transactional
    public WorkflowRunEntity triggerWorkflow(long projectId, long definitionId) {
        ProjectEntity project = ProjectEntity.findById(projectId);
        if (project == null) {
            throw new WebApplicationException("Project not found", 404);
        }

        WorkflowRunEntity activeRun = WorkflowRunEntity
                .find("projectId = ?1 and status in ?2",
                        projectId, List.of("running", "waiting"))
                .firstResult();
        if (activeRun != null) {
            throw new WebApplicationException(
                    "Project already has an active workflow run", 409);
        }

        WorkflowDefinitionEntity definition =
                WorkflowDefinitionEntity.findById(definitionId);
        if (definition == null) {
            throw new WebApplicationException(
                    "Workflow definition not found", 404);
        }
        if (definition.currentVersion == null) {
            throw new WebApplicationException(
                    "Workflow definition has no published version", 400);
        }

        WorkflowDefinitionVersionEntity version =
                WorkflowDefinitionVersionEntity
                        .find("definitionId = ?1 and version = ?2",
                                definitionId, definition.currentVersion)
                        .firstResult();
        if (version == null) {
            throw new WebApplicationException(
                    "Published version not found", 400);
        }

        Workflow workflow = deserializeWorkflow(version.content);

        validateNodeTypes(workflow);

        Map<String, Object> context = new HashMap<>();
        context.put("projectId", project.id);
        context.put("projectName", project.name);
        if (project.repository != null) {
            context.put("repository", project.repository);
        }
        if (project.ref != null) {
            context.put("ref", project.ref);
        }

        WorkflowInstance instance;
        try {
            instance = workflowEngine.startWorkflow(workflow, context);
        } catch (IllegalArgumentException | WorkflowValidationException e) {
            String message = e.getMessage() != null ? e.getMessage() : "Workflow could not be started.";
            throw new WebApplicationException(
                    Response.status(400)
                            .entity(Map.of("message", message))
                            .build());
        }

        WorkflowRunEntity entity = new WorkflowRunEntity();
        entity.projectId = projectId;
        entity.definitionId = definitionId;
        entity.definitionVersion = definition.currentVersion;
        entity.startedOn = Instant.now();
        persistInstanceState(entity, instance);
        entity.persist();

        try {
            TraceContext traceCtx = traceService.createTrace(
                    "workflow",
                    "Workflow: " + definition.name,
                    null, project.id, null,
                    "workflow", "Workflow: " + definition.name,
                    "workflow-run", entity.id);
            entity.traceId = traceCtx.traceId();
        } catch (Exception e) {
            LOG.warnf(e, "Failed to create trace for workflow run %d", entity.id);
        }

        if (instance.status() == InstanceStatus.WAITING) {
            createTaskForCurrentNode(entity, workflow, instance);
        }

        logActivity(projectId, "workflow-started",
                "Workflow started: " + definition.name);
        sseEvents.fire(SseEvent.workflowUpdated(projectId));

        return entity;
    }

    /**
     * Called when a workflow-spawned task completes, advancing the workflow.
     */
    @Transactional
    public void onTaskCompleted(long taskId) {
        TaskEntity task = TaskEntity.findById(taskId);
        if (task == null || task.workflowRunId == null) {
            return;
        }

        WorkflowRunEntity entity =
                WorkflowRunEntity.findById(task.workflowRunId);
        if (entity == null) {
            LOG.warnf("Workflow instance %d not found for task %d",
                    task.workflowRunId, taskId);
            return;
        }

        Workflow workflow = loadWorkflowContent(
                entity.definitionId, entity.definitionVersion);
        WorkflowInstance instance = deserializeInstance(entity.instanceState);

        NodeResult result;
        if ("Completed".equals(task.status)) {
            Map<String, Object> output = parseOutputMap(task.output);
            result = new NodeResult(NodeResultStatus.COMPLETED, output);
        } else {
            result = new NodeResult(NodeResultStatus.FAILED, Map.of());
        }

        WorkflowInstance advanced = workflowEngine.completeCurrentNode(
                workflow, instance, result);

        persistInstanceState(entity, advanced);

        if (advanced.status() == InstanceStatus.WAITING) {
            createTaskForCurrentNode(entity, workflow, advanced);
        } else if (advanced.status() == InstanceStatus.COMPLETED) {
            entity.completedOn = Instant.now();
            logActivity(entity.projectId, "workflow-completed",
                    "Workflow completed");
        } else if (advanced.status() == InstanceStatus.FAILED) {
            entity.completedOn = Instant.now();
            logActivity(entity.projectId, "workflow-failed",
                    "Workflow failed: " + advanced.failureReason());
            sseEvents.fire(SseEvent.notification(
                    "Workflow failed for project", "error"));
        }

        sseEvents.fire(SseEvent.workflowUpdated(entity.projectId));
    }

    /**
     * Cancels a running or waiting workflow instance.
     */
    @Transactional
    public void cancelWorkflow(long projectId) {
        WorkflowRunEntity entity = WorkflowRunEntity
                .find("projectId", projectId).firstResult();
        if (entity == null) {
            throw new WebApplicationException("No workflow instance found", 404);
        }

        if ("completed".equals(entity.status)
                || "failed".equals(entity.status)
                || "cancelled".equals(entity.status)) {
            throw new WebApplicationException(
                    "Workflow instance is already in a terminal state", 409);
        }

        Workflow workflow = loadWorkflowContent(
                entity.definitionId, entity.definitionVersion);
        WorkflowInstance instance = deserializeInstance(entity.instanceState);

        WorkflowInstance cancelled = workflowEngine.cancelWorkflow(
                workflow, instance);

        persistInstanceState(entity, cancelled);
        entity.completedOn = Instant.now();

        TaskEntity activeTask = TaskEntity
                .find("workflowRunId = ?1 and status in ?2",
                        entity.id,
                        List.of("Pending", "InProgress"))
                .firstResult();
        if (activeTask != null) {
            activeTask.status = "Failed";
            activeTask.output = "Cancelled: workflow was cancelled";
            activeTask.completedOn = Instant.now();
        }

        logActivity(projectId, "workflow-cancelled", "Workflow cancelled");
        sseEvents.fire(SseEvent.workflowUpdated(projectId));
    }

    // -- Private helpers --

    /**
     * Rebuilds a {@link TraceContext} rooted at a run's trace root node, or
     * null if the run has no trace or the root cannot be found.
     */
    private TraceContext traceContextFor(WorkflowRunEntity run) {
        if (run.traceId == null) {
            return null;
        }
        io.apitomy.axiom.core.entities.TraceNodeEntity root =
                io.apitomy.axiom.core.entities.TraceNodeEntity.find(
                        "traceId = ?1 and parentNodeId is null", run.traceId)
                        .firstResult();
        if (root == null) {
            return null;
        }
        return new TraceContext(run.traceId, root.id);
    }

    private void createTaskForCurrentNode(WorkflowRunEntity entity,
            Workflow workflow, WorkflowInstance instance) {
        ActionInfo actionInfo = workflowEngine.getActionInfo(
                workflow, instance);
        if (actionInfo == null) {
            LOG.warnf("No action info for current node in instance %d",
                    entity.id);
            return;
        }

        TaskEntity task = new TaskEntity();
        task.projectId = entity.projectId;
        task.actionType = actionInfo.actionType();
        task.createdBy = "workflow";
        task.status = "Pending";
        task.input = serializeInputs(actionInfo);
        task.workflowRunId = entity.id;
        task.nodeId = instance.currentNodeId();
        task.traceId = entity.traceId;
        task.createdOn = Instant.now();
        task.persist();

        TraceContext traceCtx = traceContextFor(entity);
        if (traceCtx != null) {
            try {
                traceService.addNode(traceCtx, "task", "in-progress",
                        "Node: " + actionInfo.actionType(), "task", task.id);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to add workflow task trace node");
            }
        }

        LOG.infof("Created task %d for workflow instance %d (action: %s)",
                task.id, entity.id, actionInfo.actionType());

        sseEvents.fire(SseEvent.taskUpdated(
                entity.projectId, task.id, task.status));
    }

    private void persistInstanceState(WorkflowRunEntity entity,
            WorkflowInstance instance) {
        try {
            entity.instanceState = objectMapper.writeValueAsString(instance);
        } catch (JsonProcessingException e) {
            throw new WebApplicationException(
                    "Failed to serialize workflow instance state", 500);
        }
        entity.status = instance.status().name().toLowerCase();
        entity.currentNodeId = instance.currentNodeId();
        entity.failureReason = instance.failureReason();
    }

    private void validateNodeTypes(Workflow workflow) {
        List<String> unsupported = workflow.nodes().stream()
                .map(WorkflowNode::type)
                .filter(type -> !SUPPORTED_NODE_TYPES.contains(type))
                .map(NodeType::name)
                .distinct()
                .toList();
        if (!unsupported.isEmpty()) {
            throw new WebApplicationException(
                    "Workflow contains unsupported node types: "
                            + String.join(", ", unsupported)
                            + ". Phase 2 supports only: start, end, action.",
                    400);
        }
    }

    private Workflow deserializeWorkflow(String json) {
        try {
            return objectMapper.readValue(json, Workflow.class);
        } catch (JsonProcessingException e) {
            throw new WebApplicationException(
                    "Invalid workflow JSON: " + e.getMessage(), 400);
        }
    }

    private WorkflowInstance deserializeInstance(String json) {
        try {
            return objectMapper.readValue(json, WorkflowInstance.class);
        } catch (JsonProcessingException e) {
            throw new WebApplicationException(
                    "Invalid workflow instance state: " + e.getMessage(), 500);
        }
    }

    private Workflow loadWorkflowContent(long definitionId,
            int definitionVersion) {
        WorkflowDefinitionVersionEntity version =
                WorkflowDefinitionVersionEntity
                        .find("definitionId = ?1 and version = ?2",
                                definitionId, definitionVersion)
                        .firstResult();
        if (version == null) {
            throw new WebApplicationException(
                    "Workflow version not found", 500);
        }
        return deserializeWorkflow(version.content);
    }

    private Map<String, Object> parseOutputMap(String output) {
        if (output == null || output.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(
                    output, Map.class);
            return map;
        } catch (JsonProcessingException e) {
            return Map.of("rawOutput", output);
        }
    }

    private String serializeInputs(ActionInfo actionInfo) {
        if (actionInfo.resolvedInputs() == null
                || actionInfo.resolvedInputs().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(
                    actionInfo.resolvedInputs());
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void logActivity(Long projectId, String entryType,
            String summary) {
        ActivityLogEntity log = new ActivityLogEntity();
        log.projectId = projectId;
        log.entryType = entryType;
        log.summary = summary;
        log.createdOn = Instant.now();
        log.persist();

        sseEvents.fire(SseEvent.activity(entryType, summary));
    }
}
