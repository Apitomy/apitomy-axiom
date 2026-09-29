package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Loads OpenCode SSE event streams captured from a real {@code opencode serve}.
 */
final class OpenCodeEventFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenCodeEventFixtures() {
    }

    static List<JsonNode> load(String fileName) {
        String resource = "/opencode/events/" + fileName;
        try (InputStream in = OpenCodeEventFixtures.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing fixture " + resource);
            }
            List<JsonNode> events = new ArrayList<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    events.add(MAPPER.readTree(line));
                }
            }
            return events;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode first(List<JsonNode> events, Predicate<JsonNode> predicate) {
        return events.stream().filter(predicate).findFirst()
                .orElseThrow(() -> new AssertionError("No fixture event matches predicate"));
    }

    static boolean isSessionScoped(JsonNode event) {
        JsonNode properties = event.path("properties");
        return !properties.path("sessionID").asText("").isEmpty()
                || !properties.path("part").path("sessionID").asText("").isEmpty()
                || !properties.path("info").path("sessionID").asText("").isEmpty();
    }

    static Predicate<JsonNode> toolPart(String tool, String status) {
        return event -> "message.part.updated".equals(event.path("type").asText())
                && "tool".equals(event.path("properties").path("part").path("type").asText())
                && tool.equals(event.path("properties").path("part").path("tool").asText())
                && status.equals(event.path("properties").path("part").path("state").path("status").asText());
    }

    static Predicate<JsonNode> partOfType(String partType) {
        return event -> "message.part.updated".equals(event.path("type").asText())
                && partType.equals(event.path("properties").path("part").path("type").asText());
    }
}
