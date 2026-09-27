package io.apitomy.axiom.events.github.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.events.core.ApiResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Client for the GitHub Repository Events API. Uses ETag-based conditional
 * polling for efficiency — returns 304 Not Modified when nothing has changed.
 */
@ApplicationScoped
public class GitHubEventsApiClient {

    private static final Logger LOG = Logger.getLogger(GitHubEventsApiClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    @Inject
    ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Result of a repository events poll, including ETag and poll interval headers.
     */
    public record EventsPollResult(
            /** Whether the request succeeded (200 or 304). */
            boolean success,
            /** The events array (null if 304 or error). */
            JsonNode events,
            /** True if 304 Not Modified (no new events). */
            boolean notModified,
            /** ETag header from the response, to send as If-None-Match on next poll. */
            String etag,
            /** X-Poll-Interval header value in seconds (GitHub's recommended minimum). */
            int pollInterval,
            /** HTTP status code. */
            int statusCode,
            /** Error message if the request failed. */
            String errorMessage
    ) {
        public static EventsPollResult notModified(String etag, int pollInterval) {
            return new EventsPollResult(true, null, true, etag, pollInterval, 304, null);
        }

        public static EventsPollResult ok(JsonNode events, String etag, int pollInterval) {
            return new EventsPollResult(true, events, false, etag, pollInterval, 200, null);
        }

        public static EventsPollResult error(int statusCode, String message) {
            return new EventsPollResult(false, null, false, null, 60, statusCode, message);
        }
    }

    /**
     * Polls the repository events API with optional ETag for conditional requests.
     *
     * @param baseUrl  API base URL (e.g., "https://api.github.com" or GHE URL)
     * @param owner    repository owner
     * @param repo     repository name
     * @param token    GitHub API token (may be null for public repos)
     * @param etag     ETag from a previous poll (null for first poll)
     * @return the poll result with events, ETag, and recommended poll interval
     */
    public EventsPollResult fetchRepositoryEvents(String baseUrl, String owner, String repo,
                                                    String token, String etag) {
        String url = baseUrl + "/repos/" + owner + "/" + repo + "/events?per_page=100";
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .timeout(REQUEST_TIMEOUT)
                    .GET();

            if (token != null && !token.isEmpty()) {
                builder.header("Authorization", "Bearer " + token);
            }
            if (etag != null && !etag.isEmpty()) {
                builder.header("If-None-Match", etag);
            }

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            String responseEtag = response.headers().firstValue("ETag").orElse(etag);
            int pollIntervalSeconds = response.headers().firstValue("X-Poll-Interval")
                    .map(Integer::parseInt).orElse(60);

            if (response.statusCode() == 304) {
                return EventsPollResult.notModified(responseEtag, pollIntervalSeconds);
            } else if (response.statusCode() == 200) {
                JsonNode events = objectMapper.readTree(response.body());
                return EventsPollResult.ok(events, responseEtag, pollIntervalSeconds);
            } else {
                LOG.warnf("GitHub Events API returned %d for %s", response.statusCode(), url);
                return EventsPollResult.error(response.statusCode(),
                        "HTTP " + response.statusCode() + ": " + truncate(response.body(), 500));
            }
        } catch (IOException | InterruptedException e) {
            LOG.errorf(e, "Failed to call GitHub Events API: %s", url);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return EventsPollResult.error(0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Fetches the full pull request object. Needed because the Events API
     * truncates PullRequestEvent payloads to only {id, number, url, head, base}.
     *
     * @param baseUrl API base URL
     * @param owner   repository owner
     * @param repo    repository name
     * @param number  PR number
     * @param token   GitHub API token
     * @return the API result with the full PR JSON object
     */
    public ApiResult fetchPullRequest(String baseUrl, String owner, String repo,
                                       int number, String token) {
        String url = baseUrl + "/repos/" + owner + "/" + repo + "/pulls/" + number;
        return doGet(url, token);
    }

    private ApiResult doGet(String url, String token) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .timeout(REQUEST_TIMEOUT)
                    .GET();

            if (token != null && !token.isEmpty()) {
                builder.header("Authorization", "Bearer " + token);
            }

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return ApiResult.ok(objectMapper.readTree(response.body()),
                        "GET", url, null, response.body());
            } else {
                return ApiResult.httpError(response.statusCode(),
                        "GET", url, null, response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ApiResult.exception((Exception) e, "GET", url, null);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
