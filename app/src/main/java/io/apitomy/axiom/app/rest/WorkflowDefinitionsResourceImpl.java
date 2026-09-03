package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.WorkflowResource;
import io.apitomy.axiom.api.beans.Content;
import io.apitomy.axiom.api.beans.NewWorkflowDefinition;
import io.apitomy.axiom.api.beans.UpdateWorkflowDefinition;
import io.apitomy.axiom.api.beans.WorkflowDefinition;
import io.apitomy.axiom.api.beans.WorkflowDefinitionSearchResults;
import io.apitomy.axiom.api.beans.WorkflowDefinitionVersion;
import io.apitomy.axiom.app.WorkflowRunBeanMapper;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.flow.model.Workflow;
import io.apitomy.flow.model.WorkflowNode;
import io.apitomy.flow.validation.ValidationProblem;
import io.apitomy.flow.validation.ValidationSeverity;
import io.apitomy.flow.validation.WorkflowValidator;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Implementation of the Workflow Definitions REST API.
 */
@ApplicationScoped
@RunOnVirtualThread
public class WorkflowDefinitionsResourceImpl implements WorkflowResource {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WorkflowRunBeanMapper runBeanMapper;

    /** Inputs Axiom always injects when starting a workflow (may be marked required). */
    private static final Set<String> ALWAYS_PRESENT_INPUTS =
            Set.of("projectId", "projectName");

    /** All inputs Axiom may inject (always-present plus conditionally-present). */
    private static final List<String> CANONICAL_INPUT_NAMES =
            List.of("projectId", "projectName", "repository", "ref");

    private static final Set<String> CANONICAL_INPUTS = Set.copyOf(CANONICAL_INPUT_NAMES);

