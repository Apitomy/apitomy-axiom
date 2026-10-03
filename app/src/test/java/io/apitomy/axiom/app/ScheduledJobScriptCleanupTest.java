package io.apitomy.axiom.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies that temp file cleanup after a script run never throws, so it cannot turn a completed
 * run into a failed one or complete the run's trace twice.
 */
class ScheduledJobScriptCleanupTest {

    @Test
    void deleteQuietlySwallowsIoErrors(@TempDir Path dir) throws IOException {
        Path nonEmpty = Files.createDirectory(dir.resolve("non-empty"));
        Files.writeString(nonEmpty.resolve("child.txt"), "x");

        assertDoesNotThrow(() -> ScheduledJobExecutionService.deleteQuietly(nonEmpty));
    }

    @Test
    void deleteQuietlyDeletesFiles(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("script.sh"), "echo hi");

        ScheduledJobExecutionService.deleteQuietly(file);

        assertFalse(Files.exists(file));
    }
}
