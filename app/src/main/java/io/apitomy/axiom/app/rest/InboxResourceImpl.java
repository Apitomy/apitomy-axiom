package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.actors.human.HumanActor;
import io.apitomy.axiom.api.InboxResource;
import io.apitomy.axiom.api.beans.HumanContext;
import io.apitomy.axiom.api.beans.HumanContextReference;
import io.apitomy.axiom.api.beans.InboxCount;
import io.apitomy.axiom.api.beans.InboxItem;
import io.apitomy.axiom.api.beans.InboxResponse;
import io.apitomy.axiom.api.beans.InboxSearchResults;
import io.apitomy.axiom.api.beans.NewInboxItem;
import io.apitomy.axiom.app.TaskExecutionService;
import io.apitomy.axiom.api.beans.OutputSchema;
import io.apitomy.axiom.api.beans.OutputSchemaField;
import io.apitomy.axiom.api.beans.OutputSchemaFieldOption;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.jboss.logging.Logger;

import jakarta.transaction.Transactional;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the Inbox REST API. Provides access to human tasks
 * awaiting user input and allows completing them with structured responses.
 */
@ApplicationScoped
@RunOnVirtualThread
public class InboxResourceImpl implements InboxResource {

    private static final Logger LOG = Logger.getLogger(InboxResourceImpl.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    HumanActor humanActor;

    @Inject
    InboxResponseValidator responseValidator;

    @Inject
    TaskExecutionService taskExecutionService;

    /**
     * {@inheritDoc}
     */
    @Override
    public InboxSearchResults listInboxItems(BigInteger page, BigInteger limit) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        long totalCount = TaskEntity.count("status", "AwaitingInput");
        List<TaskEntity> taskEntities = TaskEntity.<TaskEntity>find(
                        "status = ?1", Sort.descending("createdOn"), "AwaitingInput")
                .page(Page.of(pageNum - 1, pageSize))
                .list();

        // Batch-fetch project names to avoid N+1 queries
        Map<Long, String> projectNames = new java.util.HashMap<>();
        for (TaskEntity t : taskEntities) {
            projectNames.putIfAbsent(t.projectId, null);
        }
        for (Long pid : projectNames.keySet()) {
            ProjectEntity p = ProjectEntity.findById(pid);
            if (p != null) {
                projectNames.put(pid, p.name);
            }
        }

        List<InboxItem> items = taskEntities.stream()
                .map(entity -> toInboxItem(entity, projectNames.get(entity.projectId)))
                .toList();

        InboxSearchResults results = new InboxSearchResults();
        results.setItems(items);
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InboxCount getInboxCount() {
        long count = TaskEntity.count("status", "AwaitingInput");
        InboxCount result = new InboxCount();
        result.setCount(count);
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public InboxItem createInboxItem(NewInboxItem data) {
        long projectId = data.getProjectId();
        ProjectEntity project = ProjectEntity.findById(projectId);
        if (project == null) {
            throw new WebApplicationException("Project not found: " + projectId, 404);
        }

        // Serialize humanContext and outputSchema beans to JSON for storage
        String humanContextJson = null;
        if (data.getHumanContext() != null) {
            try {
                humanContextJson = objectMapper.writeValueAsString(data.getHumanContext());
            } catch (Exception e) {
                throw new WebApplicationException("Invalid humanContext: " + e.getMessage(), 400);
            }
        }

        String outputSchemaJson = null;
        if (data.getOutputSchema() != null) {
            try {
                outputSchemaJson = objectMapper.writeValueAsString(data.getOutputSchema());
            } catch (Exception e) {
                throw new WebApplicationException("Invalid outputSchema: " + e.getMessage(), 400);
            }
        }

        // Create and persist the task entity
        TaskEntity task = new TaskEntity();
        task.projectId = projectId;
        task.actionType = data.getActionType();
        task.createdBy = "user";
        task.status = "Pending";
        task.humanContext = humanContextJson;
        task.outputSchema = outputSchemaJson;
        task.createdOn = Instant.now();
        task.persist();

        LOG.infof("Created direct human task %d (%s) for project %d",
                task.id, task.actionType, projectId);

        // Register with HumanActor and wire completion callback
        taskExecutionService.registerDirectHumanTask(task.id);

        return toInboxItem(task, project.name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InboxItem getInboxItem(BigInteger taskId) {
        long tid = taskId.longValue();
        TaskEntity task = TaskEntity.findById(tid);

        if (task == null || !"AwaitingInput".equals(task.status)) {
            throw new WebApplicationException("Inbox item not found: " + tid, 404);
        }

        ProjectEntity project = ProjectEntity.findById(task.projectId);
        String projectName = project != null ? project.name : null;
        return toInboxItem(task, projectName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void completeInboxItem(BigInteger taskId, InboxResponse data) {
        long tid = taskId.longValue();
        TaskEntity task = TaskEntity.findById(tid);

        if (task == null || !"AwaitingInput".equals(task.status)) {
            throw new WebApplicationException("Inbox item not found or not awaiting input: " + tid, 404);
        }

        Map<String, Object> responseMap = data.getAdditionalProperties();

        // Validate against output schema
        List<String> validationErrors = responseValidator.validate(task.outputSchema, responseMap);
        if (!validationErrors.isEmpty()) {
            throw new WebApplicationException(
                    "Validation failed: " + String.join("; ", validationErrors), 400);
        }

        // Serialize response to JSON and submit
        try {
            String responseJson = objectMapper.writeValueAsString(responseMap);
            boolean accepted = humanActor.submitResponse(tid, responseJson);
            if (!accepted) {
                throw new WebApplicationException("Task is not pending a human response", 409);
            }

            LOG.infof("Inbox item %d completed with structured response", tid);
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to complete inbox item %d", tid);
            throw new WebApplicationException("Failed to process response: " + e.getMessage(), 500);
        }
    }

    private InboxItem toInboxItem(TaskEntity entity, String projectName) {
        InboxItem item = new InboxItem();
        item.setId(entity.id);
        item.setProjectId(entity.projectId);
        item.setActionType(entity.actionType);
        item.setStatus(entity.status);
        item.setInput(entity.input);
        item.setCreatedOn(Date.from(entity.createdOn));
        item.setEventId(entity.eventId);
        if (entity.traceId != null) {
            item.setTraceId(entity.traceId);
        }

        if (projectName != null) {
            item.setProjectName(projectName);
        }

        // Parse humanContext JSON
        if (entity.humanContext != null) {
            try {
                item.setHumanContext(parseHumanContext(entity.humanContext));
            } catch (Exception e) {
                LOG.warnf("Failed to parse humanContext for task %d: %s", entity.id, e.getMessage());
            }
        }

        // Parse outputSchema JSON
        if (entity.outputSchema != null) {
            try {
                item.setOutputSchema(parseOutputSchema(entity.outputSchema));
            } catch (Exception e) {
                LOG.warnf("Failed to parse outputSchema for task %d: %s", entity.id, e.getMessage());
            }
        }

        return item;
    }

    private HumanContext parseHumanContext(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        HumanContext ctx = new HumanContext();
        ctx.setTitle(node.path("title").asText(""));
        if (node.has("description")) {
            ctx.setDescription(node.get("description").asText());
        }
        if (node.has("references") && node.get("references").isArray()) {
            List<HumanContextReference> refs = new ArrayList<>();
            for (JsonNode refNode : node.get("references")) {
                HumanContextReference ref = new HumanContextReference();
                ref.setLabel(refNode.path("label").asText(""));
                ref.setUrl(refNode.path("url").asText(""));
                refs.add(ref);
            }
            ctx.setReferences(refs);
        }
        return ctx;
    }

    private OutputSchema parseOutputSchema(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        OutputSchema schema = new OutputSchema();
        List<OutputSchemaField> fields = new ArrayList<>();

        JsonNode fieldsNode = node.path("fields");
        if (fieldsNode.isArray()) {
            for (JsonNode fieldNode : fieldsNode) {
                OutputSchemaField field = new OutputSchemaField();
                field.setName(fieldNode.path("name").asText());
                field.setType(OutputSchemaField.Type.fromValue(fieldNode.path("type").asText("text")));
                field.setLabel(fieldNode.path("label").asText());
                if (fieldNode.has("description")) {
                    field.setDescription(fieldNode.get("description").asText());
                }
                field.setRequired(fieldNode.path("required").asBoolean(false));

                if (fieldNode.has("options") && fieldNode.get("options").isArray()) {
                    List<OutputSchemaFieldOption> options = new ArrayList<>();
                    for (JsonNode optNode : fieldNode.get("options")) {
                        OutputSchemaFieldOption opt = new OutputSchemaFieldOption();
                        opt.setLabel(optNode.path("label").asText(""));
                        opt.setValue(optNode.path("value").asText(""));
                        options.add(opt);
                    }
                    field.setOptions(options);
                }

                fields.add(field);
            }
        }

        schema.setFields(fields);
        return schema;
    }
}
