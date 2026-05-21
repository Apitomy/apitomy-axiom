package io.apicurio.axiom.engine.bob;

import io.apicurio.axiom.engine.spi.AiEngine;
import io.apicurio.axiom.engine.spi.AiEngineCheckResult;
import io.apicurio.axiom.engine.spi.AiEngineConfig;
import io.apicurio.axiom.engine.spi.AiEngineProvider;
import io.apicurio.axiom.engine.spi.AiEngineResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * IBM Bob CLI implementation of the {@link AiEngine} SPI. Wraps the Bob Shell
 * CLI to provide engine-agnostic prompt execution.
 *
 * <p>Bob Shell runs in non-interactive mode via {@code bob -p "<prompt>"} and
 * uses {@code BOBSHELL_API_KEY} for authentication.</p>
 */
@ApplicationScoped
@Typed({BobEngine.class, AiEngineProvider.class})
public class BobEngine implements AiEngine, AiEngineProvider {

    @Override
    public String getType() {
        return "bob";
    }

    @Override
    public CompletableFuture<AiEngineResult> prompt(AiEngineConfig config, String prompt) {
        return executeInternal(config, prompt, null);
    }

    @Override
    public CompletableFuture<AiEngineResult> promptWithSchema(AiEngineConfig config,
                                                               String prompt,
                                                               String jsonSchema) {
        return executeInternal(config, prompt, jsonSchema);
    }

    @Override
    public List<AiEngineCheckResult> healthCheck() {
        List<AiEngineCheckResult> results = new ArrayList<>();

        try {
            ProcessBuilder pb = new ProcessBuilder("bob", "--auth-method", "api-key",
                    "-p", "Reply with exactly: AXIOM_OK");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                results.add(new AiEngineCheckResult(
                        "IBM Bob CLI",
                        "error",
                        "IBM Bob CLI check timed out after 30 seconds. "
                                + "Ensure that the 'bob' command is on your PATH and that "
                                + "BOBSHELL_API_KEY is set in your environment."
                ));
                return results;
            }

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.exitValue();

            if (exitCode == 0 && output.contains("AXIOM_OK")) {
                results.add(new AiEngineCheckResult(
                        "IBM Bob CLI",
                        "ok",
                        "IBM Bob CLI is available and working."
                ));
            } else {
                results.add(new AiEngineCheckResult(
                        "IBM Bob CLI",
                        "error",
                        "IBM Bob CLI returned exit code " + exitCode + ". "
                                + "Ensure that the 'bob' command is installed, on your PATH, "
                                + "and that BOBSHELL_API_KEY is set. "
                                + "Install Bob Shell: curl -fsSL https://bob.ibm.com/download/bobshell.sh | bash"
                ));
            }
        } catch (Exception e) {
            results.add(new AiEngineCheckResult(
                    "IBM Bob CLI",
                    "error",
                    "IBM Bob CLI is not available: " + e.getMessage() + ". "
                            + "Install Bob Shell: curl -fsSL https://bob.ibm.com/download/bobshell.sh | bash"
            ));
        }

        return results;
    }

    private CompletableFuture<AiEngineResult> executeInternal(AiEngineConfig config,
                                                               String prompt,
                                                               String jsonSchema) {
        // If structured output is requested, inject schema instructions into the prompt
        String effectivePrompt = prompt;
        if (jsonSchema != null) {
            effectivePrompt = prompt + "\n\nYou MUST respond with valid JSON conforming to "
                    + "this schema:\n" + jsonSchema
                    + "\n\nRespond ONLY with the JSON object, no other text.";
        }

        BobCommandBuilder cmdBuilder = BobCommandBuilder.create(effectivePrompt)
                .systemPrompt(config.getSystemPrompt())
                .yolo(true);

        List<String> command = cmdBuilder.build();

        java.io.File workDir = config.getWorkingDirectory() != null
                ? config.getWorkingDirectory().toFile()
                : null;

        BobSubprocess subprocess = new BobSubprocess(
                command, workDir,
                config.getEnvironment() != null ? config.getEnvironment() : Map.of(),
                Duration.ofSeconds(config.getTimeoutSeconds())
        );

        return subprocess.execute().thenApply(BobEngine::toEngineResult);
    }

    private static AiEngineResult toEngineResult(BobResult result) {
        return new AiEngineResult(
                result.result(),
                null,
                null,
                null,
                null,
                result.isSuccess(),
                null
        );
    }

    // --- AiEngineProvider ---

    @Override
    public AiEngine getEngine() {
        return this;
    }
}
