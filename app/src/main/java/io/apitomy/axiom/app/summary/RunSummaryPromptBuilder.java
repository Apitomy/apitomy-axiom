package io.apitomy.axiom.app.summary;

import java.util.List;
import java.util.Locale;

/** Builds the agent prompt for a run summary: a budgeted digest plus output instructions. */
public final class RunSummaryPromptBuilder {

    /** Character budget for the digest section. */
    public static final int DIGEST_BUDGET = 3000;
    /** Maximum characters of each step's output preview. */
    public static final int OUTPUT_PREVIEW = 150;

    /**
     * One step of the run.
     *
     * @param taskId the task id
     * @param nodeId the workflow node id
     * @param status the task status
     * @param output the task output, or null
     */
    public record StepDigest(long taskId, String nodeId, String status, String output) { }

    /**
     * The facts about a run that go into the prompt.
     *
     * @param runId the run id
     * @param workflowName the workflow name
     * @param title the run title, or null
     * @param status the run status
     * @param durationMs the run duration in milliseconds, or null
     * @param failureReason the failure reason, or null
     * @param costUsd the AI cost in USD, or null
     * @param steps the steps in order
     */
    public record RunDigest(long runId, String workflowName, String title, String status, Long durationMs,
                            String failureReason, Double costUsd, List<StepDigest> steps) { }

    private RunSummaryPromptBuilder() { }

    /**
     * Builds the prompt.
     *
     * @param digest the run facts
     * @return the prompt text
     */
    public static String build(RunDigest digest) {
        StringBuilder d = new StringBuilder("## Run\n");
        d.append("Run ID: ").append(digest.runId()).append('\n');
        d.append("Workflow: ").append(digest.workflowName()).append('\n');
        if (digest.title() != null && !digest.title().isBlank()) {
            d.append("Title: ").append(oneLine(digest.title(), 200)).append('\n');
        }
        d.append("Status: ").append(digest.status()).append('\n');
        if (digest.durationMs() != null) {
            d.append("Duration: ").append(digest.durationMs() / 1000).append("s\n");
        }
        String failure = firstLine(digest.failureReason());
        if (failure != null) {
            d.append("Failure: ").append(oneLine(failure, 300)).append('\n');
        }
        if (digest.costUsd() != null) {
            d.append("AI cost: $").append(String.format(Locale.ROOT, "%.4f", digest.costUsd())).append('\n');
        }
        d.append("Steps (in order):\n");
        List<StepDigest> steps = digest.steps();
        for (int i = 0; i < steps.size(); i++) {
            StepDigest s = steps.get(i);
            String line = "- task " + s.taskId() + " [" + s.nodeId() + "] " + s.status()
                    + (s.output() == null || s.output().isBlank() ? "" : ": " + oneLine(s.output(), OUTPUT_PREVIEW))
                    + "\n";
            if (d.length() + line.length() > DIGEST_BUDGET - 40) {
                d.append("- … ").append(steps.size() - i).append(" more steps omitted (use get_run)\n");
                break;
            }
            d.append(line);
        }
        return "You are summarizing one finished workflow run of the Axiom automation platform for a person "
                + "browsing a list of runs.\n\n"
                + d
                + "\n## Instructions\n"
                + "- The digest above may be enough. If it is not (especially to explain a failure), use the "
                + "read-only tools get_run, get_task, get_task_log, and get_trace with runId " + digest.runId()
                + ". Only examine this run.\n"
                + "- Never reproduce secrets, tokens, or credentials.\n"
                + "- Respond ONLY with a JSON object: {\"headline\": \"...\", \"body\": \"...\"}\n"
                + "- headline: at most 120 characters, one line, what the run accomplished (or why it failed).\n"
                + "- body: 3-8 markdown bullet points covering what the run did, key outputs, and the cause of "
                + "any failure.\n";
    }

    private static String firstLine(String text) {
        if (text == null) {
            return null;
        }
        return text.lines().map(String::trim).filter(l -> !l.isEmpty()).findFirst().orElse(null);
    }

    private static String oneLine(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
