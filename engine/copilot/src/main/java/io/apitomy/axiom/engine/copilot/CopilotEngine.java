package io.apitomy.axiom.engine.copilot;

import io.apitomy.axiom.actors.spi.ActorContext;
import io.apitomy.axiom.engine.spi.AiEngine;
import io.apitomy.axiom.engine.spi.AiEngineCheckResult;
import io.apitomy.axiom.engine.spi.AiEngineConfig;
import io.apitomy.axiom.engine.spi.AiEngineMcpManager;
import io.apitomy.axiom.engine.spi.AiEngineProvider;
import io.apitomy.axiom.engine.spi.AiEngineResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * GitHub Copilot CLI ({@code copilot}) implementation of the {@link AiEngine}
 * SPI. Wraps {@link CopilotCommandBuilder} and {@link CopilotSubprocess} to
 * provide engine-agnostic prompt execution.
 *
 * <p>Also serves as the {@link AiEngineProvider} for CDI discovery — the
 * {@link io.apitomy.axiom.engine.spi.AiEngineProducer} finds this bean via
 * the provider interface to avoid CDI bean type recursion.</p>
 */
@ApplicationScoped
@Typed({CopilotEngine.class, AiEngineProvider.class})
public class CopilotEngine implements AiEngine, AiEngineProvider {

    @ConfigProperty(name = "axiom.copilot.executable", defaultValue = "copilot")
    String executable;

    @Inject
    CopilotMcpManager mcpManager;

    @Override
    public String getType() {
        return "copilot";
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
            ProcessBuilder pb = new ProcessBuilder(executable, "-p",
                    "Reply with exactly: AXIOM_OK", "--allow-all-tools",
                    "--disable-builtin-mcps", "--output-format", "text", "-s");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                results.add(new AiEngineCheckResult(
                        "GitHub Copilot CLI",
                        "error",
                        "GitHub Copilot CLI check timed out after 30 seconds. "
                                + "Ensure that the 'copilot' command is on your PATH and that "
                                + "you are logged in (run 'copilot login')."
                ));
                return results;
            }

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.exitValue();

            if (exitCode == 0 && output.contains("AXIOM_OK")) {
                results.add(new AiEngineCheckResult(
                        "GitHub Copilot CLI",
                        "ok",
                        "GitHub Copilot CLI is available and working."
                ));
            } else {
                results.add(new AiEngineCheckResult(
                        "GitHub Copilot CLI",
                        "error",
                        "GitHub Copilot CLI returned exit code " + exitCode + ". "
                                + "Ensure that 'copilot' is installed, on your PATH, and that "
                                + "you are authenticated (run 'copilot login'). "
                                + "Install: npm install -g @github/copilot"
                ));
            }
        } catch (Exception e) {
            results.add(new AiEngineCheckResult(
                    "GitHub Copilot CLI",
                    "error",
                    "GitHub Copilot CLI is not available: " + e.getMessage() + ". "
                            + "Install GitHub Copilot CLI: npm install -g @github/copilot"
            ));
        }

        return results;
    }

    private CompletableFuture<AiEngineResult> executeInternal(AiEngineConfig config,
                                                               String prompt,
                                                               String jsonSchema) {
        ActorContext actorContext = ActorContext.builder()
                .allowedTools(config.getAllowedTools())
                .disallowedTools(config.getDisallowedTools())
                .workingDirectory(config.getWorkingDirectory())
                .mcpConfigFile(config.getMcpConfigFile())
                .build();

        String effectivePrompt = prompt;
        if (config.getSystemPrompt() != null && !config.getSystemPrompt().isBlank()) {
            effectivePrompt = config.getSystemPrompt() + "\n\n" + prompt;
        }

        if (jsonSchema != null) {
            effectivePrompt = effectivePrompt
                    + "\n\nIMPORTANT: You MUST respond with ONLY a valid JSON object "
                    + "matching this JSON schema. No other text, no markdown formatting, "
                    + "no code fences — just the raw JSON.\n\nJSON Schema:\n" + jsonSchema;
        }

        CopilotCommandBuilder cmdBuilder = CopilotCommandBuilder
                .fromContext(effectivePrompt, actorContext)
                .executable(executable);

        if (config.getModel() != null) {
            cmdBuilder.model(config.getModel());
        }
        if (config.getSessionId() != null) {
            cmdBuilder.sessionId(config.getSessionId());
        }

        List<String> command = cmdBuilder.build();

        java.io.File workDir = config.getWorkingDirectory() != null
                ? config.getWorkingDirectory().toFile()
                : null;

        CopilotSubprocess subprocess = new CopilotSubprocess(
                command, workDir,
                config.getEnvironment() != null ? config.getEnvironment() : Map.of(),
                Duration.ofSeconds(config.getTimeoutSeconds())
        );

        return subprocess.execute().thenApply(CopilotEngine::toEngineResult);
    }

    private static AiEngineResult toEngineResult(CopilotResult result) {
        return new AiEngineResult(
                result.result(),
                result.sessionId(),
                result.costUsd(),
                result.inputTokens(),
                result.outputTokens(),
                result.isSuccess(),
                result.executionLog()
        );
    }

    // --- AiEngineProvider ---

    @Override
    public AiEngine getEngine() {
        return this;
    }

    @Override
    public AiEngineMcpManager getMcpManager() {
        return mcpManager;
    }
}
