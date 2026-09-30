package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.assistant.runtime.SessionCompatibilityException;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Performs a fail-closed, side-effect-free capability probe against an OpenCode runtime.
 *
 * <p>The probe checks runtime health, then reads the runtime's OpenAPI document from {@code GET /doc} and
 * verifies that the session, prompt, permission, abort and event-stream operations are declared. It then
 * performs a live check that the event stream answers with {@code text/event-stream}. When {@code /doc} is
 * unavailable (older runtimes), it falls back to creating a probe session, checking the event stream and
 * deleting that session again.</p>
 *
 * <p>The probe never sends a prompt, never replies to a permission and never aborts a session, so it has no
 * side effects on the runtime (apart from the short-lived fallback session).</p>
 *
 * <p>Passing results are cached per OpenCode version reported by the health endpoint, because the protocol
 * surface is tied to the version; failures and blank versions are never cached. On a cache hit the live
 * event-stream (SSE) check is skipped as well, so a broken event stream on a cached version is not caught
 * here; it still surfaces when the driver connects to the event stream.</p>
 */
public final class OpenCodeCapabilityProbe {

    private static final Logger LOG = Logger.getLogger(OpenCodeCapabilityProbe.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Result> PASS_CACHE = new ConcurrentHashMap<>();

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
     * Clears the per-version cache of passing results. Intended for tests.
     */
    static void clearCache() {
        PASS_CACHE.clear();
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

        String version = healthStatus.version();
        boolean cacheable = version != null && !version.isBlank();
        if (cacheable) {
            Result cached = PASS_CACHE.get(version);
            if (cached != null) {
                return cached;
            }
        }

        JsonNode spec = fetchSpec(client.baseUrl());
        Result result;
        if (spec != null) {
            Result specFailure = checkSpec(spec);
            result = specFailure != null ? specFailure : checkEventStream(client.baseUrl());
        } else {
            result = fallbackProbe(client);
        }

        if (result.compatible() && cacheable) {
            PASS_CACHE.put(version, result);
        }
        return result;
    }

    private JsonNode fetchSpec(String baseUrl) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/doc"))
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            JsonNode spec = MAPPER.readTree(response.body());
            return spec != null && spec.isObject() ? spec : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Result checkSpec(JsonNode spec) {
        if (!hasOperation(spec, "post", "/session")) {
            return Result.fail(SessionCompatibilityException.SESSION_PROTOCOL_UNSUPPORTED,
                    "OpenCode API does not declare the session creation endpoint");
        }
        if (!hasOperation(spec, "post", "/session/{sessionID}/prompt_async")) {
            return Result.fail(SessionCompatibilityException.PROMPT_PROTOCOL_UNSUPPORTED,
                    "OpenCode API does not declare the prompt endpoint");
        }
        if (!hasOperation(spec, "post", "/session/{sessionID}/permissions/{permissionID}")) {
            return Result.fail(SessionCompatibilityException.PERMISSION_PROTOCOL_UNSUPPORTED,
                    "OpenCode API does not declare the permission endpoint");
        }
        if (!hasOperation(spec, "post", "/session/{sessionID}/abort")) {
            return Result.fail(SessionCompatibilityException.INTERRUPT_PROTOCOL_UNSUPPORTED,
                    "OpenCode API does not declare the abort endpoint");
        }
        if (!hasOperation(spec, "get", "/event") && !hasOperation(spec, "get", "/global/event")) {
            return Result.fail(SessionCompatibilityException.EVENT_STREAM_UNRELIABLE,
                    "OpenCode API does not declare an event stream endpoint");
        }
        return null;
    }

    private static boolean hasOperation(JsonNode spec, String method, String path) {
        return spec.path("paths").path(path).has(method);
    }

    private Result fallbackProbe(OpenCodeAssistantClient client) {
        String sessionId;
        try {
            sessionId = client.createSession("Axiom capability probe");
        } catch (RuntimeException e) {
            return Result.fail(SessionCompatibilityException.SESSION_PROTOCOL_UNSUPPORTED,
                    "OpenCode session creation endpoint is unavailable",
                    Map.of("cause", String.valueOf(e.getMessage())));
        }

        if (sessionId == null || sessionId.isBlank()) {
            return Result.fail(SessionCompatibilityException.SESSION_PROTOCOL_UNSUPPORTED,
                    "OpenCode session creation did not return an id");
        }

        try {
            return checkEventStream(client.baseUrl());
        } finally {
            try {
                client.deleteSession(sessionId);
            } catch (RuntimeException e) {
                LOG.debugf(e, "Failed to delete OpenCode capability probe session %s", sessionId);
            }
        }
    }

    private Result checkEventStream(String baseUrl) {
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

    private enum SseEndpointStatus {
        SUPPORTED,
        UNMAPPED,
        UNRELIABLE
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
