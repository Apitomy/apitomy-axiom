package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.beans.Task;
import io.apitomy.axiom.api.beans.TaskSearchResults;
import io.apitomy.axiom.core.entities.AgentCapabilityEntity;
import io.apitomy.axiom.core.entities.AgentEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the Agents REST API. Manages AI agent slots
 * in the agent pool and provides access to their assigned tasks.
 */
@Path("/api/v1/agents")
@ApplicationScoped
@RunOnVirtualThread
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AgentsResourceImpl {

    /**
     * Lists all configured agents.
     *
     * @return list of agent representations
     */
    @GET
    public List<Map<String, Object>> listAgents() {
        return AgentEntity.<AgentEntity>listAll()
                .stream()
                .map(this::toBean)
                .toList();
    }

    /**
     * Creates a new agent slot.
     *
     * @param data the agent data
     * @return the created agent representation
     */
    @POST
    @Transactional
    public Map<String, Object> createAgent(Map<String, Object> data) {
        AgentEntity entity = new AgentEntity();
        applyFields(entity, data);
        entity.persist();

        // Set capabilities after persist to ensure entity.id is available
        if (data.containsKey("capabilities")) {
            @SuppressWarnings("unchecked")
            List<String> caps = (List<String>) data.get("capabilities");
            AgentCapabilityEntity.setCapabilities(entity.id, caps != null ? caps : List.of());
        }

        return toBean(entity);
    }

    /**
     * Returns a single agent by ID.
     *
     * @param agentId the agent ID
     * @return the agent representation
     */
    @GET
    @Path("/{agentId}")
    public Map<String, Object> getAgent(@PathParam("agentId") long agentId) {
        return toBean(findOrThrow(agentId));
    }

    /**
     * Updates an existing agent slot.
     *
     * @param agentId the agent ID
     * @param data the updated agent data
     * @return the updated agent representation
     */
    @PUT
    @Path("/{agentId}")
    @Transactional
    public Map<String, Object> updateAgent(@PathParam("agentId") long agentId,
                                           Map<String, Object> data) {
        AgentEntity entity = findOrThrow(agentId);
        applyFields(entity, data);

        // Set capabilities using separate table
        if (data.containsKey("capabilities")) {
            @SuppressWarnings("unchecked")
            List<String> caps = (List<String>) data.get("capabilities");
            AgentCapabilityEntity.setCapabilities(entity.id, caps != null ? caps : List.of());
        }

        return toBean(entity);
    }

    /**
     * Deletes an agent slot.
     *
     * @param agentId the agent ID
     */
    @DELETE
    @Path("/{agentId}")
    @Transactional
    public void deleteAgent(@PathParam("agentId") long agentId) {
        AgentEntity entity = findOrThrow(agentId);
        entity.delete();
    }

    // -- Agent Tasks ------------------------------------------------------

    /**
     * Lists tasks assigned to the given agent, with optional filtering.
     *
     * @param agentId the agent ID
     * @param page the page number (1-based)
     * @param limit the page size
     * @param filterActionType optional action type filter
     * @param filterStatus optional status filter (comma-separated)
     * @return paginated task search results
     */
    @GET
    @Path("/{agentId}/tasks")
    public TaskSearchResults listAgentTasks(@PathParam("agentId") long agentId,
                                            @QueryParam("page") BigInteger page,
                                            @QueryParam("limit") BigInteger limit,
                                            @QueryParam("filterActionType") String filterActionType,
                                            @QueryParam("filterStatus") String filterStatus) {
        findOrThrow(agentId);

        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("assignedAgent = :agentId");
        Map<String, Object> params = new HashMap<>();
        params.put("agentId", agentId);

        if (filterActionType != null && !filterActionType.isBlank()) {
            hql.append(" and lower(actionType) like :actionType");
            params.put("actionType", "%" + filterActionType.toLowerCase() + "%");
        }
        if (filterStatus != null && !filterStatus.isBlank()) {
            List<String> statuses = Arrays.stream(filterStatus.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            hql.append(" and status in :statuses");
            params.put("statuses", statuses);
        }

        long totalCount = TaskEntity.count(hql.toString(), params);
        List<Task> items = TaskEntity.<TaskEntity>find(hql.toString(),
                        Sort.descending("createdOn"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list()
                .stream()
                .map(this::toTaskBean)
                .toList();

        TaskSearchResults results = new TaskSearchResults();
        results.setItems(items);
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    // -- Helpers ----------------------------------------------------------

    private void applyFields(AgentEntity entity, Map<String, Object> body) {
        if (body.containsKey("name")) {
            entity.name = (String) body.get("name");
        }
        if (body.containsKey("description")) {
            entity.description = (String) body.get("description");
        }
        if (body.containsKey("agentType")) {
            entity.agentType = (String) body.get("agentType");
        }
        if (body.containsKey("enabled")) {
            entity.enabled = Boolean.TRUE.equals(body.get("enabled"));
        }
        if (body.containsKey("configuration")) {
            Object cfg = body.get("configuration");
            entity.configuration = cfg != null ? cfg.toString() : null;
        }
    }

    private AgentEntity findOrThrow(long id) {
        AgentEntity entity = AgentEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Agent not found: " + id, 404);
        }
        return entity;
    }

    private Map<String, Object> toBean(AgentEntity entity) {
        Map<String, Object> agent = new HashMap<>();
        agent.put("id", entity.id);
        agent.put("name", entity.name);
        agent.put("description", entity.description);
        agent.put("agentType", entity.agentType);
        agent.put("enabled", entity.enabled);
        agent.put("capabilities", AgentCapabilityEntity.findCapabilities(entity.id));
        if (entity.configuration != null) {
            agent.put("configuration", entity.configuration);
        }
        return agent;
    }

    private Task toTaskBean(TaskEntity entity) {
        Task task = new Task();
        task.setId(entity.id);
        task.setProjectId(entity.projectId);
        task.setEventId(entity.eventId);
        task.setActionType(entity.actionType);
        task.setCreatedBy(Task.CreatedBy.fromValue(entity.createdBy));
        task.setAssignedAgent(entity.assignedAgent);
        task.setStatus(Task.Status.fromValue(entity.status));
        task.setInput(entity.input);
        task.setOutput(entity.output);
        task.setCreatedOn(Date.from(entity.createdOn));
        if (entity.completedOn != null) {
            task.setCompletedOn(Date.from(entity.completedOn));
        }
        task.setSessionId(entity.sessionId);
        return task;
    }
}
