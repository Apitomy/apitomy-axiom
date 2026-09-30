package io.apitomy.axiom.app.assistant;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationFeedbackTrackerTest {

    private final Path fileA = Path.of("tools", "a.json");
    private final Path fileB = Path.of("tools", "b.json");

    @Test
    void firstFeedbackIsSent() {
        ValidationFeedbackTracker tracker = new ValidationFeedbackTracker();
        assertTrue(tracker.shouldSend(fileA, "err1"));
    }

    @Test
    void identicalFeedbackIsSuppressed() {
        ValidationFeedbackTracker tracker = new ValidationFeedbackTracker();
        assertTrue(tracker.shouldSend(fileA, "err1"));
        assertFalse(tracker.shouldSend(fileA, "err1"));
    }

    @Test
    void changedFeedbackIsSent() {
        ValidationFeedbackTracker tracker = new ValidationFeedbackTracker();
        assertTrue(tracker.shouldSend(fileA, "err1"));
        assertTrue(tracker.shouldSend(fileA, "err2"));
        assertFalse(tracker.shouldSend(fileA, "err2"));
    }

    @Test
    void markValidResetsFile() {
        ValidationFeedbackTracker tracker = new ValidationFeedbackTracker();
        assertTrue(tracker.shouldSend(fileA, "err1"));
        tracker.markValid(fileA);
        assertTrue(tracker.shouldSend(fileA, "err1"));
    }

    @Test
    void filesAreIndependent() {
        ValidationFeedbackTracker tracker = new ValidationFeedbackTracker();
        assertTrue(tracker.shouldSend(fileA, "err1"));
        assertTrue(tracker.shouldSend(fileB, "err1"));
        tracker.markValid(fileB);
        assertFalse(tracker.shouldSend(fileA, "err1"));
    }
}
