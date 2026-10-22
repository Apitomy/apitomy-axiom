package io.apitomy.axiom.core.events.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.apitomy.axiom.core.events.model.EventType;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Loads the hand-written event JSON Schemas from {@code schemas/events/} on the classpath, assembles one
 * self-contained schema per {@link EventType}, and validates normalized events against them.
 * Fails at construction (and therefore at startup) if any schema is missing or invalid.
 */
@ApplicationScoped
@Startup
public class EventSchemaRegistry {

    static final String BASE = "schemas/events/";

    private ObjectMapper objectMapper;
    private Map<String, ObjectNode> assembled;
    private Map<String, JsonSchema> compiled;

    /**
     * Loads and compiles every event schema.
     *
     * @param objectMapper mapper used to serialize events before validation
     * @throws IllegalStateException if a schema file is missing, unreadable or invalid
     */
    @Inject
    public EventSchemaRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.assembled = new TreeMap<>();
        this.compiled = new HashMap<>();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        ObjectNode envelope = load("envelope.json");
        JsonNode defs = load("defs.json").path("$defs");
        for (EventType type : EventType.values()) {
            String name = type.value();
            ObjectNode schema = assemble(envelope, defs, load("payloads/" + name + ".json"), name);
            try {
                JsonSchema jsonSchema = factory.getSchema(schema);
                jsonSchema.initializeValidators();
                compiled.put(name, jsonSchema);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Invalid event schema for " + name + ": " + e.getMessage(), e);
            }
            assembled.put(name, schema);
        }
    }

    /**
     * Lists every event type that has a schema.
     *
     * @return summaries sorted by event type
     */
    public List<EventSchemaSummary> eventTypes() {
        List<EventSchemaSummary> result = new ArrayList<>();
        assembled.forEach((type, schema) ->
                result.add(new EventSchemaSummary(type, schema.path("description").asText(""))));
        return result;
    }

    /**
     * Returns the assembled schema for an event type.
     *
     * @param eventType the event type, e.g. {@code issue.closed}
     * @return a deep copy of the schema, or empty if the type is unknown
     */
    public Optional<JsonNode> schemaFor(String eventType) {
        ObjectNode schema = assembled.get(eventType);
        return schema == null ? Optional.empty() : Optional.of(schema.deepCopy());
    }

    /**
     * Validates a normalized event.
     *
     * @param event the event
     * @return validation errors, sorted; empty if the event is valid
     */
    public List<String> validate(NormalizedEvent event) {
        return validate(objectMapper.valueToTree(event));
    }

    /**
     * Validates the JSON form of an event.
     *
     * @param eventJson the serialized event
     * @return validation errors, sorted; empty if the event is valid
     */
    public List<String> validate(JsonNode eventJson) {
        String type = eventJson.path("type").asText(null);
        JsonSchema schema = type == null ? null : compiled.get(type);
        if (schema == null) {
            return List.of("$.type: unknown event type '" + type + "'");
        }
        return schema.validate(eventJson).stream().map(ValidationMessage::getMessage).sorted().toList();
    }

    private static ObjectNode assemble(ObjectNode envelope, JsonNode defs, ObjectNode payload, String type) {
        ObjectNode schema = envelope.deepCopy();
        schema.put("title", type);
        schema.put("description", payload.path("description").asText(""));
        schema.set("$defs", defs.deepCopy());
        ObjectNode properties = (ObjectNode) schema.get("properties");
        ((ObjectNode) properties.get("type")).put("const", type);
        ObjectNode payloadSchema = payload.deepCopy();
        payloadSchema.remove("$schema");
        properties.set("payload", payloadSchema);
        return schema;
    }

    private ObjectNode load(String path) {
        try (InputStream in = EventSchemaRegistry.class.getClassLoader().getResourceAsStream(BASE + path)) {
            if (in == null) {
                throw new IllegalStateException("Missing event schema resource: " + BASE + path);
            }
            return (ObjectNode) objectMapper.readTree(in);
        } catch (IOException | ClassCastException e) {
            throw new IllegalStateException("Unreadable event schema resource: " + BASE + path, e);
        }
    }
}
