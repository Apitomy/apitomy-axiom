package io.apitomy.axiom.app.validation;

import java.util.List;
import java.util.Map;

/** Thrown when a configuration pack contains invalid items; nothing has been written. */
public class ConfigValidationException extends RuntimeException {

    private final Map<String, List<ConfigProblem>> problemsByItem;

    /**
     * @param problemsByItem problems keyed by "&lt;type&gt;/&lt;name&gt;"
     */
    public ConfigValidationException(Map<String, List<ConfigProblem>> problemsByItem) {
        super("Configuration pack has invalid items: " + problemsByItem.keySet());
        this.problemsByItem = problemsByItem;
    }

    /**
     * @return problems keyed by "&lt;type&gt;/&lt;name&gt;"
     */
    public Map<String, List<ConfigProblem>> problemsByItem() {
        return problemsByItem;
    }
}
