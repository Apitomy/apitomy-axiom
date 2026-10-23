package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.events.core.ApiResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * HTTP client for the Jira Cloud REST API (v2 - multi-project with changelog).
 * Uses JQL search with changelog expansion to detect field-level changes
 * across multiple Jira projects.
 */
@ApplicationScoped
public class JiraEventsApiClient {

    private static final Logger LOG = Logger.getLogger(JiraEventsApiClient.class);

    @Inject
    ObjectMapper objectMapper;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Overlap subtracted from the checkpoint in JQL (clock skew and boundary timestamps). */
    static final Duration QUERY_OVERLAP = Duration.ofMinutes(2);
    private static final int SEARCH_PAGE_SIZE = 100;
    private static final int CHANGELOG_PAGE_SIZE = 100;
    private static final int MAX_CHANGELOG_PAGES = 50;
    private static final int COMMENT_PAGE_SIZE = 100;
    private static final int MAX_COMMENT_PAGES = 50;

    /** Fields requested by the search; the configured extra field IDs are appended. */
    static final List<String> SEARCH_FIELDS = List.of("summary", "status", "assignee", "reporter", "labels",
            "priority", "created", "updated", "comment", "resolution", "resolutiondate", "description",
            "issuetype", "components", "fixVersions", "duedate", "parent", "project");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Fetches a single page of issues (with changelog expansion) across multiple projects
     * that have been updated since the given timestamp. Results are ordered by
     * {@code updated ASC}. Callers must follow {@code nextPageToken} in the response
     * (until it is absent or {@code isLast} is true) to retrieve the full result set.
     *
     * <p>The JQL lower bound is the given {@code since} minus a two-minute buffer (covering
     * modest clock skew), expressed in epoch milliseconds so it is independent of the Jira
     * user's profile timezone. Project keys are quoted. Duplicates resulting from the overlap
     * are removed by source-event de-duplication downstream.</p>
     *
     * @param baseUrl       Jira Cloud base URL (e.g., https://myorg.atlassian.net)
     * @param projects      list of project keys to search
     * @param since         only return issues updated at or after (approximately) this time
     * @param credentials   email:api_token for Basic auth
     * @param nextPageToken the page token from the previous page, or {@code null} for the first page
     * @param extraFieldIds field IDs of the configured extra fields to request in addition to
     *                      {@link #SEARCH_FIELDS}
     * @return API result with one page of the search response
     */
    public ApiResult fetchIssuesPage(String baseUrl, List<String> projects, Instant since,
                                     String credentials, String nextPageToken, List<String> extraFieldIds) {
        Instant bufferedSince = since.minus(QUERY_OVERLAP);
        String jql = projectClause(projects) + " AND updated >= " + bufferedSince.toEpochMilli()
                + " ORDER BY updated ASC";
        String url = baseUrl + "/rest/api/3/search/jql";

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("jql", jql);
            body.put("maxResults", SEARCH_PAGE_SIZE);
            body.put("expand", "changelog");
            if (nextPageToken != null && !nextPageToken.isBlank()) {
                body.put("nextPageToken", nextPageToken);
            }
            ArrayNode fields = body.putArray("fields");
            SEARCH_FIELDS.forEach(fields::add);
            if (extraFieldIds != null) {
                extraFieldIds.stream().filter(id -> !SEARCH_FIELDS.contains(id)).forEach(fields::add);
            }

            String jsonBody = objectMapper.writeValueAsString(body);
            return doPost(url, jsonBody, credentials);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to build Jira search request");
            return ApiResult.exception(e, "POST", url, null);
        }
    }

    /**
     * Builds the JQL project clause with every key quoted ({@code "} and {@code \} escaped).
     *
     * @param projects project keys
     * @return e.g. {@code project in ("A", "B")}
     */
    static String projectClause(List<String> projects) {
        return projects.stream()
                .map(key -> "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(", ", "project in (", ")"));
    }

    /**
     * Fetches all statuses visible to the user ({@code GET /rest/api/3/status}), used to map status IDs to
     * status categories.
     *
     * @param baseUrl     Jira Cloud base URL
     * @param credentials email:api_token for Basic auth
     * @return API result whose data is the array of statuses
     */
    public ApiResult fetchStatuses(String baseUrl, String credentials) {
        return doGet(baseUrl + "/rest/api/3/status", credentials);
    }

    /**
     * Fetches all fields ({@code GET /rest/api/3/field}), used to resolve configured extra field names to IDs.
     *
     * @param baseUrl     Jira Cloud base URL
     * @param credentials email:api_token for Basic auth
     * @return API result whose data is the array of fields
     */
    public ApiResult fetchFields(String baseUrl, String credentials) {
        return doGet(baseUrl + "/rest/api/3/field", credentials);
    }

    /**
     * Fetches the complete changelog of an issue, following {@code startAt} pagination of
     * {@code /rest/api/3/issue/{key}/changelog}. Used when the changelog embedded in search
     * results is truncated. If the changelog exceeds the page safety cap, the newest
     * histories are fetched (oldest ones are skipped) and a warning is logged.
     *
     * @param baseUrl     Jira Cloud base URL
     * @param issueKey    issue key (e.g., PROJ-123)
     * @param credentials email:api_token for Basic auth
     * @return API result whose data is an array of all changelog histories (oldest first)
     */
    public ApiResult fetchFullChangelog(String baseUrl, String issueKey, String credentials) {
        ArrayNode histories = objectMapper.createArrayNode();
        int startAt = 0;
        int pages = 0;
        boolean capAdjusted = false;
        String changelogUrl = baseUrl + "/rest/api/3/issue/"
                + URLEncoder.encode(issueKey, StandardCharsets.UTF_8).replace("+", "%20") + "/changelog";
        while (true) {
            String url = changelogUrl + "?startAt=" + startAt
                    + "&maxResults=" + CHANGELOG_PAGE_SIZE;
            ApiResult page = doGet(url, credentials);
            if (!page.success()) {
                return page;
            }
            JsonNode data = page.data();
            int total = data.path("total").asInt(0);
            if (!capAdjusted && total > CHANGELOG_PAGE_SIZE * MAX_CHANGELOG_PAGES) {
                // Too many histories: jump to the newest ones, which matter for incremental polling.
                capAdjusted = true;
                startAt = total - CHANGELOG_PAGE_SIZE * MAX_CHANGELOG_PAGES;
                LOG.warnf("Changelog for %s has %d entries; only the newest %d are fetched",
                        issueKey, total, CHANGELOG_PAGE_SIZE * MAX_CHANGELOG_PAGES);
                continue;
            }
            JsonNode values = data.path("values");
            if (values.isArray()) {
                values.forEach(histories::add);
            }
            pages++;
            int fetched = values.isArray() ? values.size() : 0;
            startAt += fetched;
            boolean last = data.path("isLast").asBoolean(false) || fetched == 0 || startAt >= total;
            if (last || pages >= MAX_CHANGELOG_PAGES) {
                break;
            }
        }
        return ApiResult.ok(histories, "GET", changelogUrl, null, null);
    }

    /**
     * Fetches all comments of an issue, following {@code startAt} pagination of
     * {@code /rest/api/3/issue/{key}/comment}. Used when the comment list embedded in search results is
     * incomplete.
     *
     * @param baseUrl     Jira Cloud base URL
     * @param issueKey    issue key (e.g., PROJ-123)
     * @param credentials email:api_token for Basic auth
     * @return API result whose data is an array of all comments (oldest first), or the failing page's result
     */
    public ApiResult fetchIssueComments(String baseUrl, String issueKey, String credentials) {
        ArrayNode comments = objectMapper.createArrayNode();
        String commentUrl = baseUrl + "/rest/api/3/issue/"
                + URLEncoder.encode(issueKey, StandardCharsets.UTF_8).replace("+", "%20") + "/comment";
        int startAt = 0;
        for (int pages = 0; pages < MAX_COMMENT_PAGES; pages++) {
            ApiResult page = doGet(commentUrl + "?orderBy=created&startAt=" + startAt
                    + "&maxResults=" + COMMENT_PAGE_SIZE, credentials);
            if (!page.success()) {
                return page;
            }
            JsonNode values = page.data().path("comments");
            int fetched = values.isArray() ? values.size() : 0;
            if (fetched > 0) {
                values.forEach(comments::add);
            }
            startAt += fetched;
            if (fetched == 0 || startAt >= page.data().path("total").asInt(0)) {
                return ApiResult.ok(comments, "GET", commentUrl, null, null);
            }
        }
        LOG.warnf("Comments of %s exceed %d pages; only the first %d are used",
                issueKey, MAX_COMMENT_PAGES, comments.size());
        return ApiResult.ok(comments, "GET", commentUrl, null, null);
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
