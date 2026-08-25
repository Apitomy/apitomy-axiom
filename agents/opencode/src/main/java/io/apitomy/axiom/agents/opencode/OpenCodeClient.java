package io.apitomy.axiom.agents.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Java HTTP client for the OpenCode server API. Wraps the REST endpoints
 * exposed by {@code opencode serve} to provide session management,
 * prompt execution, and server health checking.
 *
 * @see <a href="https://opencode.ai/docs/server/">OpenCode Server Docs</a>
 */
public class OpenCodeClient {

    private static final Logger LOG = Logger.getLogger(OpenCodeClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient httpClient;

    /**
     * Creates a new client targeting the OpenCode server at the given host and port.
     *
     * @param hostname the server hostname
     * @param port     the server port
     */
    public OpenCodeClient(String hostname, int port) {
        this.baseUrl = "http://" + hostname + ":" + port;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Checks server health.
     *
     * @return true if the server is healthy
     */
    public boolean isHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/global/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode body = MAPPER.readTree(response.body());
                return body.path("healthy").asBoolean(false);
            }
            return false;
        } catch (Exception e) {
            LOG.tracef("Health check failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Creates a new session, using a default 10-second timeout.
     *
     * @param title optional session title
     * @return the session ID
     * @throws IOException          if the HTTP request fails
     * @throws InterruptedException if the thread is interrupted
     */
    public String createSession(String title) throws IOException, InterruptedException {
        return createSession(title, 10);
    }

    /**
     * Creates a new session.
     *
     * <p>Under load (many concurrent tasks hitting a single {@code opencode serve}
     * process), session creation can be briefly delayed well past a few seconds even
     * though the server is healthy and recovers. Callers that already have a
     * configured request timeout (e.g. the Manager/report timeout-seconds) should
     * pass it through here rather than relying on the short default, to avoid
     * spurious timeouts under load.</p>
     *
     * @param title          optional session title
     * @param timeoutSeconds request timeout in seconds
     * @return the session ID
     * @throws IOException          if the HTTP request fails
     * @throws InterruptedException if the thread is interrupted
     */
    public String createSession(String title, int timeoutSeconds) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        if (title != null) {
            body.put("title", title);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        // Use a fresh, single-use HttpClient rather than the shared pooled one.
        // Under concurrent load we've observed the shared client occasionally
        // hang indefinitely waiting on a response over a reused/pooled
        // connection to the opencode (Bun) server, even though the server
        // processed the request and responded in milliseconds. A dedicated
        // client avoids reusing a possibly-stale connection.
        HttpResponse<String> response = freshClient(timeoutSeconds)
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException("Failed to create session: HTTP " + response.statusCode()
                    + " — " + response.body());
        }

        JsonNode result = MAPPER.readTree(response.body());
        return result.path("id").asText();
    }

    /**
     * Sends a prompt to a session and waits for the response.
     *
     * @param sessionId      the session ID
     * @param prompt         the prompt text
     * @param model          the model in provider/model format, or null for default
     * @param format         structured output format, or null for text
     * @param permissions    OpenCode permission config, or null for defaults
     * @param timeoutSeconds request timeout
     * @return the response JSON
     * @throws IOException          if the HTTP request fails
     * @throws InterruptedException if the thread is interrupted
     */
    public JsonNode sendPrompt(String sessionId, String prompt, String model,
                                JsonNode format, Map<String, Object> permissions,
                                int timeoutSeconds)
            throws IOException, InterruptedException {

        ObjectNode body = MAPPER.createObjectNode();

        // Parts array
        ArrayNode parts = body.putArray("parts");
        ObjectNode textPart = parts.addObject();
        textPart.put("type", "text");
        textPart.put("text", prompt);

        // Model
        if (model != null && model.contains("/")) {
            ObjectNode modelNode = body.putObject("model");
            String[] split = model.split("/", 2);
            modelNode.put("providerID", split[0]);
            modelNode.put("modelID", split[1]);
        }

        // Structured output format
        if (format != null) {
            body.set("format", format);
        }

        // Tool restrictions: OpenCode expects an object mapping tool name to
        // a boolean (true = allowed), not an array of names.
        if (permissions != null && !permissions.isEmpty()) {
            ObjectNode toolsNode = body.putObject("tools");
            for (Map.Entry<String, Object> entry : permissions.entrySet()) {
                toolsNode.put(entry.getKey(), "allow".equals(entry.getValue()));
            }
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session/" + sessionId + "/message"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        // See createSession() — use a dedicated client to avoid a hang on a
        // stale pooled connection during this (often long-running) call.
        HttpResponse<String> response = freshClient(timeoutSeconds)
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Prompt failed: HTTP " + response.statusCode()
                    + " — " + truncate(response.body(), 500));
        }

        return MAPPER.readTree(response.body());
    }

    /**
     * Builds a single-use {@link HttpClient} dedicated to one request, rather
     * than reusing the shared pooled client. Under concurrent load against the
     * {@code opencode serve} (Bun) process we've observed the shared client's
     * pooled connections occasionally hang indefinitely waiting on a response
     * that the server already sent, so long-running/high-value calls
     * (session creation, prompt sending) use a fresh connection instead.
     */
    private HttpClient freshClient(int timeoutSeconds) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)))
                .build();
    }

    /**
     * Aborts a running session.
     *
     * @param sessionId the session ID to abort
     */
    public void abortSession(String sessionId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/session/" + sessionId + "/abort"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            LOG.warnf("Failed to abort session %s: %s", sessionId, e.getMessage());
        }
    }

    /**
     * Adds an MCP server dynamically.
     *
     * @param name   the MCP server name
     * @param config the MCP server configuration
     * @throws IOException          if the HTTP request fails
     * @throws InterruptedException if the thread is interrupted
     */
    public void addMcpServer(String name, Map<String, Object> config)
            throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("name", name);
        body.set("config", MAPPER.valueToTree(config));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            LOG.warnf("Failed to add MCP server '%s': HTTP %d", name, response.statusCode());
        }
    }

    /**
     * Gets the server version string.
     *
     * @return the version string, or null if unavailable
     */
    public String getVersion() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/global/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode body = MAPPER.readTree(response.body());
                return body.path("version").asText("unknown");
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * Returns the base URL of the OpenCode server.
     *
     * @return the base URL string
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
