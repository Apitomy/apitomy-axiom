package io.apitomy.axiom.events.jira.v2;

/**
 * Builds the {@code email:api_token} string used for HTTP Basic authentication against Jira Cloud.
 *
 * <p>The email and the API token are configured separately: the token comes from the event source's
 * authentication secret and the email from {@code configuration.email}. For backward compatibility, when
 * no email is configured a token value that already has the {@code email:api_token} form is used as is.</p>
 */
public final class JiraCredentials {

    private JiraCredentials() {
    }

    /**
     * Combines a Jira account email and API token into Basic-auth credentials.
     *
     * @param email the Jira account email, or {@code null}/blank when not configured
     * @param token the API token (or a legacy {@code email:api_token} value)
     * @return the {@code email:api_token} credentials
     * @throws JiraCredentialsException when the token is missing, or when no email is configured and the
     *                                  token does not contain one
     */
    public static String combine(String email, String token) {
        if (token == null || token.isBlank()) {
            throw new JiraCredentialsException("No Jira API token configured: set the event source's "
                    + "authentication secret (or the default JIRA_API_TOKEN secret)");
        }
        String trimmedToken = token.trim();
        if (email != null && !email.isBlank()) {
            return email.trim() + ":" + trimmedToken;
        }
        if (trimmedToken.contains(":")) {
            return trimmedToken;
        }
        throw new JiraCredentialsException("Jira email not configured: set the event source's Jira email "
                + "(plain text or a secret) so it can be combined with the API token");
    }

    /** Thrown when Jira credentials cannot be built from the configured values. */
    public static class JiraCredentialsException extends RuntimeException {

        /**
         * Creates the exception.
         *
         * @param message the reason, suitable for the poll log
         */
        public JiraCredentialsException(String message) {
            super(message);
        }
    }
}
