package io.apitomy.axiom.events.github.v2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "https://github.com/EricWittmann/cb-test-project/issues/152,        EricWittmann/cb-test-project",
        "https://github.com/o/r/pull/7,                                     o/r",
        "https://github.com/o/r/commit/abc123,                              o/r",
        "https://github.com/o/r/tree/main,                                  o/r",
        "https://github.com/o/r/releases/tag/v1.0,                          o/r",
        "https://github.com/o/r,                                            o/r",
        "https://github.com/o/r/,                                           o/r",
        "https://github.ibm.com/Apicurio/apicurio-csd/issues/3,             Apicurio/apicurio-csd",
        "https://example.com/github/team/repo/issues/9,                     team/repo",
    })
    void repositoryFromRef(String ref, String repository) {
        assertEquals(repository, GitHubUrls.repositoryFromRef(ref).orElseThrow());
    }

    @Test
    void repositoryFromRefIsEmptyWhenUnknown() {
        assertTrue(GitHubUrls.repositoryFromRef(null).isEmpty());
        assertTrue(GitHubUrls.repositoryFromRef("https://github.com").isEmpty());
        assertTrue(GitHubUrls.repositoryFromRef("https://github.com/only-owner").isEmpty());
        assertTrue(GitHubUrls.repositoryFromRef("not a url").isEmpty());
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
