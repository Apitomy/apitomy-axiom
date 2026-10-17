package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.api.InboxResource;
import io.apitomy.axiom.api.beans.HumanContext;
import io.apitomy.axiom.api.beans.HumanContextReference;
import io.apitomy.axiom.api.beans.InboxCount;
import io.apitomy.axiom.api.beans.InboxItem;
import io.apitomy.axiom.api.beans.InboxResponse;
import io.apitomy.axiom.api.beans.InboxSearchResults;
import io.apitomy.axiom.api.beans.NewInboxItem;
import io.apitomy.axiom.app.HumanTaskStatuses;
import io.apitomy.axiom.app.TaskExecutionService;
import io.apitomy.axiom.api.beans.OutputSchema;
import io.apitomy.axiom.api.beans.OutputSchemaField;
import io.apitomy.axiom.api.beans.OutputSchemaFieldOption;
import io.apitomy.axiom.core.entities.SubjectEntity;
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
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
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
    InboxResponseValidator responseValidator;

    @Inject
    TaskExecutionService taskExecutionService;

    /**
     * {@inheritDoc}
     */
    @Override
    public InboxSearchResults listInboxItems(BigInteger page, BigInteger limit,
                                             String filterLabels) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("status = :status");
        Map<String, Object> params = new HashMap<>();
        params.put("status", HumanTaskStatuses.AWAITING_INPUT);

        if (filterLabels != null && !filterLabels.isBlank()) {
            List<String> labels = Arrays.stream(filterLabels.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            hql.append(" and subjectId in (SELECT p.id FROM SubjectEntity p"
                    + " JOIN p.labels pl WHERE pl IN :labels"
                    + " GROUP BY p.id HAVING COUNT(DISTINCT pl) = :labelCount)");
            params.put("labels", labels);
            params.put("labelCount", (long) labels.size());
        }

        long totalCount = TaskEntity.count(hql.toString(), params);
        List<TaskEntity> taskEntities = TaskEntity.<TaskEntity>find(
                        hql.toString(), Sort.descending("createdOn"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list();

        // Batch-fetch subject names to avoid N+1 queries
        Map<Long, String> subjectNames = new java.util.HashMap<>();
        for (TaskEntity t : taskEntities) {
            subjectNames.putIfAbsent(t.subjectId, null);
        }
        for (Long pid : subjectNames.keySet()) {
            SubjectEntity p = SubjectEntity.findById(pid);
            if (p != null) {
                subjectNames.put(pid, p.name);
            }
        }

        List<InboxItem> items = taskEntities.stream()
                .map(entity -> toInboxItem(entity, subjectNames.get(entity.subjectId)))
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
        long count = TaskEntity.count("status", HumanTaskStatuses.AWAITING_INPUT);
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
        long subjectId = data.getSubjectId();
        SubjectEntity subject = SubjectEntity.findById(subjectId);
        if (subject == null) {
            throw new WebApplicationException("Subject not found: " + subjectId, 404);
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
        task.subjectId = subjectId;
        task.actionType = data.getActionType();
        task.createdBy = "user";
        task.status = "Pending";
        task.humanContext = humanContextJson;
        task.outputSchema = outputSchemaJson;
        task.createdOn = Instant.now();
        task.persist();

        LOG.infof("Created direct human task %d (%s) for subject %d",
                task.id, task.actionType, subjectId);

        // Mark the task as awaiting human input
        taskExecutionService.markTaskAwaitingInput(task.id);

        return toInboxItem(task, subject.name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InboxItem getInboxItem(BigInteger taskId) {
        long tid = taskId.longValue();
        TaskEntity task = TaskEntity.findById(tid);

        if (task == null || !HumanTaskStatuses.AWAITING_INPUT.equals(task.status)) {
            throw new WebApplicationException("Inbox item not found: " + tid, 404);
        }

        SubjectEntity subject = SubjectEntity.findById(task.subjectId);
        String subjectName = subject != null ? subject.name : null;
        return toInboxItem(task, subjectName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void completeInboxItem(BigInteger taskId, InboxResponse data) {
        long tid = taskId.longValue();
        TaskEntity task = TaskEntity.findById(tid);

        // A Cancelled task falls through so completeHumanTask can report the 409.
        if (task == null || !(HumanTaskStatuses.AWAITING_INPUT.equals(task.status)
                || HumanTaskStatuses.CANCELLED.equals(task.status))) {
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
            // Re-checks status and run state under a run lock: 404 / 409 if it lost a race.
            taskExecutionService.completeHumanTask(tid, AgentResult.success(responseJson));

            LOG.infof("Inbox item %d completed with structured response", tid);
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to complete inbox item %d", tid);
            throw new WebApplicationException("Failed to process response: " + e.getMessage(), 500);
        }
    }

    private InboxItem toInboxItem(TaskEntity entity, String subjectName) {
        InboxItem item = new InboxItem();
        item.setId(entity.id);
        item.setSubjectId(entity.subjectId);
        item.setActionType(entity.actionType);
        item.setStatus(entity.status);
        item.setInput(entity.input);
        item.setCreatedOn(Date.from(entity.createdOn));
        item.setEventId(entity.eventId);
        if (entity.traceId != null) {
            item.setTraceId(entity.traceId);
        }

        if (subjectName != null) {
            item.setSubjectName(subjectName);
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
        if (node.has("details") && node.get("details").isArray()) {
            java.util.List<io.apitomy.axiom.api.beans.HumanContextDetail> details =
                    new java.util.ArrayList<>();
            for (JsonNode detailNode : node.get("details")) {
                io.apitomy.axiom.api.beans.HumanContextDetail detail =
                        new io.apitomy.axiom.api.beans.HumanContextDetail();
                detail.setLabel(detailNode.path("label").asText(""));
                detail.setValue(detailNode.path("value").asText(""));
                details.add(detail);
            }
            ctx.setDetails(details);
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
                if (fieldNode.has("defaultValue") && !fieldNode.get("defaultValue").isNull()) {
                    field.setDefaultValue(
                            objectMapper.convertValue(fieldNode.get("defaultValue"), Object.class));
                }

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
