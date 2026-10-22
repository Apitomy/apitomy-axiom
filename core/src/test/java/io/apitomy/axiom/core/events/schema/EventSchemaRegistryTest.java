package io.apitomy.axiom.core.events.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.events.model.Actor;
import io.apitomy.axiom.core.events.model.EventType;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.model.NormalizedIssue;
import io.apitomy.axiom.core.events.model.payloads.IssueClosedPayload;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSchemaRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static EventSchemaRegistry registry;

    @BeforeAll
    static void load() {
        registry = new EventSchemaRegistry(MAPPER);
    }

    static NormalizedEvent closedEvent(String state, String createdAt) {
        Actor actor = new Actor("u1", "User One", null, null);
        NormalizedIssue issue = new NormalizedIssue("PROJ-1", "Title", "Body", state, "Closed", actor,
                List.of(), List.of("bug"), null, "https://jira.example/browse/PROJ-1",
                createdAt, "2026-10-21T14:54:06.982Z", null);
        return new NormalizedEvent("04f96ff3-6a2c-4766-aa84-27da50fb366f", "PROJ-1-100", "jira", "src",
                EventType.ISSUE_CLOSED, "https://jira.example/browse/PROJ-1", "2026-10-21T14:54:06Z",
                actor, new IssueClosedPayload(issue), MAPPER.createObjectNode().put("anything", 1));
    }

    @Test
    void listsEveryEventTypeSorted() {
        List<EventSchemaSummary> types = registry.eventTypes();
        assertEquals(EventType.values().length, types.size());
        assertEquals("branch.created", types.get(0).type());
        assertTrue(types.stream().allMatch(t -> !t.description().isBlank()));
    }

    @Test
    void assembledSchemaIsSelfContained() {
        JsonNode schema = registry.schemaFor("issue.closed").orElseThrow();
        assertEquals("issue.closed", schema.path("title").asText());
        assertEquals("issue.closed", schema.at("/properties/type/const").asText());
        assertEquals("issue.closed", schema.at("/properties/payload/properties/type/const").asText());
        assertTrue(schema.at("/$defs/NormalizedIssue").isObject());
    }

    @Test
    void unknownTypeHasNoSchema() {
        assertEquals(Optional.empty(), registry.schemaFor("nope"));
    }

    @Test
    void validEventHasNoErrors() {
        assertEquals(List.of(), registry.validate(closedEvent("closed", "2026-10-21T14:53:07.835Z")));
    }

    @Test
    void sourceDataIsNotValidated() {
        NormalizedEvent base = closedEvent("closed", "2026-10-21T14:53:07.835Z");
        com.fasterxml.jackson.databind.node.ArrayNode mixed = MAPPER.createArrayNode();
        mixed.add(1);
        mixed.add("x");
        mixed.addObject().putNull("a");
        NormalizedEvent event = new NormalizedEvent(base.id(), base.sourceEventId(), base.source(),
                base.eventSourceId(), base.type(), base.ref(), base.timestamp(), base.actor(), base.payload(),
                mixed);
        assertEquals(List.of(), registry.validate(event));
    }

    @Test
    void reportsBadStateAndNonIsoTimestamp() {
        List<String> errors = registry.validate(closedEvent("weird", "2026-10-21T14:53:07.835+0000"));
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("$.payload.issue.state")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("$.payload.issue.createdAt")), errors.toString());
    }

    @Test
    void unknownTypeIsAnError() {
        JsonNode json = MAPPER.createObjectNode().put("type", "nope");
        List<String> errors = registry.validate(json);
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("unknown event type"));
    }

    @Test
    void schemaForReturnsACopy() {
        ((com.fasterxml.jackson.databind.node.ObjectNode) registry.schemaFor("push").orElseThrow())
                .put("title", "changed");
        assertEquals("push", registry.schemaFor("push").orElseThrow().path("title").asText());
    }
}
