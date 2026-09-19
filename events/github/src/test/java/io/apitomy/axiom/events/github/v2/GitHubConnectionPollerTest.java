package io.apitomy.axiom.events.github.v2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link GitHubConnectionPoller}.
 */
class GitHubConnectionPollerTest {

    private final GitHubConnectionPoller poller = new GitHubConnectionPoller();

    @Test
    void deriveHtmlBaseUrl_publicGitHub() {
        assertEquals("https://github.com",
                poller.deriveHtmlBaseUrl("https://api.github.com"));
    }

    @Test
    void deriveHtmlBaseUrl_gheInstance() {
        assertEquals("https://github.example.com",
                poller.deriveHtmlBaseUrl("https://github.example.com/api/v3"));
    }

    @Test
    void deriveHtmlBaseUrl_null() {
        assertEquals("https://github.com",
                poller.deriveHtmlBaseUrl(null));
    }
}
