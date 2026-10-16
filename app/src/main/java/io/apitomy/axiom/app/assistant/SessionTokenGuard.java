package io.apitomy.axiom.app.assistant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;

/**
 * The single check used by every REST route that only a session's own MCP server may call. The token keeps
 * sessions apart and stops casual local callers. It does not protect Axiom from the agent itself, which can
 * read its own token from its environment.
 */
@ApplicationScoped
public class SessionTokenGuard {

    /** Request header carrying the session's MCP token. */
    public static final String HEADER = "X-Axiom-Session-Token";

    /**
     * Requires the presented token to match the session's token.
     *
     * @param session the live session the route belongs to, or null if none is running
     * @param presented the header value, possibly null
     * @throws WebApplicationException with status 401 if the session is missing or the token does not match
     */
    public void require(AssistantSession session, String presented) {
        if (session == null || !session.verifyMcpToken(presented)) {
            throw new WebApplicationException("Missing or invalid " + HEADER, 401);
        }
    }
}
