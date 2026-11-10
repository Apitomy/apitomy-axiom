package io.apitomy.axiom.events.jira.v2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JiraCredentials}.
 */
class JiraCredentialsTest {

    @Test
    void emailAndToken_areCombined() {
        assertEquals("user@example.com:abc123", JiraCredentials.combine("user@example.com", "abc123"));
    }

    @Test
    void emailAndToken_areTrimmed() {
        assertEquals("user@example.com:abc123", JiraCredentials.combine("  user@example.com\n", " abc123\n"));
    }

    @Test
    void noEmail_legacyCombinedToken_isUsedAsIs() {
        assertEquals("user@example.com:abc123", JiraCredentials.combine(null, "user@example.com:abc123"));
        assertEquals("user@example.com:abc123", JiraCredentials.combine("  ", "user@example.com:abc123"));
    }

    @Test
    void noEmail_plainToken_fails() {
        JiraCredentials.JiraCredentialsException e = assertThrows(JiraCredentials.JiraCredentialsException.class,
                () -> JiraCredentials.combine(null, "abc123"));
        assertTrue(e.getMessage().contains("email"), e.getMessage());
    }

    @Test
    void noToken_fails() {
        assertThrows(JiraCredentials.JiraCredentialsException.class,
                () -> JiraCredentials.combine("user@example.com", null));
        assertThrows(JiraCredentials.JiraCredentialsException.class,
                () -> JiraCredentials.combine("user@example.com", " "));
    }
}
