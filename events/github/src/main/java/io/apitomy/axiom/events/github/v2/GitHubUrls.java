package io.apitomy.axiom.events.github.v2;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The web and REST API base URLs of a GitHub server, resolved from the loosely formatted base URL configured on an
 * event source.
 *
 * <p>Accepted inputs include the web URL ({@code https://github.com}, {@code https://github.example.com}), a
 * repository or other page on the server ({@code https://github.com/owner/repo}), the REST API URL
 * ({@code https://api.github.com}, {@code https://github.example.com/api/v3}), a {@code www.} host, and a value
 * without a scheme. Scheme and host are case-insensitive; trailing slashes, query and fragment are ignored.
 * Repository paths are never used to select repositories; those come from the event source configuration.</p>
 *
 * @param webBaseUrl the server's web URL, used to build event references (e.g. {@code https://github.com})
 * @param apiBaseUrl the server's REST API URL (e.g. {@code https://api.github.com})
 */
public record GitHubUrls(String webBaseUrl, String apiBaseUrl) {

    /** GitHub.com. */
    public static final GitHubUrls GITHUB_COM = new GitHubUrls("https://github.com", "https://api.github.com");

    private static final String API_V3 = "/api/v3";
    private static final String GHE_COM = ".ghe.com";

    /**
     * Resolves a configured base URL.
     *
     * <ul>
     *   <li>{@code github.com}, {@code www.github.com}, {@code api.github.com} (any path): GitHub.com;</li>
     *   <li>{@code <org>.ghe.com} or {@code api.<org>.ghe.com}: GitHub Enterprise Cloud with data residency
     *       ({@code https://<org>.ghe.com} / {@code https://api.<org>.ghe.com});</li>
     *   <li>any other host whose path contains {@code /api/v3}: GitHub Enterprise Server, API URL up to and
     *       including {@code /api/v3}, web URL is everything before it;</li>
     *   <li>any other host: GitHub Enterprise Server at that host, API URL {@code <scheme>://<host>/api/v3}.</li>
     * </ul>
     *
     * @param baseUrl the configured value; null or blank means GitHub.com
     * @return the resolved URLs
     * @throws IllegalArgumentException if the value is not an http(s) URL with a host
     */
    public static GitHubUrls resolve(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return GITHUB_COM;
        }
        String value = baseUrl.strip();
        if (!value.contains("://")) {
            value = "https://" + value;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Not a valid URL: " + baseUrl, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IllegalArgumentException("Base URL must use http or https: " + baseUrl);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isBlank()) {
            throw new IllegalArgumentException("Base URL has no host: " + baseUrl);
        }

        if (host.equals("github.com") || host.equals("www.github.com") || host.equals("api.github.com")) {
            return GITHUB_COM;
        }
        if (host.endsWith(GHE_COM)) {
            String org = host.startsWith("api.") ? host.substring(4) : host;
            return new GitHubUrls("https://" + org, "https://api." + org);
        }

        String origin = scheme + "://" + host + (uri.getPort() != -1 ? ":" + uri.getPort() : "");
        String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
        int api = path.indexOf(API_V3);
        if (api >= 0 && (path.length() == api + API_V3.length() || path.charAt(api + API_V3.length()) == '/')) {
            String prefix = path.substring(0, api);
            return new GitHubUrls(origin + prefix, origin + prefix + API_V3);
        }
        return new GitHubUrls(origin, origin + API_V3);
    }

    /** Path segments that follow {@code owner/repo} in the refs built by the event normalizer. */
    private static final Set<String> REF_KINDS = Set.of("issues", "pull", "commit", "tree", "releases");

    /**
     * Extracts {@code owner/repo} from an event ref built by the GitHub normalizer, e.g.
     * {@code https://github.com/owner/repo/issues/42} or {@code https://github.com/owner/repo}. The two path
     * segments before {@code issues}, {@code pull}, {@code commit}, {@code tree} or {@code releases} are used, or
     * the last two segments when none is present (which also tolerates a path prefix on the web URL).
     *
     * @param ref the event ref
     * @return {@code owner/repo}, or empty when it cannot be determined
     */
    public static Optional<String> repositoryFromRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        String path;
        try {
            path = new URI(ref.strip()).getPath();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        if (path == null) {
            return Optional.empty();
        }
        List<String> segments = Arrays.stream(path.split("/")).filter(seg -> !seg.isBlank()).toList();
        int end = segments.size();
        for (int i = 2; i < segments.size(); i++) {
            if (REF_KINDS.contains(segments.get(i))) {
                end = i;
                break;
            }
        }
        if (end < 2) {
            return Optional.empty();
        }
        return Optional.of(segments.get(end - 2) + "/" + segments.get(end - 1));
    }
}
