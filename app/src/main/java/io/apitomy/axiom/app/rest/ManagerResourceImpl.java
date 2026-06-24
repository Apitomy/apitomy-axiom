package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.ManagerResource;
import io.apitomy.axiom.api.beans.ManagerConfig;
import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.ManagerConfigEntity;
import io.apitomy.axiom.manager.ManagerPromptBuilder;
import io.apitomy.axiom.manager.ManagerService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.math.BigInteger;

/**
 * Implementation of the Manager REST API, including configuration
 * and the debug evaluation endpoint.
 */
@ApplicationScoped
@RunOnVirtualThread
public class ManagerResourceImpl implements ManagerResource {

    @Inject
    ManagerService managerService;

    /**
     * {@inheritDoc}
     */
    @Override
    public ManagerConfig getManagerConfig() {
        ManagerConfigEntity entity = ManagerConfigEntity.<ManagerConfigEntity>findAll()
                .firstResult();

        ManagerConfig config = new ManagerConfig();
        if (entity != null) {
            config.setSystemPrompt(entity.systemPrompt);
            config.setPromptTemplate(entity.promptTemplate);
        } else {
            config.setSystemPrompt(ManagerPromptBuilder.DEFAULT_SYSTEM_PROMPT);
            config.setPromptTemplate(ManagerPromptBuilder.DEFAULT_PROMPT_TEMPLATE);
        }
        return config;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public ManagerConfig updateManagerConfig(ManagerConfig data) {
        ManagerConfigEntity entity = ManagerConfigEntity.<ManagerConfigEntity>findAll()
                .firstResult();
        if (entity == null) {
            entity = new ManagerConfigEntity();
        }
        entity.systemPrompt = data.getSystemPrompt();
        entity.promptTemplate = data.getPromptTemplate();
        entity.persist();

        return data;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Response evaluateEvent(BigInteger eventId) {
        EventEntity event = EventEntity.findById(eventId.longValue());
        if (event == null) {
            throw new WebApplicationException("Event not found: " + eventId, 404);
        }
        return Response.ok(managerService.evaluate(event, null)).build();
    }
}
