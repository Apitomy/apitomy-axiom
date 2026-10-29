package io.apitomy.axiom.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Helpers for workflow run labels: free-form strings, stored like subject labels. Built-in labels use the
 * {@code key:value} form ({@code trigger:manual}, {@code trigger:schedule}, {@code rerunOf:<id>}).
 */
public final class RunLabels {

    /** Maximum length of a single label (the database column size). */
    public static final int MAX_LENGTH = 255;

    /** Label of a run started manually from a stream event. */
    public static final String TRIGGER_MANUAL = "trigger:manual";

    /** Label of a run started by a schedule trigger. */
    public static final String TRIGGER_SCHEDULE = "trigger:schedule";

    private RunLabels() {
    }

    /**
     * Normalizes labels: trims them, drops null and blank ones and removes duplicates, keeping the first
     * occurrence's position.
     *
     * @param labels the labels; may be null
     * @return the normalized labels, never null
     */
    public static List<String> normalize(Collection<String> labels) {
        if (labels == null) {
            return new ArrayList<>();
        }
        Set<String> result = new LinkedHashSet<>();
        labels.stream()
                .filter(label -> label != null && !label.isBlank())
                .map(String::trim)
                .forEach(result::add);
        return new ArrayList<>(result);
    }

    /**
     * Returns the labels with every {@code key:...} label replaced by a single {@code key:value} label at the end.
     *
     * @param labels the labels; may be null
     * @param key    the label key, e.g. {@code trigger}
     * @param value  the label value, e.g. {@code manual}
     * @return the normalized labels with the keyed label set
     */
    public static List<String> withKeyed(Collection<String> labels, String key, String value) {
        String prefix = key + ":";
        List<String> result = new ArrayList<>(normalize(labels).stream()
                .filter(label -> !label.startsWith(prefix))
                .toList());
        result.add(prefix + value);
        return result;
    }

    /**
     * Returns the first label that is longer than {@link #MAX_LENGTH}, if any.
     *
     * @param labels the labels; may be null
     * @return the too-long label, or null when all labels fit
     */
    public static String findTooLong(Collection<String> labels) {
        if (labels == null) {
            return null;
        }
        return labels.stream()
                .filter(label -> label != null && label.trim().length() > MAX_LENGTH)
                .findFirst()
                .orElse(null);
    }
}
