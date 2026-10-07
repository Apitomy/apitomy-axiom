package io.apitomy.axiom.events.github.v2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitHubUrlsTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        // GitHub.com in its many spellings
        "https://github.com,                                   https://github.com,           https://api.github.com",
        "https://github.com/,                                  https://github.com,           https://api.github.com",
        "https://github.com//,                                 https://github.com,           https://api.github.com",
        "https://www.github.com,                               https://github.com,           https://api.github.com",
        "https://api.github.com,                               https://github.com,           https://api.github.com",
        "https://api.github.com/,                              https://github.com,           https://api.github.com",
        "HTTPS://GitHub.COM,                                   https://github.com,           https://api.github.com",
        "http://github.com,                                    https://github.com,           https://api.github.com",
        "github.com,                                           https://github.com,           https://api.github.com",
        "https://github.com/Apicurio/apicurio-registry,        https://github.com,           https://api.github.com",
        "https://github.com/Apicurio/apicurio-registry/issues?q=x#top, https://github.com,   https://api.github.com",
        // GitHub Enterprise Server
        "https://github.ibm.com/,                              https://github.ibm.com,       https://github.ibm.com/api/v3",
        "https://github.ibm.com,                               https://github.ibm.com,       https://github.ibm.com/api/v3",
        "https://github.ibm.com/Apicurio/apicurio-csd,         https://github.ibm.com,       https://github.ibm.com/api/v3",
        "github.ibm.com,                                       https://github.ibm.com,       https://github.ibm.com/api/v3",
        "https://GitHub.IBM.com/,                              https://github.ibm.com,       https://github.ibm.com/api/v3",
        "https://github.ibm.com/api/v3,                        https://github.ibm.com,       https://github.ibm.com/api/v3",
        "https://github.ibm.com/api/v3/,                       https://github.ibm.com,       https://github.ibm.com/api/v3",
        "https://github.ibm.com/api/v3/repos/o/r,              https://github.ibm.com,       https://github.ibm.com/api/v3",
        "https://ghe.example.com:8443/api/v3,                  https://ghe.example.com:8443, https://ghe.example.com:8443/api/v3",
        "https://example.com/github/api/v3,                    https://example.com/github,   https://example.com/github/api/v3",
        "http://ghe.internal,                                  http://ghe.internal,          http://ghe.internal/api/v3",
        // GitHub Enterprise Cloud with data residency
        "https://octocorp.ghe.com,                             https://octocorp.ghe.com,     https://api.octocorp.ghe.com",
        "https://api.octocorp.ghe.com/,                        https://octocorp.ghe.com,     https://api.octocorp.ghe.com",
        "https://octocorp.ghe.com/team/repo,                   https://octocorp.ghe.com,     https://api.octocorp.ghe.com",
    })
    void resolves(String input, String web, String api) {
        GitHubUrls urls = GitHubUrls.resolve(input);
        assertEquals(web, urls.webBaseUrl(), "web");
        assertEquals(api, urls.apiBaseUrl(), "api");
    }

    @Test
    void nullOrBlankDefaultsToGitHubCom() {
        assertEquals("https://api.github.com", GitHubUrls.resolve(null).apiBaseUrl());
        assertEquals("https://github.com", GitHubUrls.resolve("  ").webBaseUrl());
    }

    @Test
    void unparseableValueIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GitHubUrls.resolve("https://exa mple.com"));
        assertThrows(IllegalArgumentException.class, () -> GitHubUrls.resolve("ftp://github.com"));
        assertThrows(IllegalArgumentException.class, () -> GitHubUrls.resolve("https://"));
    }
}
