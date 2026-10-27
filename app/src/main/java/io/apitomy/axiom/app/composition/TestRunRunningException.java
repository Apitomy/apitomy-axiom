package io.apitomy.axiom.app.composition;

/**
 * A test run cannot be removed because it is still running.
 */
public class TestRunRunningException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param runId the id of the running test run
     */
    public TestRunRunningException(String runId) {
        super("Test run " + runId + " is still running; remove it when it finishes.");
    }
}
