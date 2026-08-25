package io.apitomy.axiom.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class SlugUtilTest {

    @ParameterizedTest
    @CsvSource({
        "Daily GitHub Activity, daily-github-activity",
        "auto-tag, auto-tag",
        "Test Scheduled Job, test-scheduled-job",
        "My Report!!, my-report",
        "  Spaces  Everywhere  , spaces-everywhere",
        "UPPER CASE NAME, upper-case-name",
        "already-a-slug, already-a-slug",
        "multiple---dashes, multiple-dashes",
    })
    void testSlugify(String input, String expected) {
        assertEquals(expected, SlugUtil.slugify(input));
    }

    @Test
    void testNullAndEmpty() {
        assertNull(SlugUtil.slugify(null));
        assertEquals("", SlugUtil.slugify(""));
    }
}
