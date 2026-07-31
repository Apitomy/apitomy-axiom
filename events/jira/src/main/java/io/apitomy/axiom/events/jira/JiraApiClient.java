package io.apitomy.axiom.events.jira;

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

/**
 * HTTP client for the Jira Cloud REST API.
 * Uses JQL search to fetch issues updated since a given timestamp.
 */
@ApplicationScoped
public class JiraApiClient {

    private static final Logger LOG = Logger.getLogger(JiraApiClient.class);

    private static final DateTimeFormatter JQL_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @Inject
    ObjectMapper objectMapper;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Searches for issues in a project that have been updated since the given timestamp.
     *
     * @param baseUrl the Jira Cloud base URL (e.g. https://myorg.atlassian.net)
     * @param project the Jira project key (e.g. MYPROJECT)
     * @param since only return issues updated after this time
     * @param credentials the authentication credentials (email:api_token)
     * @return the API result with the search response JSON
     */
    public ApiResult fetchIssuesUpdatedSince(String baseUrl, String project,
                                              Instant since, String credentials) {
        Instant bufferedSince = since.minus(2, java.time.temporal.ChronoUnit.MINUTES);
        String jql = "project=" + project + " AND updated>=\""
                + JQL_DATE_FORMAT.format(bufferedSince) + "\" ORDER BY updated ASC";
        String url = baseUrl + "/rest/api/3/search/jql";

        try {
            var body = objectMapper.createObjectNode();
            body.put("jql", jql);
            body.put("maxResults", 100);
            body.putArray("fields").add("summary").add("status").add("assignee")
                    .add("reporter").add("labels").add("priority").add("created")
                    .add("updated").add("comment").add("resolution");
            String jsonBody = objectMapper.writeValueAsString(body);
            return doPost(url, jsonBody, credentials);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to build Jira search request for project %s", project);
            return ApiResult.exception(e, "POST", url, null);
        }
    }

    private ApiResult doPost(String url, String jsonBody, String credentials) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

            if (credentials != null && !credentials.isEmpty()) {
                String encoded = Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + encoded);
            }

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
}
