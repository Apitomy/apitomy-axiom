package io.apitomy.axiom.app.assistant;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the last validation feedback sent to an assistant session for each item file, so identical feedback
 * for an unchanged invalid file is not re-sent after every tool call.
 */
public final class ValidationFeedbackTracker {

    private final Map<Path, String> lastFeedback = new ConcurrentHashMap<>();

    /**
     * Returns whether the given feedback should be sent for the file. Returns {@code true} (and records the
     * feedback) only if it differs from the feedback last recorded for the file.
     *
     * @param file the validated item file
     * @param feedback the feedback text
     * @return {@code true} if the feedback is new for this file
     */
    public boolean shouldSend(Path file, String feedback) {
        String previous = lastFeedback.put(file, feedback);
        return !Objects.equals(previous, feedback);
    }

    /**
     * Forgets any recorded feedback for the file, so future feedback for it is sent again.
     *
     * @param file the item file that is now valid
     */
    public void markValid(Path file) {
        lastFeedback.remove(file);
    }
}
