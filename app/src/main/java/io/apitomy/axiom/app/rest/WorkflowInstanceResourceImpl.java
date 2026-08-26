package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.WorkflowInstanceResource;
import io.apitomy.axiom.api.beans.HistoryEntry;
import io.apitomy.axiom.api.beans.TriggerWorkflow;
import io.apitomy.axiom.app.WorkflowExecutionService;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowInstanceEntity;
import io.apitomy.flow.model.Workflow;
import io.apitomy.flow.model.WorkflowInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

import java.util.Date;
import java.util.List;

/**
 * Implementation of the Workflow Instance REST API. Provides endpoints for
 * triggering, querying, and cancelling workflow executions.
 */
@ApplicationScoped
public class WorkflowInstanceResourceImpl implements WorkflowInstanceResource {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Override
    public io.apitomy.axiom.api.beans.WorkflowInstance triggerProjectWorkflow(
            long projectId, TriggerWorkflow data) {
        WorkflowInstanceEntity entity = workflowExecutionService
                .triggerWorkflow(projectId, data.getWorkflowDefinitionId());
        return toBean(entity);
    }

    @Override
    public io.apitomy.axiom.api.beans.WorkflowInstance
            getProjectWorkflowInstance(long projectId) {
        WorkflowInstanceEntity entity = WorkflowInstanceEntity
                .find("projectId", projectId).firstResult();
        if (entity == null) {
            throw new WebApplicationException(404);
        }
        return toBean(entity);
    }

    @Override
    public void cancelProjectWorkflow(long projectId) {
        workflowExecutionService.cancelWorkflow(projectId);
    }

    private io.apitomy.axiom.api.beans.WorkflowInstance toBean(
            WorkflowInstanceEntity entity) {
        io.apitomy.axiom.api.beans.WorkflowInstance bean =
                new io.apitomy.axiom.api.beans.WorkflowInstance();

        bean.setId(entity.id);
        bean.setProjectId(entity.projectId);
        bean.setDefinitionId(entity.definitionId);
        bean.setDefinitionVersion(entity.definitionVersion);
        bean.setStatus(entity.status);
        bean.setCurrentNodeId(entity.currentNodeId);
        bean.setFailureReason(entity.failureReason);
        bean.setStartedOn(Date.from(entity.startedOn));
        if (entity.completedOn != null) {
            bean.setCompletedOn(Date.from(entity.completedOn));
        }

        WorkflowDefinitionEntity definition =
                WorkflowDefinitionEntity.findById(entity.definitionId);
        if (definition != null) {
            bean.setDefinitionName(definition.name);
        }

        WorkflowDefinitionVersionEntity version =
                WorkflowDefinitionVersionEntity
                        .find("definitionId = ?1 and version = ?2",
                                entity.definitionId,
                                entity.definitionVersion)
                        .firstResult();
        if (version != null) {
            try {
                bean.setWorkflowContent(objectMapper.readValue(
                        version.content, Object.class));
            } catch (JsonProcessingException e) {
                bean.setWorkflowContent(null);
            }

            if (entity.currentNodeId != null) {
                try {
                    Workflow workflow = objectMapper.readValue(
                            version.content, Workflow.class);
                    workflow.findNodeById(entity.currentNodeId)
                            .ifPresent(node ->
                                    bean.setCurrentNodeName(node.name()));
                } catch (JsonProcessingException ignored) {
                }
            }
        }

        try {
            WorkflowInstance flowInstance = objectMapper.readValue(
                    entity.instanceState, WorkflowInstance.class);
            bean.setContext(flowInstance.context());
            bean.setHistory(flowInstance.history().stream()
                    .map(this::toHistoryBean).toList());
        } catch (JsonProcessingException e) {
            bean.setHistory(List.of());
        }

        return bean;
    }

    private HistoryEntry toHistoryBean(
            io.apitomy.flow.model.HistoryEntry entry) {
        HistoryEntry bean = new HistoryEntry();
        bean.setNodeId(entry.nodeId());
        bean.setNodeName(entry.nodeName());
        if (entry.enteredOn() != null) {
            bean.setEnteredOn(Date.from(entry.enteredOn()));
        }
        if (entry.completedOn() != null) {
            bean.setCompletedOn(Date.from(entry.completedOn()));
        }
        if (entry.output() != null && !entry.output().isEmpty()) {
            bean.setOutput(entry.output());
        }
        return bean;
    }
}
