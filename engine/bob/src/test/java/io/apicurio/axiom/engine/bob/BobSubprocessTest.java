package io.apicurio.axiom.engine.bob;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that actually launch the IBM Bob CLI.
 *
 * <p>These tests are <strong>disabled by default</strong>. To enable them, set the
 * environment variable {@code AXIOM_BOB_TESTS=true} before running:</p>
 *
 * <pre>
 * AXIOM_BOB_TESTS=true mvn test -pl engine/bob
 * </pre>
 *
 * <p>Requirements:</p>
 * <ul>
 *   <li>The {@code bob} CLI must be installed and on the PATH</li>
 *   <li>A valid {@code BOBSHELL_API_KEY} must be set in the environment</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "AXIOM_BOB_TESTS", matches = "true")
class BobSubprocessTest {

    @TempDir
    Path tempDir;

    @Test
    void testSimplePrompt() throws ExecutionException, InterruptedException, TimeoutException {
        List<String> cmd = BobCommandBuilder.create("Reply with exactly: HELLO AXIOM")
                .yolo(false)
                .build();

        BobSubprocess subprocess = new BobSubprocess(
                cmd, tempDir.toFile(), Map.of(), Duration.ofSeconds(60));

        BobResult result = subprocess.execute()
                .get(90, TimeUnit.SECONDS);

        assertTrue(result.isSuccess(), "Expected success but got exit code " + result.exitCode());
        assertNotNull(result.result(), "Result text should not be null");
        assertTrue(result.result().contains("HELLO AXIOM"),
                "Expected result to contain 'HELLO AXIOM' but got: " + result.result());
    }

    @Test
    void testTimeoutEnforcement() throws ExecutionException, InterruptedException, TimeoutException {
        List<String> cmd = BobCommandBuilder
                .create("Write a 5000 word essay about the history of computing")
                .yolo(false)
                .build();

        BobSubprocess subprocess = new BobSubprocess(
                cmd, tempDir.toFile(), Map.of(), Duration.ofSeconds(5));

        BobResult result = subprocess.execute()
                .get(30, TimeUnit.SECONDS);

        assertFalse(result.isSuccess(), "Should have timed out");
        assertEquals(124, result.exitCode(), "Timeout exit code should be 124");
    }

    @Test
    void testCancellation() throws InterruptedException {
        List<String> cmd = BobCommandBuilder
                .create("Write a very long essay about every programming language ever created")
                .yolo(false)
                .build();

        BobSubprocess subprocess = new BobSubprocess(
                cmd, tempDir.toFile(), Map.of(), Duration.ofSeconds(120));

        var future = subprocess.execute();

        Thread.sleep(3000);
        subprocess.kill();

        BobResult result = future.join();
        assertFalse(result.isSuccess(), "Cancelled task should not be successful");
    }
}
