package io.apitomy.axiom.app.summary;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunSummaryPromptBuilderTest {

    private static RunSummaryPromptBuilder.RunDigest digest(List<RunSummaryPromptBuilder.StepDigest> steps) {
        return new RunSummaryPromptBuilder.RunDigest(42L, "Triage", "Daily triage", "failed", 65_000L,
                "Rate limited\nstack...", 0.1234, steps);
    }

    @Test
    void includesRunFactsToolsHintAndJsonInstruction() {
        String p = RunSummaryPromptBuilder.build(digest(List.of(
                new RunSummaryPromptBuilder.StepDigest(7L, "fetch", "Completed", "14 issues"))));
        assertTrue(p.contains("Run ID: 42"));
        assertTrue(p.contains("Workflow: Triage"));
        assertTrue(p.contains("Failure: Rate limited"));
        assertFalse(p.contains("stack..."));
        assertTrue(p.contains("task 7 [fetch] Completed: 14 issues"));
        assertTrue(p.contains("$0.1234"));
        assertTrue(p.contains("\"headline\""));
        assertTrue(p.contains("get_task_log"));
    }

    @Test
    void previewsAreOneLineAndShort() {
        String out = "line1\nline2 " + "y".repeat(500);
        String p = RunSummaryPromptBuilder.build(digest(List.of(
                new RunSummaryPromptBuilder.StepDigest(1L, "n", "Completed", out))));
        assertFalse(p.contains("y".repeat(200)));
        assertTrue(p.contains("line1 line2"));
    }

    @Test
    void digestStaysWithinBudgetForHugeRuns() {
        List<RunSummaryPromptBuilder.StepDigest> steps = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            steps.add(new RunSummaryPromptBuilder.StepDigest(i, "node" + i, "Completed", "out " + i));
        }
        String p = RunSummaryPromptBuilder.build(digest(steps));
        String digestPart = p.substring(p.indexOf("## Run"), p.indexOf("## Instructions"));
        assertTrue(digestPart.length() <= RunSummaryPromptBuilder.DIGEST_BUDGET + 100);
        assertTrue(digestPart.contains("more steps omitted"));
    }
}
