package io.apitomy.axiom.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the execution-log model annotation helpers in {@link TaskExecutionService} (#524).
 */
class TaskModelAnnotationTest {

    @Test
    void aliasMatchingActualModelIsNotAMismatch() {
        assertFalse(TaskExecutionService.isModelMismatch("haiku", "claude-haiku-4-5-20251001"));
        assertFalse(TaskExecutionService.isModelMismatch("github-copilot/gpt-5", "github-copilot/gpt-5"));
        assertFalse(TaskExecutionService.isModelMismatch(null, "claude-opus-5"));
    }

    @Test
    void differentModelIsAMismatch() {
        assertTrue(TaskExecutionService.isModelMismatch("haiku", "claude-opus-5"));
    }

    @Test
    void appendsModelSectionWithMismatchNote() {
        String log = TaskExecutionService.appendModelSection(
                "=== Stderr ===\nWarning: Opus 5.5 not available", "haiku", "claude-opus-5");

        assertTrue(log.startsWith("=== Stderr ==="));
        assertTrue(log.contains("=== Model ===\nRequested: haiku\nActual: claude-opus-5\n"));
        assertTrue(log.contains("Note: the engine ran a different model than requested"));
    }

    @Test
    void appendsModelSectionWithoutNoteWhenModelsMatch() {
        String log = TaskExecutionService.appendModelSection(null, "haiku", "claude-haiku-4-5");

        assertTrue(log.startsWith("=== Model ===\nRequested: haiku\nActual: claude-haiku-4-5\n"));
        assertFalse(log.contains("Note:"));
    }
}
