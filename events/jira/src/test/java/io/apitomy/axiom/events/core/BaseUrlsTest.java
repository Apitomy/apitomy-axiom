package io.apitomy.axiom.events.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BaseUrlsTest {

    @Test
    void normalize_trimsAndStripsTrailingSlashes() {
        assertEquals("https://x.atlassian.net", BaseUrls.normalize("https://x.atlassian.net/"));
        assertEquals("https://x.atlassian.net", BaseUrls.normalize("https://x.atlassian.net//"));
        assertEquals("https://x.atlassian.net", BaseUrls.normalize(" https://x.atlassian.net "));
        assertEquals("https://x.atlassian.net", BaseUrls.normalize("https://x.atlassian.net"));
        assertNull(BaseUrls.normalize(null));
    }
}
