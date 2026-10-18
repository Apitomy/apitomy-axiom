package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.SdkFunctionRegistry;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes Axiom SDK function calls directly, without going through an
 * AI agent. Used by workflow action nodes with the "sdk:" prefix.
 */
@ApplicationScoped
public class SdkCallService {

    private static final Logger LOG = Logger.getLogger(SdkCallService.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    jakarta.enterprise.event.Event<io.apitomy.axiom.core.events.StreamEventPersisted> persistedEvents;

    /** Lazy: the workflow engine itself calls this service for {@code sdk:} nodes. */
    @Inject
    jakarta.enterprise.inject.Instance<WorkflowExecutionService> workflowExecutionService;

    /**
     * Result of an SDK call.
     */
    public record SdkCallResult(boolean success, String output, Map<String, Object> outputMap) {
        public static SdkCallResult ok(String output) {
            return new SdkCallResult(true, output, Map.of());
        }
        public static SdkCallResult ok(String output, Map<String, Object> outputMap) {
            return new SdkCallResult(true, output, outputMap);
        }
        public static SdkCallResult error(String message) {
            return new SdkCallResult(false, message, Map.of());
        }
    }

    /**
     * Executes an SDK function by name with the given parameters.
     *
     * @param functionName the SDK function name (without "sdk:" prefix)
     * @param params       the parameters as a map
     * @return the result
     */
    public SdkCallResult execute(String functionName, Map<String, Object> params) {
        LOG.infof("SDK call: %s(%s)", functionName, params);

        return switch (functionName) {
            case "axiom_add_subject_label" -> addSubjectLabel(params);
            case "axiom_remove_subject_label" -> removeSubjectLabel(params);
            case "axiom_update_subject" -> updateSubject(params);
            case "axiom_add_thread_entry" -> addThreadEntry(params);
            case "axiom_get_subject" -> getSubject(params);
            case "axiom_fire_event" -> fireEvent(params);
            case "axiom_list_subjects" -> listSubjects(params);
            case "axiom_create_subject" -> createSubject(params);
            case "axiom_update_subject_body" -> updateSubjectBody(params);
            case "axiom_get_workflow_run" -> getWorkflowRun(params);
            case "axiom_set_workflow_run_title" -> setWorkflowRunTitle(params);
            case "axiom_list_subject_runs" -> listSubjectRuns(params);
            default -> SdkCallResult.error("Unknown SDK function: " + functionName);
        };
    }

    /**
     * Returns true if the given function name is a recognized SDK function
     * that supports direct (non-MCP) execution.
     */
    public boolean isValidFunction(String functionName) {
        return SdkFunctionRegistry.isValidSdkCall(functionName);
    }

    private SdkCallResult addSubjectLabel(Map<String, Object> params) {
        Long subjectId = toLong(params.get("subjectId"));
        String label = toString(params.get("label"));
        if (subjectId == null) return SdkCallResult.error("subjectId is required");
        if (label == null) return SdkCallResult.error("label is required");
        SubjectEntity subject = SubjectEntity.findById(subjectId);
        if (subject == null) return SdkCallResult.error("Subject not found: " + subjectId);
        if (!subject.labels.contains(label)) {
            subject.labels.add(label);
            subject.updatedOn = Instant.now();
        }
        return SdkCallResult.ok("Label '" + label + "' added to subject " + subjectId);
    }

    private SdkCallResult removeSubjectLabel(Map<String, Object> params) {
        Long subjectId = toLong(params.get("subjectId"));
        String label = toString(params.get("label"));
        if (subjectId == null) return SdkCallResult.error("subjectId is required");
        if (label == null) return SdkCallResult.error("label is required");
        SubjectEntity subject = SubjectEntity.findById(subjectId);
        if (subject == null) return SdkCallResult.error("Subject not found: " + subjectId);
        subject.labels.remove(label);
        subject.updatedOn = Instant.now();
        return SdkCallResult.ok("Label '" + label + "' removed from subject " + subjectId);
    }

    private SdkCallResult updateSubject(Map<String, Object> params) {
        Long subjectId = toLong(params.get("subjectId"));
        if (subjectId == null) return SdkCallResult.error("subjectId is required");
        SubjectEntity subject = SubjectEntity.findById(subjectId);
        if (subject == null) return SdkCallResult.error("Subject not found: " + subjectId);
        String name = toString(params.get("name"));
        if (name != null) subject.name = name;
        subject.updatedOn = Instant.now();
        return SdkCallResult.ok("Subject " + subjectId + " updated");
    }

    private SdkCallResult addThreadEntry(Map<String, Object> params) {
        Long subjectId = toLong(params.get("subjectId"));
        String content = toString(params.get("content"));
        if (subjectId == null) return SdkCallResult.error("subjectId is required");
        if (content == null) return SdkCallResult.error("content is required");
        ThreadEntryEntity entry = new ThreadEntryEntity();
        entry.subjectId = subjectId;
        entry.authorType = toString(params.getOrDefault("authorType", "system"));
        entry.entryType = toString(params.getOrDefault("entryType", "message"));
        entry.content = content;
        entry.createdOn = Instant.now();
        entry.persist();
        return SdkCallResult.ok("Thread entry added to subject " + subjectId);
    }

    private SdkCallResult getSubject(Map<String, Object> params) {
        Long subjectId = toLong(params.get("subjectId"));
        if (subjectId == null) return SdkCallResult.error("subjectId is required");
        SubjectEntity subject = SubjectEntity.findById(subjectId);
        if (subject == null) return SdkCallResult.error("Subject not found: " + subjectId);
        return SdkCallResult.ok("Subject found", Map.of(
                "id", subject.id,
                "name", subject.name != null ? subject.name : "",
                "active", SubjectEntity.isActive(subject.id),
                "ref", subject.ref != null ? subject.ref : "",
                "type", subject.type != null ? subject.type : ""
        ));
    }

    private SdkCallResult fireEvent(Map<String, Object> params) {
        String source = toString(params.get("source"));
        String eventType = toString(params.get("eventType"));
        String payload = toString(params.get("payload"));
        if (source == null) return SdkCallResult.error("source is required");
        if (eventType == null) return SdkCallResult.error("eventType is required");
        if (payload == null) payload = "{}";

        StreamEventEntity event = new StreamEventEntity();
        event.id = java.util.UUID.randomUUID();
        event.sourceEventId = "sdk-" + event.id;
        event.source = source;
        event.eventSourceId = "sdk";
        event.type = eventType;
        event.ref = toString(params.getOrDefault("ref", ""));
        event.timestamp = Instant.now();
        event.actor = "{\"login\":\"workflow\"}";
        event.payload = payload;
        event.createdOn = Instant.now();
        event.persist();
        persistedEvents.fire(new io.apitomy.axiom.core.events.StreamEventPersisted(
                event.id.toString(), event.type, event.eventSourceId));

        return SdkCallResult.ok("Event fired: " + eventType,
                Map.of("eventId", event.id.toString()));
    }

    private SdkCallResult listSubjects(Map<String, Object> params) {
        String filterName = toString(params.get("filterName"));
        String filterRef = toString(params.get("filterRef"));

        StringBuilder hql = new StringBuilder("1=1");
        java.util.Map<String, Object> queryParams = new java.util.HashMap<>();

        if (filterName != null) {
            hql.append(" and (lower(name) like :name or lower(ref) like :name)");
            queryParams.put("name", "%" + filterName.toLowerCase() + "%");
        }
        if (filterRef != null) {
            hql.append(" and ref = :ref");
            queryParams.put("ref", filterRef);
        }

        var subjects = SubjectEntity.<SubjectEntity>find(hql.toString(),
                io.quarkus.panache.common.Sort.descending("createdOn"), queryParams)
                .page(0, 50).list();

        java.util.Set<Long> activeIds = SubjectEntity.findActiveSubjectIds();
        var items = subjects.stream().map(p -> Map.<String, Object>of(
                "id", p.id,
                "name", p.name != null ? p.name : "",
                "active", activeIds.contains(p.id),
                "ref", p.ref != null ? p.ref : "",
                "type", p.type != null ? p.type : ""
        )).toList();

        return SdkCallResult.ok("Found " + items.size() + " subjects",
                Map.of("subjects", items, "count", items.size()));
    }

    private SdkCallResult createSubject(Map<String, Object> params) {
        String name = toString(params.get("name"));
        String type = toString(params.get("type"));
        String ref = toString(params.get("ref"));
        if (name == null) return SdkCallResult.error("name is required");
        if (type == null) return SdkCallResult.error("type is required");
        if (ref == null) return SdkCallResult.error("ref is required");

        SubjectEntity subject = new SubjectEntity();
        subject.name = name;
        subject.type = type;
        subject.ref = ref;
        subject.refSource = toString(params.get("refSource"));
        subject.repository = toString(params.get("repository"));
        subject.body = toString(params.get("body"));
        subject.createdOn = Instant.now();
        subject.updatedOn = Instant.now();
        subject.persist();

        return SdkCallResult.ok("Subject " + subject.id + " created",
                Map.of("subjectId", subject.id));
    }

    private SdkCallResult updateSubjectBody(Map<String, Object> params) {
        Long subjectId = toLong(params.get("subjectId"));
        String body = toString(params.get("body"));
        if (subjectId == null) return SdkCallResult.error("subjectId is required");
        if (body == null) return SdkCallResult.error("body is required");
        SubjectEntity subject = SubjectEntity.findById(subjectId);
        if (subject == null) return SdkCallResult.error("Subject not found: " + subjectId);
        subject.body = body;
        subject.updatedOn = Instant.now();
        return SdkCallResult.ok("Subject " + subjectId + " body updated");
    }

    /**
     * Returns a run's metadata. {@code runId} defaults to the calling run, which the workflow
     * engine injects into the params of direct SDK calls.
     */
    private SdkCallResult getWorkflowRun(Map<String, Object> params) {
        Long runId = toLong(params.get("runId"));
        if (runId == null) return SdkCallResult.error("runId is required");
        WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
        if (run == null) return SdkCallResult.error("Workflow run not found: " + runId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", run.id);
        result.put("title", run.title);
        result.put("status", run.status);
        result.put("definitionId", run.definitionId);
        result.put("definitionVersion", run.definitionVersion);
        result.put("subjectId", run.subjectId);
        result.put("labels", readJsonObject(run.labels));
        result.put("inputs", readJsonObject(run.inputs));
        result.put("currentNodeId", run.currentNodeId);
        result.put("startedOn", run.startedOn.toString());
        result.put("completedOn", run.completedOn != null ? run.completedOn.toString() : null);
        return SdkCallResult.ok("Workflow run found", result);
    }

    /**
     * Sets a run's title; a blank title clears it. {@code runId} defaults to the calling run, which the
     * workflow engine supplies for {@code sdk:} nodes.
     */
    private SdkCallResult setWorkflowRunTitle(Map<String, Object> params) {
        Long runId = toLong(params.get("runId"));
        if (runId == null) return SdkCallResult.error("runId is required");
        try {
            String title = workflowExecutionService.get().setRunTitle(runId, toString(params.get("title")));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("runId", runId);
            result.put("title", title);
            return SdkCallResult.ok(title == null ? "Title of run " + runId + " cleared"
                    : "Title of run " + runId + " set to '" + title + "'", result);
        } catch (WebApplicationException e) {
            Object entity = e.getResponse().getEntity();
            String message = entity instanceof Map<?, ?> m && m.get("message") != null
                    ? String.valueOf(m.get("message")) : e.getMessage();
            return SdkCallResult.error(message);
        }
    }

    private SdkCallResult listSubjectRuns(Map<String, Object> params) {
        Long subjectId = toLong(params.get("subjectId"));
        if (subjectId == null) return SdkCallResult.error("subjectId is required");
        String status = toString(params.get("status"));
        List<WorkflowRunEntity> runs = status != null && !status.isBlank()
                ? WorkflowRunEntity.<WorkflowRunEntity>find("subjectId = ?1 and status = ?2",
                        Sort.descending("startedOn", "id"), subjectId, status).page(0, 50).list()
                : WorkflowRunEntity.<WorkflowRunEntity>find("subjectId", Sort.descending("startedOn", "id"),
                        subjectId).page(0, 50).list();
        List<Map<String, Object>> items = runs.stream().map(run -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", run.id);
            item.put("title", run.title);
            item.put("status", run.status);
            item.put("definitionId", run.definitionId);
            item.put("startedOn", run.startedOn.toString());
            item.put("completedOn", run.completedOn != null ? run.completedOn.toString() : null);
            return item;
        }).toList();
        return SdkCallResult.ok("Found " + items.size() + " workflow runs",
                Map.of("runs", items, "count", items.size()));
    }

    private Map<String, Object> readJsonObject(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String toString(Object value) {
        return value != null ? value.toString() : null;
    }
}
