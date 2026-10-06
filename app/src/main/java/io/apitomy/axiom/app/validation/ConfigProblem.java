package io.apitomy.axiom.app.validation;

/**
 * One validation finding.
 *
 * @param severity "error" or "warning"
 * @param path     JSON path of the offending value, e.g. {@code content.edges[2].source}; "" for the item root
 * @param code     stable machine-readable code, e.g. {@code WORKFLOW_PARSE_ERROR}
 * @param message  human-readable explanation including how to fix it
 */
public record ConfigProblem(String severity, String path, String code, String message) {

    /** Severity value for errors. */
    public static final String ERROR = "error";
    /** Severity value for warnings. */
    public static final String WARNING = "warning";

    /** @return a new error problem */
    public static ConfigProblem error(String path, String code, String message) {
        return new ConfigProblem(ERROR, path, code, message);
    }

    /** @return a new warning problem */
    public static ConfigProblem warning(String path, String code, String message) {
        return new ConfigProblem(WARNING, path, code, message);
    }

    /** @return true if this is an error */
    public boolean isError() {
        return ERROR.equals(severity);
    }
}
