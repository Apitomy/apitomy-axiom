package io.apitomy.axiom.app.composition;

import io.apitomy.axiom.api.beans.CompositionTestRun;
import io.apitomy.axiom.api.beans.CompositionTestValues;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TestRunRedactor}.
 */
class TestRunRedactorTest {

    private static CompositionTestRun run() {
        CompositionTestRun run = new CompositionTestRun();
        run.setId("run-12345678");
        run.setItemId(1L);
        run.setItemType(CompositionTestRun.ItemType.TOOL);
        run.setItemName("t");
        run.setAuthor(CompositionTestRun.Author.USER);
        run.setStatus(CompositionTestRun.Status.FAILED);
        run.setStartedAt(new Date());
        return run;
    }

    @Test
    void keepsSecretReferencesAndRedactsLiteralEnvironmentValues() {
        assertEquals("${secret:A}", TestRunRedactor.redactEnvValue("${secret:A}"));
        assertEquals("${secret:A}${secret:B}", TestRunRedactor.redactEnvValue("${secret:A}${secret:B}"));
        assertEquals(TestRunRedactor.REDACTED, TestRunRedactor.redactEnvValue("Bearer ${secret:A}"));
        assertEquals(TestRunRedactor.REDACTED, TestRunRedactor.redactEnvValue("plain"));
        assertEquals(TestRunRedactor.REDACTED, TestRunRedactor.redactEnvValue(null));
    }

    @Test
    void listsReferencedSecrets() {
        assertEquals(Set.of("A", "B"),
                TestRunRedactor.referencedSecrets(Map.of("X", "${secret:A}-${secret:B}", "Y", "z")));
        assertTrue(TestRunRedactor.referencedSecrets(null).isEmpty());
    }

    @Test
    void scrubsSecretValuesFromAllText() {
        CompositionTestRun run = run();
        run.setStdout("token=hunter22");
        run.setStderr("err hunter22");
        run.setMessage("m hunter22");
        run.setResolvedScript("echo hunter22");
        run.setProblems(List.of("p hunter22"));
        CompositionTestValues outputs = new CompositionTestValues();
        outputs.setAdditionalProperty("nested", Map.of("v", List.of("hunter22")));
        run.setOutputs(outputs);
        CompositionTestValues inputs = new CompositionTestValues();
        inputs.setAdditionalProperty("in", "hunter22");
        run.setInputs(inputs);

        CompositionTestRun view = TestRunRedactor.redact(run, Map.of("T", "${secret:T}"), List.of("hunter22"));

        String all = view.getStdout() + view.getStderr() + view.getMessage() + view.getResolvedScript()
                + view.getProblems() + view.getOutputs().getAdditionalProperties()
                + view.getInputs().getAdditionalProperties();
        assertFalse(all.contains("hunter22"));
        assertEquals("token=[redacted]", view.getStdout());
        assertEquals("${secret:T}", view.getEnvironment().getAdditionalProperties().get("T"));
        assertEquals("token=hunter22", run.getStdout());
    }

    @Test
    void truncatesLongLogsKeepingTheTail() {
        CompositionTestRun run = run();
        run.setStdout("x".repeat(TestRunRedactor.MAX_LOG_CHARS + 10) + "END");
        CompositionTestRun view = TestRunRedactor.redact(run, null, List.of());
        assertTrue(view.getStdout().startsWith("[truncated 13 chars]"));
        assertTrue(view.getStdout().endsWith("END"));
        assertNull(view.getEnvironment());
    }
}
