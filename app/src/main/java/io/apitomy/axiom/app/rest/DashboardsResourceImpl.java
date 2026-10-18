package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.DashboardsResource;
import io.apitomy.axiom.app.DashboardValidator;
import io.apitomy.axiom.api.beans.Dashboard;
import io.apitomy.axiom.api.beans.DashboardTab;
import io.apitomy.axiom.api.beans.NewDashboard;
import io.apitomy.axiom.core.entities.DashboardEntity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Implementation of the Dashboards REST API. Provides CRUD operations
 * for user-created custom dashboards with configurable widget layouts.
 */
@ApplicationScoped
@RunOnVirtualThread
public class DashboardsResourceImpl implements DashboardsResource {

    private static final Logger LOG = Logger.getLogger(DashboardsResourceImpl.class);

    private static final TypeReference<List<DashboardTab>> TAB_LIST_TYPE =
            new TypeReference<>() {};

    @Inject
    ObjectMapper objectMapper;

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Dashboard> listDashboards() {
        return DashboardEntity.<DashboardEntity>listAll()
                .stream()
                .sorted(java.util.Comparator.comparing(e -> e.name))
                .map(this::toBean)
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Dashboard createDashboard(NewDashboard data) {
        DashboardEntity entity = new DashboardEntity();
        applyFields(entity, data);
        entity.createdOn = Instant.now();
        entity.updatedOn = Instant.now();
        entity.persist();
        if (entity.isDefault) {
            clearOtherDefaults(entity.id);
        }
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Dashboard getDashboard(long dashboardId) {
        return toBean(findOrThrow(dashboardId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Dashboard updateDashboard(long dashboardId, NewDashboard data) {
        DashboardEntity entity = findOrThrow(dashboardId);
        applyFields(entity, data);
        entity.updatedOn = Instant.now();
        if (entity.isDefault) {
            clearOtherDefaults(entity.id);
        }
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteDashboard(long dashboardId) {
        DashboardEntity entity = findOrThrow(dashboardId);
        entity.delete();
    }

    private void clearOtherDefaults(Long currentId) {
        DashboardEntity.update("isDefault = false WHERE id != ?1 AND isDefault = true", currentId);
    }

    /**
     * Applies common fields from the API bean to the entity.
     *
     * @param entity the entity to update
     * @param data the API bean with new values
     */
    private void applyFields(DashboardEntity entity, NewDashboard data) {
        if (data == null) {
            throw badRequest("Dashboard body is required");
        }
        List<String> problems = DashboardValidator.validate(data.getName(), data.getTabs());
        if (!problems.isEmpty()) {
            throw badRequest("Invalid dashboard: " + String.join("; ", problems));
        }
        entity.name = data.getName().trim();
        entity.description = data.getDescription();
        entity.isDefault = data.getIsDefault() != null ? data.getIsDefault() : false;
        entity.tabs = serializeTabs(data.getTabs());
        entity.labels.clear();
        if (data.getLabels() != null) {
            entity.labels.addAll(data.getLabels());
        }
    }

    private static WebApplicationException badRequest(String message) {
        return new WebApplicationException(Response.status(400)
                .type(MediaType.TEXT_PLAIN_TYPE)
                .entity(message)
                .build());
    }

    private DashboardEntity findOrThrow(long id) {
        DashboardEntity entity = DashboardEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Dashboard not found: " + id, 404);
        }
        return entity;
    }

    private Dashboard toBean(DashboardEntity entity) {
        Dashboard bean = new Dashboard();
        bean.setId(entity.id);
        bean.setName(entity.name);
        bean.setDescription(entity.description);
        bean.setIsDefault(entity.isDefault);
        bean.setLabels(entity.labels);
        bean.setTabs(deserializeTabs(entity.tabs));
        bean.setCreatedOn(Date.from(entity.createdOn));
        bean.setUpdatedOn(Date.from(entity.updatedOn));
        return bean;
    }

    private String serializeTabs(List<DashboardTab> tabs) {
        if (tabs == null || tabs.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(tabs);
        } catch (JsonProcessingException e) {
            throw new WebApplicationException("Failed to serialize tabs", 500);
        }
    }

    /**
     * Parses stored tabs JSON. Malformed JSON (or JSON that is not a list of tabs) never fails the request:
     * it is logged and treated as an empty tab list.
     */
    private List<DashboardTab> deserializeTabs(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<DashboardTab> tabs = objectMapper.readValue(json, TAB_LIST_TYPE);
            return tabs != null ? tabs : new ArrayList<>();
        } catch (JsonProcessingException e) {
            LOG.warnf("Ignoring malformed stored tabs JSON for a dashboard: %s", e.getMessage());
            return new ArrayList<>();
        }
    }
}
