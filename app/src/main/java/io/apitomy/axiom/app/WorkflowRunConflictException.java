package io.apitomy.axiom.app;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Thrown when a workflow run is requested for a (subject, definition) pair that already has an active run.
 */
public class WorkflowRunConflictException extends WebApplicationException {

    /**
     * Creates the exception.
     *
     * @param subjectId    the subject id
     * @param definitionId the workflow definition id
     */
    public WorkflowRunConflictException(long subjectId, long definitionId) {
        super(Response.status(409)
                .entity(Map.of("message", "Subject " + subjectId
                        + " already has an active run of workflow definition " + definitionId))
                .build());
    }
}
