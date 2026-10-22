package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.SchemasResource;
import io.apitomy.axiom.api.beans.EventSchemaDocument;
import io.apitomy.axiom.api.beans.EventSchemaSummary;
import io.apitomy.axiom.core.events.schema.EventSchemaRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

import java.util.List;

/**
 * Implementation of the Event Schemas REST API: JSON Schemas of normalized event types.
 */
@ApplicationScoped
public class EventSchemasResourceImpl implements SchemasResource {

    @Inject
    EventSchemaRegistry eventSchemaRegistry;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Lists event types that have a JSON Schema.
     *
     * @return summaries sorted by type
     */
    @Override
    public List<EventSchemaSummary> listEventSchemas() {
        return eventSchemaRegistry.eventTypes().stream().map(s -> {
            EventSchemaSummary bean = new EventSchemaSummary();
            bean.setType(s.type());
            bean.setDescription(s.description());
            return bean;
        }).toList();
    }

    /**
     * Returns the assembled JSON Schema of an event type.
     *
     * @param eventType the event type
     * @return the schema
     */
    @Override
    public EventSchemaDocument getEventSchema(String eventType) {
        return eventSchemaRegistry.schemaFor(eventType)
                .map(schema -> objectMapper.convertValue(schema, EventSchemaDocument.class))
                .orElseThrow(() -> new WebApplicationException("Unknown event type: " + eventType, 404));
    }
}
