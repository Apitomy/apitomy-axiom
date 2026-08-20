package io.apitomy.axiom.engine.copilot;

import io.apitomy.axiom.actors.spi.Actor;
import io.apitomy.axiom.actors.spi.ActorContext;
import io.apitomy.axiom.actors.spi.TaskResult;
import io.apitomy.axiom.core.entities.TaskEntity;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Actor implementation that executes tasks by launching the GitHub Copilot
 * CLI ({@code copilot}) as a subprocess. Each task gets its own subprocess,
 * scoped to the project's working directory.
 */
@ApplicationScoped
public class CopilotActor implements Actor {

    private static final Logger LOG = Logger.getLogger(CopilotActor.class);

    @ConfigProperty(name = "axiom.copilot.executable", defaultValue = "copilot")
    String executable;

    @ConfigProperty(name = "axiom.copilot.model")
    Optional<String> model;

    @ConfigProperty(name = "axiom.copilot.timeout-seconds", defaultValue = "600")
    int timeoutSeconds;

    private final Map<Long, CopilotSubprocess> runningProcesses = new ConcurrentHashMap<>();

    @Override
    public String getType() {
        return "copilot";
    }

    @Override
    public CompletableFuture<TaskResult> execute(TaskEntity task, ActorContext context) {
        LOG.infof("Executing task %d (action: %s) via GitHub Copilot CLI", task.id, task.actionType);

        String prompt = buildPrompt(task, context);
        if (context.getSystemPrompt() != null && !context.getSystemPrompt().isBlank()) {
            prompt = context.getSystemPrompt() + "\n\n" + prompt;
        }

        CopilotCommandBuilder cmdBuilder = CopilotCommandBuilder
                .fromContext(prompt, context)
                .executable(executable);

        // Per-action-type model takes priority over the global default
        if (context.getModel() != null && !context.getModel().isBlank()) {
            cmdBuilder.model(context.getModel());
        } else {
            model.ifPresent(cmdBuilder::model);
        }

        List<String> command = cmdBuilder.build();

        java.io.File workDir = context.getWorkingDirectory() != null
                ? context.getWorkingDirectory().toFile() : null;

        CopilotSubprocess subprocess = new CopilotSubprocess(
                command,
                workDir,
                context.getEnvironment() != null ? context.getEnvironment() : Map.of(),
                Duration.ofSeconds(timeoutSeconds)
        );

        runningProcesses.put(task.id, subprocess);

        return subprocess.execute()
                .thenApply(result -> {
                    runningProcesses.remove(task.id);
                    return toTaskResult(result);
                })
                .exceptionally(throwable -> {
                    runningProcesses.remove(task.id);
                    LOG.errorf(throwable, "Task %d execution failed", task.id);
                    return TaskResult.failure("Execution error: " + throwable.getMessage()).build();
                });
    }

    @Override
    public void cancel(TaskEntity task) {
        CopilotSubprocess subprocess = runningProcesses.remove(task.id);
        if (subprocess != null) {
            LOG.infof("Cancelling task %d", task.id);
            subprocess.kill();
        }
    }

    /**
     * Builds the prompt for Copilot. If the context has a resolved prompt template,
     * uses it. Otherwise falls back to a generic prompt.
     */
    private String buildPrompt(TaskEntity task, ActorContext context) {
        String template = context.getPromptTemplate();
        if (template != null && !template.isBlank()) {
            return template;
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are performing the following action: ").append(task.actionType).append("\n\n");
        if (task.input != null && !task.input.isEmpty()) {
            prompt.append("Task context:\n").append(task.input).append("\n");
        }
        return prompt.toString();
    }

    private TaskResult toTaskResult(CopilotResult result) {
        if (result.isSuccess()) {
            return TaskResult.success(result.result())
                    .sessionId(result.sessionId())
                    .costUsd(result.costUsd())
                    .inputTokens(result.inputTokens())
                    .outputTokens(result.outputTokens())
                    .executionLog(result.executionLog())
                    .build();
        } else {
            return TaskResult.failure(result.result())
                    .sessionId(result.sessionId())
                    .costUsd(result.costUsd())
                    .inputTokens(result.inputTokens())
                    .outputTokens(result.outputTokens())
                    .executionLog(result.executionLog())
                    .build();
        }
    }
}
