package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.lifecycle.ProjectStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
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
            case "axiom_close_project" -> closeProject(params);
            case "axiom_reopen_project" -> reopenProject(params);
            case "axiom_add_project_label" -> addProjectLabel(params);
            case "axiom_remove_project_label" -> removeProjectLabel(params);
            case "axiom_update_project" -> updateProject(params);
            case "axiom_add_thread_entry" -> addThreadEntry(params);
            case "axiom_get_project" -> getProject(params);
            case "axiom_create_task" -> createTask(params);
            case "axiom_fire_event" -> fireEvent(params);
            case "axiom_list_projects" -> listProjects(params);
            case "axiom_create_project" -> createProject(params);
            case "axiom_update_project_body" -> updateProjectBody(params);
            default -> SdkCallResult.error("Unknown SDK function: " + functionName);
        };
    }

    /**
     * Returns true if the given function name is a recognized SDK function.
     */
    public boolean isValidFunction(String functionName) {
        return switch (functionName) {
            case "axiom_close_project", "axiom_reopen_project",
                 "axiom_add_project_label", "axiom_remove_project_label",
                 "axiom_update_project", "axiom_add_thread_entry",
                 "axiom_get_project", "axiom_create_task",
                 "axiom_fire_event", "axiom_list_projects",
                 "axiom_create_project", "axiom_update_project_body" -> true;
            default -> false;
        };
    }

    private SdkCallResult closeProject(Map<String, Object> params) {
        Long projectId = toLong(params.get("projectId"));
        if (projectId == null) return SdkCallResult.error("projectId is required");
        ProjectEntity project = ProjectEntity.findById(projectId);
        if (project == null) return SdkCallResult.error("Project not found: " + projectId);
        project.status = ProjectStatus.Completed.name();
        project.updatedOn = Instant.now();
        return SdkCallResult.ok("Project " + projectId + " closed");
    }

    private SdkCallResult reopenProject(Map<String, Object> params) {
        Long projectId = toLong(params.get("projectId"));
        if (projectId == null) return SdkCallResult.error("projectId is required");
        ProjectEntity project = ProjectEntity.findById(projectId);
        if (project == null) return SdkCallResult.error("Project not found: " + projectId);
        project.status = ProjectStatus.InProgress.name();
        project.updatedOn = Instant.now();
        return SdkCallResult.ok("Project " + projectId + " reopened");
    }

    private SdkCallResult addProjectLabel(Map<String, Object> params) {
        Long projectId = toLong(params.get("projectId"));
        String label = toString(params.get("label"));
        if (projectId == null) return SdkCallResult.error("projectId is required");
        if (label == null) return SdkCallResult.error("label is required");
        ProjectEntity project = ProjectEntity.findById(projectId);
        if (project == null) return SdkCallResult.error("Project not found: " + projectId);
        if (!project.labels.contains(label)) {
            project.labels.add(label);
            project.updatedOn = Instant.now();
        }
        return SdkCallResult.ok("Label '" + label + "' added to project " + projectId);
    }

    private SdkCallResult removeProjectLabel(Map<String, Object> params) {
        Long projectId = toLong(params.get("projectId"));
        String label = toString(params.get("label"));
        if (projectId == null) return SdkCallResult.error("projectId is required");
        if (label == null) return SdkCallResult.error("label is required");
        ProjectEntity project = ProjectEntity.findById(projectId);
        if (project == null) return SdkCallResult.error("Project not found: " + projectId);
        project.labels.remove(label);
        project.updatedOn = Instant.now();
        return SdkCallResult.ok("Label '" + label + "' removed from project " + projectId);
    }

    private SdkCallResult updateProject(Map<String, Object> params) {
        Long projectId = toLong(params.get("projectId"));
        if (projectId == null) return SdkCallResult.error("projectId is required");
        ProjectEntity project = ProjectEntity.findById(projectId);
        if (project == null) return SdkCallResult.error("Project not found: " + projectId);
        String name = toString(params.get("name"));
        if (name != null) project.name = name;
        String status = toString(params.get("status"));
        if (status != null) project.status = status;
        project.updatedOn = Instant.now();
        return SdkCallResult.ok("Project " + projectId + " updated");
    }

    private SdkCallResult addThreadEntry(Map<String, Object> params) {
        Long projectId = toLong(params.get("projectId"));
        String content = toString(params.get("content"));
        if (projectId == null) return SdkCallResult.error("projectId is required");
        if (content == null) return SdkCallResult.error("content is required");
        ThreadEntryEntity entry = new ThreadEntryEntity();
        entry.projectId = projectId;
        entry.authorType = toString(params.getOrDefault("authorType", "system"));
        entry.entryType = toString(params.getOrDefault("entryType", "message"));
        entry.content = content;
        entry.createdOn = Instant.now();
        entry.persist();
        return SdkCallResult.ok("Thread entry added to project " + projectId);
    }

    private SdkCallResult getProject(Map<String, Object> params) {
        Long projectId = toLong(params.get("projectId"));
        if (projectId == null) return SdkCallResult.error("projectId is required");
        ProjectEntity project = ProjectEntity.findById(projectId);
        if (project == null) return SdkCallResult.error("Project not found: " + projectId);
        return SdkCallResult.ok("Project found", Map.of(
                "id", project.id,
                "name", project.name != null ? project.name : "",
                "status", project.status != null ? project.status : "",
                "ref", project.ref != null ? project.ref : "",
                "type", project.type != null ? project.type : ""
        ));
    }

    private SdkCallResult createTask(Map<String, Object> params) {
        Long projectId = toLong(params.get("projectId"));
        String actionType = toString(params.get("actionType"));
        if (projectId == null) return SdkCallResult.error("projectId is required");
        if (actionType == null) return SdkCallResult.error("actionType is required");
        TaskEntity task = new TaskEntity();
        task.projectId = projectId;
        task.actionType = actionType;
        task.createdBy = "workflow";
        task.status = "Pending";
        task.input = toString(params.get("input"));
        task.createdOn = Instant.now();
        task.persist();
        return SdkCallResult.ok("Task " + task.id + " created",
                Map.of("taskId", task.id));
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
        event.connectionId = "sdk";
        event.type = eventType;
        event.ref = toString(params.getOrDefault("ref", ""));
        event.timestamp = Instant.now();
        event.actor = "{\"login\":\"workflow\"}";
        event.payload = payload;
        event.createdOn = Instant.now();
        event.persist();

        return SdkCallResult.ok("Event fired: " + eventType,
                Map.of("eventId", event.id.toString()));
    }

    private SdkCallResult listProjects(Map<String, Object> params) {
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

        var projects = ProjectEntity.<ProjectEntity>find(hql.toString(),
                io.quarkus.panache.common.Sort.descending("createdOn"), queryParams)
                .page(0, 50).list();

        var items = projects.stream().map(p -> Map.<String, Object>of(
                "id", p.id,
                "name", p.name != null ? p.name : "",
                "status", p.status != null ? p.status : "",
                "ref", p.ref != null ? p.ref : "",
                "type", p.type != null ? p.type : ""
        )).toList();

        return SdkCallResult.ok("Found " + items.size() + " projects",
                Map.of("projects", items, "count", items.size()));
    }

    private SdkCallResult createProject(Map<String, Object> params) {
        String name = toString(params.get("name"));
        String type = toString(params.get("type"));
        String ref = toString(params.get("ref"));
        if (name == null) return SdkCallResult.error("name is required");
        if (type == null) return SdkCallResult.error("type is required");
        if (ref == null) return SdkCallResult.error("ref is required");

        ProjectEntity project = new ProjectEntity();
        project.name = name;
        project.type = type;
        project.ref = ref;
        project.refSource = toString(params.get("refSource"));
        project.repository = toString(params.get("repository"));
        project.body = toString(params.get("body"));
        project.status = ProjectStatus.Created.name();
        project.createdOn = Instant.now();
        project.updatedOn = Instant.now();
        project.persist();

        return SdkCallResult.ok("Project " + project.id + " created",
                Map.of("projectId", project.id));
    }

    private SdkCallResult updateProjectBody(Map<String, Object> params) {
        Long projectId = toLong(params.get("projectId"));
        String body = toString(params.get("body"));
        if (projectId == null) return SdkCallResult.error("projectId is required");
        if (body == null) return SdkCallResult.error("body is required");
        ProjectEntity project = ProjectEntity.findById(projectId);
        if (project == null) return SdkCallResult.error("Project not found: " + projectId);
        project.body = body;
        project.updatedOn = Instant.now();
        return SdkCallResult.ok("Project " + projectId + " body updated");
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
