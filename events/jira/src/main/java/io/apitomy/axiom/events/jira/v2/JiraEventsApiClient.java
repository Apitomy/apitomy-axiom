package io.apitomy.axiom.events.jira.v2;

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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

/**
 * HTTP client for the Jira Cloud REST API (v2 - multi-project with changelog).
 * Uses JQL search with changelog expansion to detect field-level changes
 * across multiple Jira projects.
 */
@ApplicationScoped
public class JiraEventsApiClient {

    private static final Logger LOG = Logger.getLogger(JiraEventsApiClient.class);

    private static final DateTimeFormatter JQL_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @Inject
    ObjectMapper objectMapper;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Searches for issues across multiple projects that have been updated since
     * the given timestamp, with changelog expansion for field-level change detection.
     *
     * @param baseUrl     Jira Cloud base URL (e.g., https://myorg.atlassian.net)
     * @param projects    list of project keys to search
     * @param since       only return issues updated after this time
     * @param credentials email:api_token for Basic auth
     * @return API result with search response including changelog
     */
    public ApiResult fetchIssuesWithChangelog(String baseUrl, List<String> projects,
                                               Instant since, String credentials) {
        Instant bufferedSince = since.minus(2, java.time.temporal.ChronoUnit.MINUTES);

        // Build JQL: project in (A, B, C) AND updated >= "2026-09-18 10:00"
        String projectList = String.join(", ", projects);
        String jql = "project in (" + projectList + ") AND updated>=\""
                + JQL_DATE_FORMAT.format(bufferedSince) + "\" ORDER BY updated ASC";

        String url = baseUrl + "/rest/api/3/search/jql";

        try {
            var body = objectMapper.createObjectNode();
            body.put("jql", jql);
            body.put("maxResults", 100);
            body.put("expand", "changelog");
            body.putArray("fields")
                    .add("summary").add("status").add("assignee").add("reporter")
                    .add("labels").add("priority").add("created").add("updated")
                    .add("comment").add("resolution").add("description")
                    .add("issuetype").add("components").add("fixVersions")
                    .add("duedate").add("parent");

            String jsonBody = objectMapper.writeValueAsString(body);
            return doPost(url, jsonBody, credentials);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to build Jira search request");
            return ApiResult.exception(e, "POST", url, null);
        }
    }

    /**
     * Fetches comments for a specific issue.
     *
     * @param baseUrl     Jira Cloud base URL
     * @param issueKey    issue key (e.g., PROJ-123)
     * @param credentials email:api_token for Basic auth
     * @return API result with comments array
     */
    public ApiResult fetchIssueComments(String baseUrl, String issueKey, String credentials) {
        String url = baseUrl + "/rest/api/3/issue/" + issueKey + "/comment?orderBy=created&maxResults=100";
        return doGet(url, credentials);
    }

    private ApiResult doPost(String url, String jsonBody, String credentials) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

            addAuth(builder, credentials);

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            String responseBody = response.body();
            if (response.statusCode() == 200) {
                return ApiResult.ok(objectMapper.readTree(responseBody),
                        "POST", url, jsonBody, responseBody);
            } else {
                LOG.warnf("Jira API returned %d for %s", response.statusCode(), url);
                return ApiResult.httpError(response.statusCode(),
                        "POST", url, jsonBody, responseBody);
            }
        } catch (IOException | InterruptedException e) {
            LOG.errorf(e, "Failed to call Jira API: %s", url);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ApiResult.exception(e, "POST", url, jsonBody);
        }
    }

    private ApiResult doGet(String url, String credentials) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .GET();

            addAuth(builder, credentials);

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            String responseBody = response.body();
            if (response.statusCode() == 200) {
                return ApiResult.ok(objectMapper.readTree(responseBody),
                        "GET", url, null, responseBody);
            } else {
                return ApiResult.httpError(response.statusCode(),
                        "GET", url, null, responseBody);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ApiResult.exception(e, "GET", url, null);
        }
    }

    private void addAuth(HttpRequest.Builder builder, String credentials) {
        if (credentials != null && !credentials.isEmpty()) {
            String encoded = Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encoded);
        }
    }
}
