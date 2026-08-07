package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.EventResource;
import io.apitomy.axiom.api.beans.EventSource;
import io.apitomy.axiom.api.beans.EventSourceLog;
import io.apitomy.axiom.api.beans.EventSourceLogSearchResults;
import io.apitomy.axiom.api.beans.FilterDryRunRequest;
import io.apitomy.axiom.api.beans.FilterDryRunResponse;
import io.apitomy.axiom.api.beans.FilterDryRunResult;
import io.apitomy.axiom.api.beans.NewEventSource;
import io.apitomy.axiom.core.entities.EventSourceEntity;
import io.apitomy.axiom.core.entities.EventSourceLogEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.filters.EventFilterEvaluator;
import io.apitomy.axiom.core.filters.EventSourceFilterRule;
import io.apitomy.axiom.core.filters.EventSourceFilters;
import io.apitomy.axiom.core.filters.FilterResult;
import io.apitomy.axiom.core.services.EncryptionService;
import io.apitomy.axiom.events.core.DryRunEvent;
import io.apitomy.axiom.events.github.GitHubDryRunService;
import io.apitomy.axiom.events.jira.JiraDryRunService;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import org.jboss.logging.Logger;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the Event Sources REST API.
 */
@ApplicationScoped
@RunOnVirtualThread
public class EventSourcesResourceImpl implements EventResource {

