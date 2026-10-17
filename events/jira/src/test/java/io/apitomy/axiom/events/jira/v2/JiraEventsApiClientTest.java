package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.apitomy.axiom.events.core.ApiResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JiraEventsApiClient} against a local Jira API stub.
 */
class JiraEventsApiClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private JiraEventsApiClient client;
    private final List<JsonNode> searchBodies = new ArrayList<>();
    private final List<String> changelogQueries = new ArrayList<>();
    private final List<String> changelogPaths = new ArrayList<>();
    private final Map<String, String> searchResponses = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/api/3/search/jql", this::handleSearch);
        server.createContext("/rest/api/3/issue/", this::handleChangelog);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new JiraEventsApiClient();
        client.objectMapper = MAPPER;
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void clientCanBeInstantiated() {
        assertNotNull(new JiraEventsApiClient());
    }

    @Test
    void fetchIssuesPage_sendsNextPageTokenAndReturnsPage() {
        searchResponses.put("", "{\"issues\":[{\"key\":\"P-1\"}],\"nextPageToken\":\"tok2\"}");
        searchResponses.put("tok2", "{\"issues\":[{\"key\":\"P-2\"}],\"isLast\":true}");

        ApiResult first = client.fetchIssuesPage(baseUrl, List.of("P"),
                Instant.parse("2026-10-01T10:00:00Z"), "a:b", null);
        assertTrue(first.success());
        assertEquals("tok2", first.data().path("nextPageToken").asText());

        ApiResult second = client.fetchIssuesPage(baseUrl, List.of("P"),
                Instant.parse("2026-10-01T10:00:00Z"), "a:b", "tok2");
        assertTrue(second.success());
        assertEquals("P-2", second.data().path("issues").get(0).path("key").asText());

        assertFalse(searchBodies.get(0).has("nextPageToken"));
        assertEquals("tok2", searchBodies.get(1).path("nextPageToken").asText());
        assertTrue(searchBodies.get(0).path("jql").asText().contains("ORDER BY updated ASC"));
    }

    @Test
    void fetchIssuesPage_usesEpochMillisLowerBoundInJql() {
        Instant since = Instant.parse("2026-10-01T10:00:00Z");
        long expected = since.minus(JiraEventsApiClient.QUERY_OVERLAP).toEpochMilli();

        client.fetchIssuesPage(baseUrl, List.of("P"), since, "a:b", null);

        String jql = searchBodies.get(0).path("jql").asText();
        assertTrue(jql.contains("updated >= " + expected), jql);
    }

    @Test
    void fetchFullChangelog_urlEncodesIssueKey() {
        client.fetchFullChangelog(baseUrl, "P 1", "a:b");

        assertEquals("/rest/api/3/issue/P%201/changelog", changelogPaths.get(0));
    }

    @Test
    void fetchFullChangelog_followsAllPages() {
        ApiResult result = client.fetchFullChangelog(baseUrl, "P-1", "a:b");

        assertTrue(result.success());
        assertEquals(250, result.data().size());
        assertEquals("0", result.data().get(0).path("id").asText());
        assertEquals("249", result.data().get(249).path("id").asText());
        assertEquals(3, changelogQueries.size());
    }

    private void handleSearch(HttpExchange exchange) throws IOException {
        JsonNode body = MAPPER.readTree(exchange.getRequestBody());
        searchBodies.add(body);
        String token = body.path("nextPageToken").asText("");
        respond(exchange, 200, searchResponses.getOrDefault(token, "{\"issues\":[],\"isLast\":true}"));
    }

    private void handleChangelog(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        changelogQueries.add(query);
        changelogPaths.add(exchange.getRequestURI().getRawPath());
        int startAt = 0;
        for (String part : query.split("&")) {
            if (part.startsWith("startAt=")) {
                startAt = Integer.parseInt(part.substring("startAt=".length()));
            }
        }
        int total = 250;
        int end = Math.min(startAt + 100, total);
        ObjectNode page = MAPPER.createObjectNode();
        page.put("startAt", startAt);
        page.put("maxResults", 100);
        page.put("total", total);
        page.put("isLast", end >= total);
        ArrayNode values = page.putArray("values");
        for (int i = startAt; i < end; i++) {
            values.addObject().put("id", String.valueOf(i));
        }
        respond(exchange, 200, MAPPER.writeValueAsString(page));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
