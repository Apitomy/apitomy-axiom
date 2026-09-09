package io.apitomy.axiom.app.assistant.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.apitomy.axiom.app.assistant.AssistantSession;

import java.io.IOException;

/**
 * Runtime driver abstraction for interactive Assistant sessions.
 */
public interface InteractiveSessionDriver {

    /**
     * Starts the runtime for this interactive session.
     *
     * @throws IOException if startup fails
     */
    void start() throws IOException;

    /**
     * Sends a user message to the interactive runtime.
     *
     * @param message user message content
     * @throws IOException if the message cannot be delivered
     */
    void sendUserMessage(String message) throws IOException;

    /**
     * Responds to a pending permission request.
     *
     * @param permissionId permission request identifier
     * @param allow true to approve the request; false to deny
     * @param toolInput optional tool input payload
     * @throws IOException if the response cannot be delivered
     */
    void respondToPermission(String permissionId, boolean allow, JsonNode toolInput)
            throws IOException;

    /**
     * Interrupts current runtime activity while keeping the session alive.
     */
    void interrupt();

    /**
     * Destroys the runtime for this interactive session.
     */
    void destroy();

    /**
     * Returns whether the runtime is alive.
     *
     * @return true when the runtime is active
     */
    boolean isAlive();

    /**
     * Returns current session status.
     *
     * @return current status
     */
    AssistantSession.Status getStatus();

    /**
     * Returns the current runtime error message, if present.
     *
     * @return error message or null
     */
    String getErrorMessage();
}
