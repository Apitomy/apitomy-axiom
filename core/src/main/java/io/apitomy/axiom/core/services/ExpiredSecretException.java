package io.apitomy.axiom.core.services;

import java.time.Instant;

/**
 * Thrown when an environment references a secret whose expiration has passed. Extends
 * {@link MissingSecretException} so every existing handler treats it the same way: the process that needed
 * the secret must not start.
 */
public class ExpiredSecretException extends MissingSecretException {

    /**
     * Creates the exception.
     *
     * @param secretName the expired secret's name
     * @param expiredOn  when it expired
     */
    public ExpiredSecretException(String secretName, Instant expiredOn) {
        super("Secret '" + secretName + "' expired on " + expiredOn + ". Update it on the Secrets page.");
    }
}
