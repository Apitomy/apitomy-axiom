package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.beans.Error;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * 404 errors of the configuration snapshot endpoints (#426), with a JSON {@link Error} body so
 * clients can tell "run/report not found" from "no configuration recorded".
 */
final class ConfigSnapshotErrors {

    /** Message prefix used when the run or report exists but has no snapshot. */
    static final String NOT_RECORDED = "No configuration was recorded for ";

    private ConfigSnapshotErrors() {
    }

    /**
     * Builds a 404 exception with a JSON error body.
     *
     * @param message the error message
     * @return the exception to throw
     */
    static WebApplicationException notFound(String message) {
        Error error = new Error();
        error.setMessage(message);
        error.setErrorCode(404);
        return new WebApplicationException(message, Response.status(404)
                .type(MediaType.APPLICATION_JSON_TYPE).entity(error).build());
    }
}
