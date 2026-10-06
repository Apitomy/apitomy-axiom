package io.apitomy.axiom.app;

import java.util.Map;

/**
 * Optional parameters for starting a workflow run.
 *
 * @param title        run title; null to use "&lt;definition name&gt; — &lt;subject name&gt;"
 * @param labels       run labels; null for none
 * @param inputs       workflow inputs (validated against the Start node input contract); null for none
 * @param extraContext additional context entries (e.g. the triggering event); null for none
 */
public record RunRequest(String title, Map<String, String> labels, Map<String, Object> inputs,
                         Map<String, Object> extraContext) {

    /** An empty request. */
    public static final RunRequest EMPTY = new RunRequest(null, null, null, null);
}