    private static final Logger LOG = Logger.getLogger(EventSourcesResourceImpl.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    GitHubDryRunService githubDryRunService;

    @Inject
    JiraDryRunService jiraDryRunService;

    @Inject
    EventFilterEvaluator filterEvaluator;

    @Inject
    EncryptionService encryptionService;

    /**
     * {@inheritDoc}
     */
    @Override
    public List<EventSource> listEventSources() {
        return EventSourceEntity.<EventSourceEntity>listAll()
                .stream()
                .sorted(Comparator.comparing(e -> e.name))
                .map(this::toBean)
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public EventSource createEventSource(NewEventSource data) {
        EventSourceEntity entity = new EventSourceEntity();
        applyFields(entity, data);
        if (entity.filters == null) {
            try {
                entity.filters = objectMapper.writeValueAsString(defaultFilters());
            } catch (Exception e) {
                entity.filters = null;
            }
        }
        entity.persist();
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventSource getEventSource(long eventSourceId) {
        return toBean(findOrThrow(eventSourceId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public EventSource updateEventSource(long eventSourceId, NewEventSource data) {
        EventSourceEntity entity = findOrThrow(eventSourceId);
        applyFields(entity, data);
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteEventSource(long eventSourceId) {
        EventSourceEntity entity = findOrThrow(eventSourceId);
        entity.delete();
    }

    /**
     * Creates the default filter configuration for new event sources.
     *
     * @return the default filters that skip bot activity and slash commands
     */
    private io.apitomy.axiom.api.beans.EventSourceFilters defaultFilters() {
        io.apitomy.axiom.api.beans.EventSourceFilters filters = new io.apitomy.axiom.api.beans.EventSourceFilters();
        filters.setInclude(List.of());

        io.apitomy.axiom.api.beans.EventSourceFilterRule botRule = new io.apitomy.axiom.api.beans.EventSourceFilterRule();
        botRule.setType(io.apitomy.axiom.api.beans.EventSourceFilterRule.Type.payload);
        botRule.setPointer("/user/login");
        botRule.setPattern("*[bot]");

        io.apitomy.axiom.api.beans.EventSourceFilterRule commentBotRule = new io.apitomy.axiom.api.beans.EventSourceFilterRule();
        commentBotRule.setType(io.apitomy.axiom.api.beans.EventSourceFilterRule.Type.payload);
        commentBotRule.setPointer("/comment/user/login");
        commentBotRule.setPattern("*[bot]");

        io.apitomy.axiom.api.beans.EventSourceFilterRule slashRule = new io.apitomy.axiom.api.beans.EventSourceFilterRule();
        slashRule.setType(io.apitomy.axiom.api.beans.EventSourceFilterRule.Type.payload);
        slashRule.setPointer("/comment/body");
        slashRule.setPattern("/*");

        filters.setExclude(List.of(botRule, commentBotRule, slashRule));
        return filters;
    }

    /**
     * Applies field values from the API bean to the entity.
     *
     * @param entity the entity to update
     * @param data the API bean with new values
     */
    private void applyFields(EventSourceEntity entity, NewEventSource data) {
        entity.name = data.getName();
        entity.description = data.getDescription();
        entity.sourceType = data.getSourceType() != null ? data.getSourceType().value() : "github";
        entity.enabled = data.getEnabled() != null ? data.getEnabled() : false;
        entity.pollInterval = data.getPollInterval();
        entity.secretName = data.getSecretName();
        if (data.getConfiguration() != null) {
            try {
                entity.configuration = objectMapper.writeValueAsString(data.getConfiguration());
            } catch (Exception e) {
                entity.configuration = "{}";
            }
        } else {
            entity.configuration = "{}";
        }
        entity.labels.clear();
        if (data.getLabels() != null) {
            entity.labels.addAll(data.getLabels());
        }
        if (data.getFilters() != null) {
            try {
                entity.filters = objectMapper.writeValueAsString(data.getFilters());
            } catch (Exception e) {
                entity.filters = null;
            }
        } else {
            entity.filters = null;
        }
    }

    /**
     * Finds an event source by ID or throws a 404 WebApplicationException.
     *
     * @param id the event source ID
     * @return the entity
     */
    private EventSourceEntity findOrThrow(long id) {
        EventSourceEntity entity = EventSourceEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Event source not found: " + id, 404);
        }
        return entity;
    }

    /**
     * Converts an entity to an API bean.
     *
     * @param entity the entity to convert
     * @return the API bean
     */
    @SuppressWarnings("unchecked")
    private EventSource toBean(EventSourceEntity entity) {
        EventSource bean = new EventSource();
        bean.setId(entity.id);
        bean.setName(entity.name);
        bean.setDescription(entity.description);
        bean.setSourceType(EventSource.SourceType.fromValue(entity.sourceType));
        bean.setEnabled(entity.enabled);
        bean.setPollInterval(entity.pollInterval);
        bean.setSecretName(entity.secretName);
        if (entity.configuration != null) {
            try {
                bean.setConfiguration(objectMapper.readValue(entity.configuration,
                        io.apitomy.axiom.api.beans.Configuration.class));
            } catch (Exception e) {
                // ignore parse errors
            }
        }
        bean.setLabels(entity.labels);
        if (entity.filters != null) {
            try {
                bean.setFilters(objectMapper.readValue(entity.filters,
                        io.apitomy.axiom.api.beans.EventSourceFilters.class));
            } catch (Exception e) {
                // ignore parse errors
            }
        }
        return bean;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventSourceLogSearchResults listEventSourceLogs(BigInteger eventSourceId,
                                                            BigInteger page, BigInteger limit) {
        findOrThrow(eventSourceId.longValue());
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        long totalCount = EventSourceLogEntity.count("eventSourceId", eventSourceId.longValue());
        List<EventSourceLog> items = EventSourceLogEntity.<EventSourceLogEntity>find(
                        "eventSourceId", Sort.descending("createdOn"),
                        eventSourceId.longValue())
                .page(Page.of(pageNum - 1, pageSize))
                .list().stream()
                .map(this::toLogBean)
                .toList();

        EventSourceLogSearchResults results = new EventSourceLogSearchResults();
        results.setItems(items);
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    private EventSourceLog toLogBean(EventSourceLogEntity entity) {
        EventSourceLog log = new EventSourceLog();
        log.setId(entity.id);
        log.setEventSourceId(entity.eventSourceId);
        log.setStatus(entity.status);
        log.setMessage(entity.message);
        log.setDetail(entity.detail);
        log.setEventsIngested(entity.eventsIngested);
        log.setCreatedOn(Date.from(entity.createdOn));
        return log;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FilterDryRunResponse dryRunFilters(FilterDryRunRequest request) {
        // Convert API filter beans to core filter model
        EventSourceFilters coreFilters = toCoreFilters(request.getFilters());

        // Fetch recent events from the source
        List<DryRunEvent> events = fetchDryRunEvents(request);

        // Evaluate filters against each event
        List<FilterDryRunResult> results = new ArrayList<>();
        int allowed = 0;
        int blocked = 0;

        for (DryRunEvent event : events) {
            FilterResult filterResult = filterEvaluator.evaluate(
                    coreFilters, event.eventType(), event.payload());
            FilterDryRunResult result = new FilterDryRunResult();
            result.setEventType(event.eventType());
            result.setIssueRef(event.issueRef());
            result.setSummary(event.summary());
            result.setAllowed(filterResult.allowed());
            result.setMatchedRule(filterResult.matchedRule());
            results.add(result);
            if (filterResult.allowed()) allowed++;
            else blocked++;
        }

        FilterDryRunResponse response = new FilterDryRunResponse();
        response.setResults(results);
        response.setTotalEvaluated(events.size());
        response.setTotalAllowed(allowed);
        response.setTotalBlocked(blocked);
        return response;
    }

    /**
     * Fetches recent events from the configured event source for dry-run evaluation.
     *
     * @param request the dry-run request containing source configuration
     * @return list of classified events
     */
    private List<DryRunEvent> fetchDryRunEvents(FilterDryRunRequest request) {
        String sourceType = request.getSourceType().value();
        Map<String, Object> config = request.getConfiguration();
        String token = resolveSecretValue(request.getSecretName());

        if (token == null) {
            throw new WebApplicationException("Secret not found or could not be decrypted", 401);
        }

        if ("github".equals(sourceType)) {
            String owner = String.valueOf(config.get("owner"));
            String name = String.valueOf(config.get("name"));
            return githubDryRunService.fetchRecentEvents(owner, name, token);
        } else if ("jira".equals(sourceType)) {
            String baseUrl = String.valueOf(config.get("baseUrl"));
            String project = String.valueOf(config.get("project"));
            return jiraDryRunService.fetchRecentEvents(baseUrl, project, token);
        }
        return List.of();
    }

    /**
     * Converts API filter beans to core filter model.
     *
     * @param apiFilters the API filter configuration
     * @return the core filter model
     */
    private EventSourceFilters toCoreFilters(
            io.apitomy.axiom.api.beans.EventSourceFilters apiFilters) {
        if (apiFilters == null) return EventSourceFilters.allowAll();
        List<EventSourceFilterRule> include = apiFilters.getInclude() != null
                ? apiFilters.getInclude().stream()
                    .map(r -> new EventSourceFilterRule(
                            r.getType().value(), r.getPointer(), r.getPattern()))
                    .toList()
                : List.of();
        List<EventSourceFilterRule> exclude = apiFilters.getExclude() != null
                ? apiFilters.getExclude().stream()
                    .map(r -> new EventSourceFilterRule(
                            r.getType().value(), r.getPointer(), r.getPattern()))
                    .toList()
                : List.of();
        return new EventSourceFilters(include, exclude);
    }

    /**
     * Resolves a secret value by name using the same logic as the pollers.
     *
     * @param secretName the name of the secret to resolve
     * @return the decrypted secret value, or null if not found
     */
    private String resolveSecretValue(String secretName) {
        if (secretName != null && !secretName.isBlank()) {
            SecretEntity secret = SecretEntity.find("name", secretName).firstResult();
            if (secret != null) {
                try {
                    return encryptionService.decrypt(secret.encryptedValue);
                } catch (Exception e) {
                    LOG.warnf("Failed to decrypt secret '%s'", secretName);
                }
            }
        }
        return null;
    }
}
