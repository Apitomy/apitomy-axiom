package io.apitomy.axiom.events.github.v2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link GitHubConnectionPoller}.
 */
class GitHubConnectionPollerTest {

    private final GitHubConnectionPoller poller = new GitHubConnectionPoller();

    @Test
    void deriveApiBaseUrl_publicGitHub() {
        assertEquals("https://api.github.com",
                poller.deriveApiBaseUrl("https://github.com"));
    }

    @Test
    void deriveApiBaseUrl_publicGitHubWithTrailingSlash() {
        assertEquals("https://api.github.com",
                poller.deriveApiBaseUrl("https://github.com/"));
    }

    @Test
    void deriveApiBaseUrl_gheInstance() {
        assertEquals("https://github.example.com/api/v3",
                poller.deriveApiBaseUrl("https://github.example.com"));
    }

    @Test
    void deriveApiBaseUrl_gheInstanceAlreadyHasApiPath() {
        assertEquals("https://github.example.com/api/v3",
                poller.deriveApiBaseUrl("https://github.example.com/api/v3"));
    }

    @Test
    void deriveApiBaseUrl_null() {
        assertEquals("https://api.github.com",
                poller.deriveApiBaseUrl(null));
    }
}
