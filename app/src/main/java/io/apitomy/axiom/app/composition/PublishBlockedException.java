package io.apitomy.axiom.app.composition;

import io.apitomy.axiom.app.validation.ConfigProblem;

import java.util.List;
import java.util.Map;

/** Publishing was refused because selected items have errors or missing dependencies (HTTP 422). */
public class PublishBlockedException extends RuntimeException {
    private final Map<String, List<ConfigProblem>> problems;

    /**
     * @param problems problems keyed by {@code "<type>/<name>"}
     */
    public PublishBlockedException(Map<String, List<ConfigProblem>> problems) {
        super("Cannot publish: " + problems.keySet());
        this.problems = problems;
    }

    /**
     * @return problems keyed by {@code "<type>/<name>"}
     */
    public Map<String, List<ConfigProblem>> problems() {
        return problems;
    }
}
