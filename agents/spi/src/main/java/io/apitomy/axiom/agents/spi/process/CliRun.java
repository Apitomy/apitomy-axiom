package io.apitomy.axiom.agents.spi.process;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * One short, bounded run of an agent CLI, as used by the agent startup checks.
 * <p>
 * The CLI runs through {@link BoundedProcessRunner}: stdin is closed, stdout and stderr are combined and drained
 * concurrently (so a chatty CLI can never block on a full pipe), the output is kept also after a timeout, and the
 * whole process tree is killed at the deadline or when the calling thread is interrupted.
 *
 * @param outcome  how the run ended
 * @param exitCode the exit code, meaningful only when {@link Outcome#COMPLETED}
 * @param output   the combined output captured so far, possibly empty, never {@code null}
 * @param failure  the cause when the CLI could not be started, otherwise {@code null}
 */
public record CliRun(Outcome outcome, int exitCode, String output, String failure) {

    /** Maximum number of output bytes kept per run; the tail is kept when the CLI writes more. */
    public static final int MAX_OUTPUT_BYTES = 64 * 1024;

    /** Default length of the excerpt that {@link #excerptSuffix(String, int)} quotes. */
    public static final int DEFAULT_EXCERPT_CHARS = 200;

    /** How a CLI run ended. */
    public enum Outcome {
        /** The CLI exited on its own; {@link CliRun#exitCode()} is valid. */
        COMPLETED,
        /** The CLI exceeded its deadline and its process tree was killed. */
        TIMED_OUT,
        /** The calling thread was interrupted; the process tree was killed and the interrupt flag restored. */
        INTERRUPTED,
        /** The CLI could not be started at all (for example it is not on the PATH). */
        NOT_STARTED
    }

    /**
     * Runs a CLI command with a deadline and returns how it ended. Never throws: start failures and interruption
     * are reported through {@link #outcome()}. On interruption the thread's interrupt flag is set again.
     *
     * @param command the command and its arguments
     * @param timeout the maximum run time
     * @return the run
     */
    public static CliRun run(List<String> command, Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        try {
            BoundedProcessRunner.Result result = BoundedProcessRunner.run(builder, timeout, MAX_OUTPUT_BYTES);
            return result.timedOut()
                    ? new CliRun(Outcome.TIMED_OUT, BoundedProcessRunner.NO_EXIT_CODE, result.stdout(), null)
                    : new CliRun(Outcome.COMPLETED, result.exitCode(), result.stdout(), null);
        } catch (IOException | RuntimeException e) {
            return new CliRun(Outcome.NOT_STARTED, BoundedProcessRunner.NO_EXIT_CODE, "", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CliRun(Outcome.INTERRUPTED, BoundedProcessRunner.NO_EXIT_CODE, "", null);
        }
    }

    /**
     * Returns true when the run completed with exit code 0 and its output contains the token.
     *
     * @param token the text the CLI is expected to print
     * @return whether the run succeeded and printed the token
     */
    public boolean succeededWith(String token) {
        return outcome == Outcome.COMPLETED && exitCode == 0 && output.contains(token);
    }

    /**
     * Formats CLI output as a quoted, single-line excerpt for a check message, such as {@code : "..."}, or an
     * empty string when the output has no printable content. Whitespace is collapsed. When the output is longer
     * than {@code maxChars}, the <em>end</em> is kept, because CLIs print banners and warnings first and the
     * actual error last. The excerpt quotes the CLI verbatim and is shown to operators in the log and the UI.
     *
     * @param output   the CLI output, may be {@code null}
     * @param maxChars the maximum number of output characters to quote
     * @return the excerpt suffix, or an empty string
     */
    public static String excerptSuffix(String output, int maxChars) {
        if (output == null) {
            return "";
        }
        String flat = output.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            return "";
        }
        String excerpt = flat.length() <= maxChars
                ? flat : "..." + flat.substring(flat.length() - maxChars);
        return ": \"" + excerpt + "\"";
    }

    /**
     * {@link #excerptSuffix(String, int)} of this run's output with {@link #DEFAULT_EXCERPT_CHARS}.
     *
     * @return the excerpt suffix, or an empty string
     */
    public String excerptSuffix() {
        return excerptSuffix(output, DEFAULT_EXCERPT_CHARS);
    }
}
