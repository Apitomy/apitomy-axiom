package io.apitomy.axiom.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how script output is captured and how a script's outputs are extracted from stdout.
 */
class ScriptOutputTest {

    @Test
    void singleLineObjectIsTheOutput() {
        assertEquals("{\"a\":1}", ScriptExecutionService.extractScriptOutput("{\"a\":1}\n"));
    }

    @Test
    void prettyPrintedObjectAfterLogLinesIsTheOutput() {
        String stdout = """
                fetching issue 42
                found {3} labels
                {
                  "issueTitle": "Bug",
                  "labels": {"count": 3}
                }
                """;
        assertEquals("""
                {
                  "issueTitle": "Bug",
                  "labels": {"count": 3}
                }""", ScriptExecutionService.extractScriptOutput(stdout));
    }

    @Test
    void lastObjectWinsWhenSeveralArePrinted() {
        String stdout = "{\"debug\":true}\n{\"result\":\"ok\"}\n";
        assertEquals("{\"result\":\"ok\"}", ScriptExecutionService.extractScriptOutput(stdout));
    }

    @Test
    void outputWithoutJsonObjectIsReturnedUnchanged() {
        assertEquals("just text\n", ScriptExecutionService.extractScriptOutput("just text\n"));
        assertEquals("[1,2]", ScriptExecutionService.extractScriptOutput("[1,2]"));
        assertEquals("", ScriptExecutionService.extractScriptOutput(""));
    }

    @Test
    void trailingTextAfterObjectMeansNoObjectIsFinal() {
        String stdout = "{\"a\":1}\ndone\n";
        assertEquals(stdout, ScriptExecutionService.extractScriptOutput(stdout));
    }

    @Test
    void stdoutAndStderrAreCapturedSeparately() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("/bin/bash", "-c",
                "echo 'warning: {oops}' >&2; jq -n '{issueTitle: \"Bug\"}'").start();
        ScriptExecutionService.CapturedOutput captured = ScriptExecutionService.capture(process, 30);

        assertTrue(captured.finished());
        assertEquals(0, captured.exitCode());
        assertFalse(captured.stdout().contains("oops"), captured.stdout());
        assertTrue(captured.stderr().contains("warning: {oops}"), captured.stderr());
        assertEquals("{\n  \"issueTitle\": \"Bug\"\n}",
                ScriptExecutionService.extractScriptOutput(captured.stdout()));
    }

    @Test
    void largeStderrDoesNotDeadlock() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("/bin/bash", "-c",
                "head -c 2000000 /dev/zero | tr '\\0' 'x' >&2; echo '{\"ok\":true}'").start();
        ScriptExecutionService.CapturedOutput captured = ScriptExecutionService.capture(process, 30);

        assertTrue(captured.finished());
        assertEquals("{\"ok\":true}", ScriptExecutionService.extractScriptOutput(captured.stdout()));
        // Fully drained, but only 1 MiB (head and tail) is kept.
        assertTrue(captured.stderrTruncated());
        assertTrue(captured.stderr().startsWith("xxx"));
        assertTrue(captured.stderr().contains(io.apitomy.axiom.agents.spi.process.BoundedProcessRunner.TRUNCATION_MARKER));
    }

    @Test
    void timeoutIsReported() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("/bin/bash", "-c", "echo start; sleep 30").start();
        ScriptExecutionService.CapturedOutput captured = ScriptExecutionService.capture(process, 1);

        assertFalse(captured.finished());
        assertEquals(1, captured.exitCode());
    }

    @Test
    void timeoutAppliesWhenChildKeepsStdoutOpen() throws IOException, InterruptedException {
        long start = System.nanoTime();
        Process process = new ProcessBuilder("/bin/bash", "-c", "sleep 60 & wait").start();
        ScriptExecutionService.CapturedOutput captured = ScriptExecutionService.capture(process, 1);
        long elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertFalse(captured.finished());
        assertTrue(elapsedMs < 15_000, "took " + elapsedMs + "ms");
    }

    @Test
    void hugeStdoutIsCapped() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("/bin/bash", "-c",
                "head -c 5000000 /dev/zero | tr '\\0' 'x'").start();
        ScriptExecutionService.CapturedOutput captured = ScriptExecutionService.capture(process, 30);

        assertTrue(captured.finished());
        assertTrue(captured.stdout().length() < 2_000_000, "length " + captured.stdout().length());
        assertTrue(captured.stdoutTruncated());
        assertTrue(captured.stdout().contains(io.apitomy.axiom.agents.spi.process.BoundedProcessRunner.TRUNCATION_MARKER));
    }

    @Test
    void jsonResultAfterMoreThanOneMebibyteOfLogsIsStillTheOutput() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("/bin/bash", "-c",
                "for i in $(seq 1 40000); do echo \"log line $i with some padding text\"; done; echo '{\"ok\":true}'")
                .start();
        ScriptExecutionService.CapturedOutput captured = ScriptExecutionService.capture(process, 60);
        ScriptExecutionService.ActionRun run = ScriptExecutionService.toActionRun(captured, "Script", 60, 0,
                null);

        assertTrue(captured.stdoutTruncated());
        assertEquals(0, run.exitCode());
        assertEquals("{\"ok\":true}", run.output());
    }

    @Test
    void truncatedStdoutWithoutJsonResultFailsTheRun() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("/bin/bash", "-c", "head -c 3000000 /dev/zero | tr '\\0' 'x'")
                .start();
        ScriptExecutionService.CapturedOutput captured = ScriptExecutionService.capture(process, 60);
        ScriptExecutionService.ActionRun run = ScriptExecutionService.toActionRun(captured, "Script", 60, 0,
                null);

        assertEquals(1, run.exitCode());
        assertTrue(run.stderr().contains("stdout exceeded 1 MiB and no JSON result was found at the end"),
                run.stderr());
    }

    @Test
    void stdinIsClosedForScriptProcesses(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws IOException, InterruptedException {
        java.nio.file.Path script = dir.resolve("s.sh");
        java.nio.file.Files.writeString(script, "cat; echo '{\"done\":true}'\n");
        ProcessBuilder pb = ScriptExecutionService.scriptProcess(script, dir, java.util.Map.of());
        ScriptExecutionService.CapturedOutput captured =
                ScriptExecutionService.capture(ScriptExecutionService.startScript(pb), 10);

        assertTrue(captured.finished());
        assertEquals("{\"done\":true}", ScriptExecutionService.extractScriptOutput(captured.stdout()));
    }
}
