package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.beans.ToolValidationMessage;
import io.apitomy.axiom.api.beans.ToolValidationResult;
import io.apitomy.axiom.app.validation.ConfigItemValidationService;
import io.apitomy.axiom.app.validation.ConfigProblem;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * Maps {@link ConfigProblem}s produced by {@link ConfigItemValidationService} to the REST validation shapes.
 */
final class RestValidationMapper {

    private RestValidationMapper() {
    }

    /**
     * Converts problems to the {@code ToolValidationResult} response bean.
     *
     * @param problems the problems
     * @return the response bean
     */
    static ToolValidationResult toResult(List<ConfigProblem> problems) {
        ToolValidationResult response = new ToolValidationResult();
        response.setValid(!ConfigItemValidationService.hasErrors(problems));
        response.setMessages(problems.stream().map(p -> {
            ToolValidationMessage msg = new ToolValidationMessage();
            msg.setSeverity(p.isError() ? ToolValidationMessage.Severity.ERROR
                    : ToolValidationMessage.Severity.WARNING);
            msg.setField(p.path());
            msg.setMessage(p.message());
            return msg;
        }).toList());
        return response;
    }

    /**
     * Throws a 422 response listing errors and warnings if any problem is an error.
     *
     * @param problems the problems
     * @param message  the top-level message of the 422 body
     */
    static void throwIfErrors(List<ConfigProblem> problems, String message) {
        if (!ConfigItemValidationService.hasErrors(problems)) {
            return;
        }
        List<Map<String, String>> errors = problems.stream().filter(ConfigProblem::isError)
                .map(RestValidationMapper::toEntry).toList();
        List<Map<String, String>> warnings = problems.stream().filter(p -> !p.isError())
                .map(RestValidationMapper::toEntry).toList();
        throw new WebApplicationException(Response.status(422).entity(Map.of(
                "message", message,
                "errors", errors,
                "warnings", warnings)).build());
    }

    private static Map<String, String> toEntry(ConfigProblem problem) {
        return Map.of("field", problem.path(), "message", problem.message());
    }
}
