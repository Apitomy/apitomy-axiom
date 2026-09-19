package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.SubscriptionsResource;
import io.apitomy.axiom.api.beans.EventSourceFilters;
import io.apitomy.axiom.api.beans.NewSubscription;
import io.apitomy.axiom.api.beans.Subscription;
import io.apitomy.axiom.api.beans.SubscriptionSearchResults;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
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

/**
 * Implementation of the Subscriptions REST API.
 */
@ApplicationScoped
@RunOnVirtualThread
public class SubscriptionsResourceImpl implements SubscriptionsResource {

    private static final Logger LOG = Logger.getLogger(SubscriptionsResourceImpl.class);

    @Inject
    ObjectMapper objectMapper;

    /**
     * {@inheritDoc}
     */
    @Override
    public SubscriptionSearchResults listSubscriptions(BigInteger page, BigInteger limit,
                                                       String filterName) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterName != null && !filterName.isBlank()) {
            hql.append(" and (lower(name) like :name or lower(description) like :name)");
            params.put("name", "%" + filterName.toLowerCase() + "%");
        }

        long totalCount = EventSubscriptionEntity.count(hql.toString(), params);
        List<Subscription> items = EventSubscriptionEntity.<EventSubscriptionEntity>find(
                        hql.toString(), Sort.ascending("name"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list().stream().map(this::toBean).toList();

        SubscriptionSearchResults results = new SubscriptionSearchResults();
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
    public Subscription createSubscription(NewSubscription data) {
        EventSubscriptionEntity entity = new EventSubscriptionEntity();
        applyFields(entity, data);
        Instant now = Instant.now();
        entity.createdOn = now;
        entity.modifiedOn = now;
        entity.persist();
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Subscription getSubscription(long subscriptionId) {
        return toBean(findOrThrow(subscriptionId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Subscription updateSubscription(long subscriptionId, NewSubscription data) {
        EventSubscriptionEntity entity = findOrThrow(subscriptionId);
        applyFields(entity, data);
        entity.modifiedOn = Instant.now();
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteSubscription(long subscriptionId) {
        EventSubscriptionEntity entity = findOrThrow(subscriptionId);
        entity.delete();
    }

    /**
     * Applies field values from the API bean to the entity.
     *
     * @param entity the entity to update
     * @param data the API bean with new values
     */
    private void applyFields(EventSubscriptionEntity entity, NewSubscription data) {
        entity.name = data.getName();
        entity.description = data.getDescription();
        entity.enabled = data.getEnabled() != null ? data.getEnabled() : false;
        if (data.getFilters() != null) {
            try {
                entity.filters = objectMapper.writeValueAsString(data.getFilters());
            } catch (Exception e) {
                entity.filters = null;
            }
        }
        entity.labels.clear();
        if (data.getLabels() != null) {
            entity.labels.addAll(data.getLabels());
        }
    }

    /**
     * Finds a subscription by ID or throws a 404 WebApplicationException.
     *
     * @param id the subscription ID
     * @return the entity
     */
    private EventSubscriptionEntity findOrThrow(long id) {
        EventSubscriptionEntity entity = EventSubscriptionEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Subscription not found: " + id, 404);
        }
        return entity;
    }

    /**
     * Converts an entity to an API bean.
     *
     * @param entity the entity to convert
     * @return the API bean
     */
    private Subscription toBean(EventSubscriptionEntity entity) {
        Subscription bean = new Subscription();
        bean.setId(entity.id);
        bean.setName(entity.name);
        bean.setDescription(entity.description);
        bean.setEnabled(entity.enabled);
        bean.setLabels(entity.labels);
        if (entity.filters != null) {
            try {
                bean.setFilters(objectMapper.readValue(entity.filters, EventSourceFilters.class));
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
