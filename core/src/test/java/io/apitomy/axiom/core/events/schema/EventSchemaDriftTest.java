package io.apitomy.axiom.core.events.schema;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.events.model.Actor;
import io.apitomy.axiom.core.events.model.Change;
import io.apitomy.axiom.core.events.model.Commit;
import io.apitomy.axiom.core.events.model.EventType;
import io.apitomy.axiom.core.events.model.NormalizedComment;
import io.apitomy.axiom.core.events.model.NormalizedIssue;
import io.apitomy.axiom.core.events.model.NormalizedLabel;
import io.apitomy.axiom.core.events.model.NormalizedPullRequest;
import io.apitomy.axiom.core.events.model.NormalizedReview;
import io.apitomy.axiom.core.events.model.payloads.EventPayload;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Guards against the hand-written schemas drifting from the Java event model. */
class EventSchemaDriftTest {

    private static final EventSchemaRegistry REGISTRY = new EventSchemaRegistry(new ObjectMapper());

    @Test
    void payloadFilesMatchEventTypesExactly() {
        File dir = new File("src/main/resources/schemas/events/payloads");
        Set<String> files = Arrays.stream(dir.list()).map(n -> n.replace(".json", ""))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> types = Arrays.stream(EventType.values()).map(EventType::value)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(types, files);
    }

    @Test
    void sharedDefsCoverEveryRecordComponent() {
        JsonNode defs = REGISTRY.schemaFor("push").orElseThrow().path("$defs");
        Map<String, Class<?>> records = new LinkedHashMap<>();
        records.put("Actor", Actor.class);
        records.put("Change", Change.class);
        records.put("Commit", Commit.class);
        records.put("CommitAuthor", Commit.CommitAuthor.class);
        records.put("NormalizedComment", NormalizedComment.class);
        records.put("NormalizedIssue", NormalizedIssue.class);
        records.put("NormalizedLabel", NormalizedLabel.class);
        records.put("NormalizedPullRequest", NormalizedPullRequest.class);
        records.put("NormalizedReview", NormalizedReview.class);
        List<String> problems = new ArrayList<>();
        records.forEach((def, type) -> compare(def, type, defs.path(def).path("properties"), Set.of(), problems));
        assertEquals(List.of(), problems);
    }

    @Test
    void payloadSchemasCoverEveryPayloadRecordComponent() {
        List<String> problems = new ArrayList<>();
        for (JsonSubTypes.Type sub : EventPayload.class.getAnnotation(JsonSubTypes.class).value()) {
            JsonNode props = REGISTRY.schemaFor(sub.name()).orElseThrow().at("/properties/payload/properties");
            compare(sub.name(), sub.value(), props, Set.of("type"), problems);
        }
        assertEquals(List.of(), problems);
    }

    private static void compare(String name, Class<?> type, JsonNode props, Set<String> extraAllowed,
                                List<String> problems) {
        Set<String> components = Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> schemaProps = new TreeSet<>();
        for (Iterator<String> it = props.fieldNames(); it.hasNext(); ) {
            schemaProps.add(it.next());
        }
        for (String c : components) {
            if (!schemaProps.contains(c)) {
                problems.add(name + ": schema missing property '" + c + "'");
            }
        }
        for (String p : schemaProps) {
            if (!components.contains(p) && !extraAllowed.contains(p)) {
                problems.add(name + ": schema property '" + p + "' has no record component");
            }
        }
    }
}
