package io.apitomy.axiom.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class GlobMatcherTest {

    @ParameterizedTest
    @CsvSource({
        // Global wildcard
        "*, action:auto-tag, true",
        "*, report:daily-github-activity, true",
        "*, job:test-job, true",

        // Flow-level wildcards
        "action:*, action:auto-tag, true",
        "action:*, action:git-label, true",
        "report:*, report:daily-github-activity, true",
        "job:*, job:nightly-cleanup, true",

        // Exact matches
        "action:auto-tag, action:auto-tag, true",
        "report:daily-github-activity, report:daily-github-activity, true",
        "job:test-job, job:test-job, true",

        // Prefix wildcards
        "action:git-*, action:git-label, true",
        "action:git-*, action:git-triage, true",
        "action:git-*, action:auto-tag, false",
        "report:daily-*, report:daily-github-activity, true",
        "report:daily-*, report:weekly-summary, false",
        "job:github-*, job:github-sync, true",
        "job:github-*, job:nightly-cleanup, false",

        // Case insensitivity
        "ACTION:AUTO-TAG, action:auto-tag, true",
        "action:Auto-Tag, action:auto-tag, true",

        // No match
        "action:auto-tag, action:git-label, false",
        "report:*, action:auto-tag, false",
        "job:*, report:daily, false",

        // Wildcard in middle
        "action:auto-*-issues, action:auto-tag-issues, true",
        "action:auto-*-issues, action:auto-label-issues, true",
        "action:auto-*-issues, action:auto-tag, false",
    })
    void testMatches(String pattern, String value, boolean expected) {
        assertEquals(expected, GlobMatcher.matches(pattern, value));
    }

    @Test
    void testNullAndEmptyInputs() {
        assertFalse(GlobMatcher.matches(null, "action:auto-tag"));
        assertFalse(GlobMatcher.matches("action:*", null));
        assertFalse(GlobMatcher.matches(null, null));
        assertFalse(GlobMatcher.matches("", "action:auto-tag"));
        assertFalse(GlobMatcher.matches("action:*", ""));
    }

    @Test
    void testAnyCapabilityMatches() {
        var capabilities = java.util.List.of("report:*", "action:auto-tag");
        assertTrue(GlobMatcher.anyMatches(capabilities, "report:daily-github"));
        assertTrue(GlobMatcher.anyMatches(capabilities, "action:auto-tag"));
        assertFalse(GlobMatcher.anyMatches(capabilities, "action:git-label"));
        assertFalse(GlobMatcher.anyMatches(capabilities, "job:nightly"));
    }
}
