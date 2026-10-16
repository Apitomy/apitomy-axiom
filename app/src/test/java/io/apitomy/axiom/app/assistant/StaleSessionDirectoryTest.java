package io.apitomy.axiom.app.assistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that startup cleanup only treats session directories with no recent activity as stale.
 */
class StaleSessionDirectoryTest {

    private static final Duration GRACE = Duration.ofMinutes(10);

    @Test
    void recentlyWrittenNestedFileKeepsDirectory(@TempDir Path dir) throws IOException {
        Instant now = Instant.now();
        Path session = Files.createDirectories(dir.resolve("s1"));
        Path item = Files.createDirectories(session.resolve("workDir/action-types")).resolve("a.json");
        Files.writeString(item, "{}");
        setAll(session, now.minus(Duration.ofHours(2)));
        Files.setLastModifiedTime(item, FileTime.from(now.minus(Duration.ofMinutes(1))));

        assertFalse(AssistantSessionManager.isStale(session, now, GRACE));
    }

    @Test
    void directoryWithOnlyOldFilesIsStale(@TempDir Path dir) throws IOException {
        Instant now = Instant.now();
        Path session = Files.createDirectories(dir.resolve("s2/workDir"));
        Files.writeString(session.resolve("x.json"), "{}");
        setAll(dir.resolve("s2"), now.minus(Duration.ofHours(2)));

        assertTrue(AssistantSessionManager.isStale(dir.resolve("s2"), now, GRACE));
    }

    private static void setAll(Path root, Instant time) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path p : paths.toList()) {
                Files.setLastModifiedTime(p, FileTime.from(time));
            }
        }
    }
}
