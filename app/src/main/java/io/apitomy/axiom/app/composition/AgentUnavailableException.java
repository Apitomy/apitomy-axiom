package io.apitomy.axiom.app.composition;

/**
 * The workbench agent could not be started or could not be sent a message.
 */
public class AgentUnavailableException extends RuntimeException {

    /**
     * @param message the message
     * @param cause the cause
     */
    public AgentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
