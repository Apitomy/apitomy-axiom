package io.apitomy.axiom.app;

import java.util.List;

/**
 * Optional parameters for starting a workflow run. A run's inputs are not supplied: Axiom resolves the standard
 * inputs its Start node declares (see {@link StandardInputs}).
 *
 * @param title  run title; null for none
 * @param labels run labels; null for none
 */
public record RunRequest(String title, List<String> labels) {

    /** An empty request. */
    public static final RunRequest EMPTY = new RunRequest(null, null);
}
