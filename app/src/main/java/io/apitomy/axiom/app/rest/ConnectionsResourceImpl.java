package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.ConnectionsResource;
import io.apitomy.axiom.api.beans.Connection;
import io.apitomy.axiom.api.beans.ConnectionSearchResults;
import io.apitomy.axiom.api.beans.ConnectionStatus;
import io.apitomy.axiom.api.beans.Configuration;
import io.apitomy.axiom.api.beans.NewConnection;
import io.apitomy.axiom.core.entities.EventSourceConnectionEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import org.jboss.logging.Logger;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Implementation of the Connections REST API.
 */
@ApplicationScoped
@RunOnVirtualThread
public class ConnectionsResourceImpl implements ConnectionsResource {

    private static final Logger LOG = Logger.getLogger(ConnectionsResourceImpl.class);

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9-]+$");
    private static final int SLUG_MAX_LENGTH = 63;

    @Inject
    ObjectMapper objectMapper;

    /**
     * {@inheritDoc}
     */
    @Override
    public ConnectionSearchResults listConnections(BigInteger page, BigInteger limit,
                                                   String filterName, String filterType) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterName != null && !filterName.isBlank()) {
            hql.append(" and (lower(name) like :name or lower(description) like :name)");
            params.put("name", "%" + filterName.toLowerCase() + "%");
        }

        if (filterType != null && !filterType.isBlank()) {
            hql.append(" and sourceType = :sourceType");
            params.put("sourceType", filterType.toLowerCase());
        }

        long totalCount = EventSourceConnectionEntity.count(hql.toString(), params);
        List<Connection> items = EventSourceConnectionEntity.<EventSourceConnectionEntity>find(
                        hql.toString(), Sort.ascending("name"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list().stream().map(this::toBean).toList();

        ConnectionSearchResults results = new ConnectionSearchResults();
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
    @Transactional
    public Connection createConnection(NewConnection data) {
        String slug = data.getId();
        if (slug == null || slug.isEmpty() || slug.length() > SLUG_MAX_LENGTH
                || !SLUG_PATTERN.matcher(slug).matches()) {
            throw new WebApplicationException(
                    "Invalid connection id: must be 1-63 lowercase letters, numbers, or dashes", 400);
        }

        EventSourceConnectionEntity existing = EventSourceConnectionEntity.findById(slug);
        if (existing != null) {
            throw new WebApplicationException("Connection already exists: " + slug, 409);
        }

        EventSourceConnectionEntity entity = new EventSourceConnectionEntity();
        entity.id = slug;
        Instant now = Instant.now();
        entity.createdOn = now;
        entity.modifiedOn = now;
        applyFields(entity, data);
        entity.persist();
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Connection getConnection(String connectionId) {
        return toBean(findOrThrow(connectionId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Connection updateConnection(String connectionId, NewConnection data) {
        EventSourceConnectionEntity entity = findOrThrow(connectionId);
        entity.modifiedOn = Instant.now();
        applyFields(entity, data);
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteConnection(String connectionId) {
        EventSourceConnectionEntity entity = findOrThrow(connectionId);
        entity.delete();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ConnectionStatus getConnectionStatus(String connectionId) {
        EventSourceConnectionEntity entity = findOrThrow(connectionId);

        long totalEvents = StreamEventEntity.count("connectionId", connectionId);

        ConnectionStatus status = new ConnectionStatus();
        status.setConnectionId(connectionId);
        status.setEnabled(entity.enabled);
        status.setTotalEventsProduced(totalEvents);
        if (entity.lastPolledAt != null) {
            status.setLastPolledAt(Date.from(entity.lastPolledAt));
        }
        // Error tracking will be added later
        status.setLastError(null);
        status.setLastErrorAt(null);
        return status;
    }

    /**
     * Applies field values from the API bean to the entity.
     *
     * @param entity the entity to update
     * @param data the API bean with new values
     */
    private void applyFields(EventSourceConnectionEntity entity, NewConnection data) {
        entity.name = data.getName();
        entity.description = data.getDescription();
        entity.sourceType = data.getSourceType() != null ? data.getSourceType().value() : "github";
        entity.enabled = data.getEnabled() != null ? data.getEnabled() : false;
        entity.baseUrl = data.getBaseUrl();
        entity.secretName = data.getSecretName();
        entity.pollInterval = data.getPollInterval();
        if (data.getConfiguration() != null) {
            try {
                entity.configuration = objectMapper.writeValueAsString(data.getConfiguration());
            } catch (Exception e) {
                entity.configuration = "{}";
            }
        } else {
            entity.configuration = "{}";
        }
    }

    /**
     * Finds a connection by ID or throws a 404 WebApplicationException.
     *
     * @param id the connection ID (slug)
     * @return the entity
     */
    private EventSourceConnectionEntity findOrThrow(String id) {
        EventSourceConnectionEntity entity = EventSourceConnectionEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Connection not found: " + id, 404);
        }
        return entity;
    }

    /**
     * Converts an entity to an API bean.
     *
     * @param entity the entity to convert
     * @return the API bean
     */
    private Connection toBean(EventSourceConnectionEntity entity) {
        Connection bean = new Connection();
        bean.setId(entity.id);
        bean.setName(entity.name);
        bean.setDescription(entity.description);
        bean.setSourceType(Connection.SourceType.fromValue(entity.sourceType));
        bean.setEnabled(entity.enabled);
        bean.setBaseUrl(entity.baseUrl);
        bean.setSecretName(entity.secretName);
        bean.setPollInterval(entity.pollInterval);
        if (entity.configuration != null) {
            try {
                bean.setConfiguration(objectMapper.readValue(entity.configuration,
                        Configuration.class));
            } catch (Exception e) {
                // ignore parse errors
            }
        }
        if (entity.createdOn != null) {
            bean.setCreatedOn(Date.from(entity.createdOn));
        }
        if (entity.modifiedOn != null) {
            bean.setModifiedOn(Date.from(entity.modifiedOn));
        }
        return bean;
    }
}
