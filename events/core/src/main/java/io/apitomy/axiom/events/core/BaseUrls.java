package io.apitomy.axiom.events.core;

/**
 * Helpers for event-source base URLs.
 */
public final class BaseUrls {

    private BaseUrls() {
    }

    /**
     * Normalizes a base URL by trimming surrounding whitespace and stripping trailing {@code /} characters.
     *
     * @param baseUrl the URL, may be null
     * @return the normalized URL, or null when {@code baseUrl} is null
     */
    public static String normalize(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String trimmed = baseUrl.trim();
        int end = trimmed.length();
        while (end > 0 && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        return trimmed.substring(0, end);
    }
}
