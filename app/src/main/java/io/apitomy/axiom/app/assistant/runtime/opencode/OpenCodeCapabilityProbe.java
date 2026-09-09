package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.assistant.runtime.SessionCompatibilityException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Performs a fail-closed capability probe against an OpenCode runtime.
 */
public final class OpenCodeCapabilityProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROBE_PERMISSION_ID = "probe-permission-id";

    private final HttpClient httpClient;

    /**
     * Creates a probe with default HTTP settings.
     */
    public OpenCodeCapabilityProbe() {
        this(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build());
    }

    OpenCodeCapabilityProbe(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    /**
     * Probes OpenCode runtime capabilities required for interactive assistant sessions.
     *
     * @param client OpenCode HTTP client
     * @return probe result
     */
    public Result probe(OpenCodeAssistantClient client) {
        Objects.requireNonNull(client, "client");

        OpenCodeAssistantClient.HealthStatus healthStatus = client.health();
        if (!healthStatus.healthy()) {
            return Result.fail(SessionCompatibilityException.RUNTIME_UNHEALTHY,
                    "OpenCode runtime health check failed");
        }

        String sessionId;
        try {
            sessionId = client.createSession("Axiom capability probe");
        } catch (RuntimeException e) {
            return Result.fail(SessionCompatibilityException.SESSION_PROTOCOL_UNSUPPORTED,
                    "OpenCode session creation endpoint is unavailable",
                    Map.of("cause", e.getMessage()));
        }

        if (sessionId == null || sessionId.isBlank()) {
            return Result.fail(SessionCompatibilityException.SESSION_PROTOCOL_UNSUPPORTED,
                    "OpenCode session creation did not return an id");
        }

        String baseUrl = client.baseUrl();
        SseEndpointStatus eventEndpointStatus = checkSseEndpoint(baseUrl + "/event");
        if (eventEndpointStatus == SseEndpointStatus.UNMAPPED) {
            SseEndpointStatus globalEventEndpointStatus = checkSseEndpoint(baseUrl + "/global/event");
            if (globalEventEndpointStatus != SseEndpointStatus.SUPPORTED) {
                return Result.fail(SessionCompatibilityException.EVENT_STREAM_UNRELIABLE,
                        "No supported SSE endpoint for assistant runtime");
            }
        } else if (eventEndpointStatus != SseEndpointStatus.SUPPORTED) {
            return Result.fail(SessionCompatibilityException.EVENT_STREAM_UNRELIABLE,
                    "No supported SSE endpoint for assistant runtime");
        }

        try {
            client.sendPromptAsync(sessionId, "capability-probe", null, null);
        } catch (RuntimeException e) {
            return Result.fail(SessionCompatibilityException.PROMPT_PROTOCOL_UNSUPPORTED,
                    "OpenCode prompt endpoint is unavailable",
                    Map.of("sessionId", sessionId, "cause", e.getMessage()));
        }

        PermissionEndpointStatus permissionEndpointStatus = checkPermissionEndpoint(baseUrl, sessionId);
        if (permissionEndpointStatus != PermissionEndpointStatus.SUPPORTED) {
            return Result.fail(SessionCompatibilityException.PERMISSION_PROTOCOL_UNSUPPORTED,
                    "OpenCode permission endpoint is unavailable",
                    Map.of("sessionId", sessionId));
        }

        try {
            client.abort(sessionId);
        } catch (RuntimeException e) {
            return Result.fail(SessionCompatibilityException.INTERRUPT_PROTOCOL_UNSUPPORTED,
                    "OpenCode abort endpoint is unavailable",
                    Map.of("sessionId", sessionId, "cause", e.getMessage()));
        }

        return Result.pass();
    }

    private SseEndpointStatus checkSseEndpoint(String endpoint) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Accept", "text/event-stream")
                .GET()
                .timeout(Duration.ofSeconds(3))
                .build();

        try {
            HttpResponse<java.io.InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            response.body().close();

            int statusCode = response.statusCode();
            if (statusCode == 404 || statusCode == 405) {
                return SseEndpointStatus.UNMAPPED;
            }
            if (statusCode != 200) {
                return SseEndpointStatus.UNRELIABLE;
            }

            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("")
                    .toLowerCase();
            if (!contentType.startsWith("text/event-stream")) {
                return SseEndpointStatus.UNRELIABLE;
            }

            return SseEndpointStatus.SUPPORTED;
        } catch (IOException e) {
            return SseEndpointStatus.UNRELIABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SseEndpointStatus.UNRELIABLE;
        }
    }

    private PermissionEndpointStatus checkPermissionEndpoint(String baseUrl, String sessionId) {
        String permissionEndpoint = baseUrl + "/session/" + sessionId + "/permissions/" + PROBE_PERMISSION_ID;
        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode responseNode = body.putObject("response");
        responseNode.put("behavior", "allow");

        int postStatusCode = sendJsonPost(permissionEndpoint, body.toString());
        if (postStatusCode == 200) {
            return PermissionEndpointStatus.SUPPORTED;
        }
        if (postStatusCode != 404) {
            return PermissionEndpointStatus.UNSUPPORTED;
        }

        int optionsStatusCode = sendOptions(permissionEndpoint);
        if (optionsStatusCode == 200 || optionsStatusCode == 204 || optionsStatusCode == 405) {
            return PermissionEndpointStatus.SUPPORTED;
        }
        return PermissionEndpointStatus.UNSUPPORTED;
    }

    private int sendJsonPost(String endpoint, String body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(3))
                .build();
        return sendStatusCode(request);
    }

    private int sendOptions(String endpoint) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(3))
                .build();
        return sendStatusCode(request);
    }

    private int sendStatusCode(HttpRequest request) {
        try {
            HttpResponse<java.io.InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            response.body().close();
            return response.statusCode();
        } catch (IOException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    private enum SseEndpointStatus {
        SUPPORTED,
        UNMAPPED,
        UNRELIABLE
    }

    private enum PermissionEndpointStatus {
        SUPPORTED,
        UNSUPPORTED
    }

    /**
     * Result of a capability probe.
     *
     * @param compatible true when the runtime satisfies required capabilities
     * @param code compatibility failure code, or null when compatible
     * @param message compatibility failure message, or null when compatible
     * @param details optional compatibility diagnostic details
     */
    public record Result(boolean compatible, String code, String message, Map<String, String> details) {

        /**
         * Creates a passing probe result.
         *
         * @return passing result
         */
        public static Result pass() {
            return new Result(true, null, null, Map.of());
        }

        /**
         * Creates a failing probe result with no details.
         *
         * @param code compatibility failure code
         * @param message compatibility failure message
         * @return failing result
         */
        public static Result fail(String code, String message) {
            return fail(code, message, Map.of());
        }

        /**
         * Creates a failing probe result with details.
         *
         * @param code compatibility failure code
         * @param message compatibility failure message
         * @param details structured compatibility details
         * @return failing result
         */
        public static Result fail(String code, String message, Map<String, String> details) {
            return new Result(false, code, message, details != null ? Map.copyOf(details) : Map.of());
        }
    }
}
