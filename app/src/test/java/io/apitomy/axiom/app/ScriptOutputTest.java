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
        assertEquals(2_000_000, captured.stderr().length());
    }

    @Test
    void timeoutIsReported() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("/bin/bash", "-c", "echo start; sleep 30").start();
        ScriptExecutionService.CapturedOutput captured = ScriptExecutionService.capture(process, 1);

        assertFalse(captured.finished());
        assertEquals(1, captured.exitCode());
    }
}
