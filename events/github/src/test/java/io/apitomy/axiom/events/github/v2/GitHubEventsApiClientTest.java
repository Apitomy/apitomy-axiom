package io.apitomy.axiom.events.github.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.events.github.v2.GitHubEventsApiClient.EventsPollResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GitHubEventsApiClient.EventsPollResult} factory methods.
 */
class GitHubEventsApiClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void okResult_hasCorrectFields() {
        JsonNode events = mapper.createArrayNode();
        EventsPollResult result = EventsPollResult.ok(events, "\"abc123\"", 30);

        assertTrue(result.success());
        assertSame(events, result.events());
        assertFalse(result.notModified());
        assertEquals("\"abc123\"", result.etag());
        assertEquals(30, result.pollInterval());
        assertEquals(200, result.statusCode());
        assertNull(result.errorMessage());
    }

    @Test
    void notModifiedResult_hasCorrectFields() {
        EventsPollResult result = EventsPollResult.notModified("\"etag-value\"", 60);

        assertTrue(result.success());
        assertNull(result.events());
        assertTrue(result.notModified());
        assertEquals("\"etag-value\"", result.etag());
        assertEquals(60, result.pollInterval());
        assertEquals(304, result.statusCode());
        assertNull(result.errorMessage());
    }

    @Test
    void errorResult_hasCorrectFields() {
        EventsPollResult result = EventsPollResult.error(403, "Forbidden");

        assertFalse(result.success());
        assertNull(result.events());
        assertFalse(result.notModified());
        assertNull(result.etag());
        assertEquals(60, result.pollInterval());
        assertEquals(403, result.statusCode());
        assertEquals("Forbidden", result.errorMessage());
    }
}