    // ── List ────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public WorkflowDefinitionSearchResults listWorkflowDefinitions(
            Integer page, Integer limit, String filterName) {
        int pageNum = page != null ? page : 1;
        int pageSize = limit != null ? limit : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterName != null && !filterName.isBlank()) {
            hql.append(" and lower(name) like :name");
            params.put("name", "%" + filterName.toLowerCase() + "%");
        }

        PanacheQuery<WorkflowDefinitionEntity> query = WorkflowDefinitionEntity
                .find(hql.toString(), Sort.ascending("name"), params)
                .page(Page.of(pageNum - 1, pageSize));

        WorkflowDefinitionSearchResults results = new WorkflowDefinitionSearchResults();
        results.setItems(query.list().stream().map(this::toBean).toList());
        results.setTotalCount(query.count());
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    // ── Create ──────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public WorkflowDefinition createWorkflowDefinition(NewWorkflowDefinition data) {
        checkDuplicateName(data.getName(), null);

        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
        entity.name = data.getName();
        entity.description = data.getDescription();
        entity.content = createEmptyWorkflowContent(data.getName());
        entity.createdOn = Instant.now();
        entity.updatedOn = Instant.now();
        entity.persist();

        return toBean(entity);
    }

    // ── Get ─────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public WorkflowDefinition getWorkflowDefinition(long workflowDefinitionId) {
        return toBean(findOrThrow(workflowDefinitionId));
    }

    // ── Update metadata ─────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public WorkflowDefinition updateWorkflowDefinition(
            long workflowDefinitionId, UpdateWorkflowDefinition data) {
        WorkflowDefinitionEntity entity = findOrThrow(workflowDefinitionId);

        if (data.getName() != null) {
            checkDuplicateName(data.getName(), entity.id);
            entity.name = data.getName();
        }
        if (data.getDescription() != null) {
            entity.description = data.getDescription();
        }
        entity.updatedOn = Instant.now();

        return toBean(entity);
    }

    // ── Delete ──────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteWorkflowDefinition(long workflowDefinitionId) {
        WorkflowDefinitionEntity entity = findOrThrow(workflowDefinitionId);

        // Cascade delete everything that references this workflow definition. Deletes are
        // performed explicitly (rather than relying on database ON DELETE CASCADE) so the
        // behavior is deterministic across database engines.
        List<Long> runIds = WorkflowRunEntity
                .<WorkflowRunEntity>list("definitionId", workflowDefinitionId)
                .stream()
                .map(run -> run.id)
                .toList();

        if (!runIds.isEmpty()) {
            // Delete human tasks belonging to the runs first (task.workflow_run_id FK).
            TaskEntity.delete("workflowRunId in ?1", runIds);
            // Then delete the runs themselves.
            WorkflowRunEntity.delete("definitionId", workflowDefinitionId);
        }

        // Delete the published versions of the definition.
        WorkflowDefinitionVersionEntity.delete("definitionId", workflowDefinitionId);

        // Finally delete the definition itself.
        entity.delete();
    }

    // ── Update content ──────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void updateWorkflowDefinitionContent(long workflowDefinitionId, InputStream data) {
        WorkflowDefinitionEntity entity = findOrThrow(workflowDefinitionId);
        try {
            entity.content = new String(data.readAllBytes());
        } catch (Exception e) {
            throw new WebApplicationException("Invalid workflow content", 400);
        }
        entity.updatedOn = Instant.now();
    }

    // ── Publish ─────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public WorkflowDefinitionVersion publishWorkflowDefinition(long workflowDefinitionId) {
        WorkflowDefinitionEntity entity = findOrThrow(workflowDefinitionId);

        if (entity.content == null || entity.content.isBlank()) {
            throw new WebApplicationException("No draft content to publish", 400);
        }

        Workflow workflow;
        try {
            workflow = objectMapper.readValue(entity.content, Workflow.class);
        } catch (JsonProcessingException e) {
            throw new WebApplicationException("Invalid workflow JSON: " + e.getMessage(), 400);
        }

        WorkflowValidator validator = new WorkflowValidator();
        List<ValidationProblem> problems = validator.validate(workflow);
        List<ValidationProblem> errors = problems.stream()
                .filter(p -> p.severity() == ValidationSeverity.ERROR)
                .toList();
        if (!errors.isEmpty()) {
            throw new WebApplicationException(
                    Response.status(400).entity(errors).build());
        }

        validateStartInputs(workflow);

        int newVersion = entity.currentVersion != null ? entity.currentVersion + 1 : 1;

        WorkflowDefinitionVersionEntity version = new WorkflowDefinitionVersionEntity();
        version.definitionId = entity.id;
        version.version = newVersion;
        version.content = entity.content;
        version.createdOn = Instant.now();
        version.persist();

        entity.currentVersion = newVersion;
        entity.updatedOn = Instant.now();

        return toVersionBean(version);
    }

    // ── List versions ───────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public List<WorkflowDefinitionVersion> listWorkflowDefinitionVersions(
            long workflowDefinitionId) {
        findOrThrow(workflowDefinitionId);
        List<WorkflowDefinitionVersionEntity> versions = WorkflowDefinitionVersionEntity
                .find("definitionId", Sort.descending("version"), workflowDefinitionId)
                .list();
        return versions.stream().map(this::toVersionBean).toList();
    }

    // ── Get version ─────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public WorkflowDefinitionVersion getWorkflowDefinitionVersion(
            long workflowDefinitionId, int version) {
        findOrThrow(workflowDefinitionId);
        WorkflowDefinitionVersionEntity entity = WorkflowDefinitionVersionEntity
                .find("definitionId = ?1 and version = ?2", workflowDefinitionId, version)
                .firstResult();
        if (entity == null) {
            throw new WebApplicationException(404);
        }
        return toVersionBean(entity);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    /**
     * Finds a workflow definition by ID or throws a 404 exception.
     */
    private WorkflowDefinitionEntity findOrThrow(long id) {
        WorkflowDefinitionEntity entity = WorkflowDefinitionEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException(404);
        }
        return entity;
    }

    /**
     * Checks if a workflow definition name is already in use.
     */
    private void checkDuplicateName(String name, Long excludeId) {
        WorkflowDefinitionEntity existing = WorkflowDefinitionEntity
                .find("name", name).firstResult();
        if (existing != null && (excludeId == null || !existing.id.equals(excludeId))) {
            throw new WebApplicationException("Workflow definition name already exists", 409);
        }
    }

    /**
     * Validates that a workflow's Start node only declares inputs from the canonical
     * input contract, and only marks always-present inputs as required. Throws a 400
     * WebApplicationException on any violation.
     */
    private void validateStartInputs(Workflow workflow) {
        WorkflowNode startNode = workflow.findStartNode().orElse(null);
        if (startNode == null) {
            return; // missing Start node is handled by structural validation
        }
        Object inputsDef = startNode.config().get("inputs");
        if (!(inputsDef instanceof List<?> inputs)) {
            return;
        }
        for (Object inputObj : inputs) {
            if (!(inputObj instanceof Map<?, ?> input)) {
                continue;
            }
            Object nameObj = input.get("name");
            String name = nameObj != null ? nameObj.toString() : null;
            if (name == null || !CANONICAL_INPUTS.contains(name)) {
                throw new WebApplicationException(
                        Response.status(400).entity(Map.of("message",
                                "Start node input '" + name + "' is not part of the "
                                        + "workflow input contract. Allowed inputs: "
                                        + String.join(", ", CANONICAL_INPUT_NAMES)))
                                .build());
            }
            if (Boolean.TRUE.equals(input.get("required"))
                    && !ALWAYS_PRESENT_INPUTS.contains(name)) {
                throw new WebApplicationException(
                        Response.status(400).entity(Map.of("message",
                                "Start node input '" + name + "' cannot be marked "
                                        + "required because Axiom does not always provide "
                                        + "it. Only projectId and projectName may be "
                                        + "required.")).build());
            }
        }
    }

    /**
     * Converts a WorkflowDefinitionEntity to a response bean.
     */
    private WorkflowDefinition toBean(WorkflowDefinitionEntity entity) {
        WorkflowDefinition bean = new WorkflowDefinition();
        bean.setId(entity.id);
        bean.setName(entity.name);
        bean.setDescription(entity.description);
        if (entity.content != null) {
            try {
                bean.setContent(objectMapper.readValue(entity.content, Content.class));
            } catch (JsonProcessingException e) {
                bean.setContent(null);
            }
        }
        bean.setCurrentVersion(entity.currentVersion);
        bean.setCreatedOn(Date.from(entity.createdOn));
        bean.setUpdatedOn(Date.from(entity.updatedOn));
        return bean;
    }

    /**
     * Converts a WorkflowDefinitionVersionEntity to a response bean.
     */
    private WorkflowDefinitionVersion toVersionBean(WorkflowDefinitionVersionEntity entity) {
        WorkflowDefinitionVersion bean = new WorkflowDefinitionVersion();
        bean.setId(entity.id);
        bean.setDefinitionId(entity.definitionId);
        bean.setVersion(entity.version);
        if (entity.content != null) {
            try {
                bean.setContent(objectMapper.readValue(entity.content, Content.class));
            } catch (JsonProcessingException e) {
                bean.setContent(null);
            }
        }
        bean.setCreatedOn(Date.from(entity.createdOn));
        return bean;
    }

    /**
     * Creates an empty workflow JSON with start and end nodes.
     */
    private String createEmptyWorkflowContent(String name) {
        List<Map<String, Object>> startInputs = List.of(
                Map.of("name", "projectId", "type", "number",
                        "required", true,
                        "description", "The Axiom project id"),
                Map.of("name", "projectName", "type", "string",
                        "required", true,
                        "description", "The Axiom project name"),
                Map.of("name", "repository", "type", "string",
                        "required", false,
                        "description", "The project git repository, if any"),
                Map.of("name", "ref", "type", "string",
                        "required", false,
                        "description", "The project git ref, if any"));
        Map<String, Object> startNode = Map.of(
                "id", "start-1",
                "type", "start",
                "name", "Start",
                "config", Map.of("inputs", startInputs),
                "position", Map.of("x", 250, "y", 100));
        Map<String, Object> endNode = Map.of(
                "id", "end-1",
                "type", "end",
                "name", "End",
                "config", Map.of(),
                "position", Map.of("x", 250, "y", 400));
        Map<String, Object> edge = Map.of(
                "id", "edge-1",
                "source", "start-1",
                "target", "end-1",
                "priority", 0,
                "isDefault", true);
        Map<String, Object> workflow = Map.of(
                "id", UUID.randomUUID().toString(),
                "name", name,
                "nodes", List.of(startNode, endNode),
                "edges", List.of(edge));
        try {
            return objectMapper.writeValueAsString(workflow);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    // ── Workflow Runs ─────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public io.apitomy.axiom.api.beans.WorkflowRunSearchResults listWorkflowRuns(
            Long projectId, String status, java.math.BigInteger page, java.math.BigInteger limit) {
        return runBeanMapper.search(projectId, null, status, page, limit);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public io.apitomy.axiom.api.beans.WorkflowInstance getWorkflowRun(long runId) {
        WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
        if (run == null) {
            throw new WebApplicationException("Workflow run not found: " + runId,
                    Response.Status.NOT_FOUND);
        }
        return runBeanMapper.toBean(run);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public io.apitomy.axiom.api.beans.WorkflowRunSearchResults listWorkflowDefinitionRuns(
            long workflowDefinitionId, String status, java.math.BigInteger page, java.math.BigInteger limit) {
        findOrThrow(workflowDefinitionId);
        return runBeanMapper.search(null, workflowDefinitionId, status, page, limit);
    }
}
