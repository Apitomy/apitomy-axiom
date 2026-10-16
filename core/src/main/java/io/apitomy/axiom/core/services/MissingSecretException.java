package io.apitomy.axiom.core.services;

/**
 * Thrown when an environment references a secret ({@code ${secret:NAME}}) that does not exist or cannot be
 * decrypted. The process that needed it must not start.
 */
public class MissingSecretException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message the message, naming the secret
     */
    public MissingSecretException(String message) {
        super(message);
    }
}
