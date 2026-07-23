package io.apitomy.axiom.actors.claudecode;

import io.apitomy.axiom.actors.spi.Actor;
import io.apitomy.axiom.actors.spi.ActorContext;
import io.apitomy.axiom.actors.spi.TaskResult;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.services.SystemSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Actor implementation that executes tasks by launching Claude Code as a CLI
 * subprocess. Each task gets its own subprocess, scoped to the project's
 * working directory.
 */
@ApplicationScoped
public class ClaudeCodeActor implements Actor {

    private static final Logger LOG = Logger.getLogger(ClaudeCodeActor.class);

    @Inject
    SystemSettingsService settingsService;

    private final Map<Long, ClaudeCodeSubprocess> runningProcesses = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public String getType() {
        return "claude-code";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<TaskResult> execute(TaskEntity task, ActorContext context) {
        LOG.infof("Executing task %d (action: %s) via Claude Code", task.id, task.actionType);

        String prompt = buildPrompt(task, context);

        // Create execution log builder and fill header + prompt + allowed tools sections
        ExecutionLogBuilder logBuilder = new ExecutionLogBuilder();
        logBuilder.header(task.id, task.actionType, Instant.now());
        logBuilder.systemPrompt(context.getSystemPrompt());
        logBuilder.prompt(prompt);
        logBuilder.allowedTools(context.getAllowedTools());
        logBuilder.environment(context.getEnvironment());

        ClaudeCodeCommandBuilder cmdBuilder = ClaudeCodeCommandBuilder
                .fromContext(prompt, context)
                .maxTurns(settingsService.getClaudeCodeMaxTurns())
                .maxBudgetUsd(settingsService.getClaudeCodeMaxBudgetUsd());

        // Per-action-type model takes priority over the global default
        if (context.getModel() != null && !context.getModel().isBlank()) {
            cmdBuilder.model(context.getModel());
        } else {
            String globalModel = settingsService.getClaudeCodeModel();
            if (globalModel != null) {
                cmdBuilder.model(globalModel);
            }
        }

        if (context.getMcpConfigFile() != null) {
            cmdBuilder.mcpConfigFile(context.getMcpConfigFile());
        }

        List<String> command = cmdBuilder.build();

        java.io.File workDir = context.getWorkingDirectory() != null
                ? context.getWorkingDirectory().toFile() : null;

        ClaudeCodeSubprocess subprocess = new ClaudeCodeSubprocess(
                command,
                workDir,
                context.getEnvironment() != null ? context.getEnvironment() : Map.of(),
                Duration.ofSeconds(settingsService.getClaudeCodeTimeoutSeconds()),
                line -> LOG.tracef("Task %d stream: %s", task.id, line),
                logBuilder
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void cancel(TaskEntity task) {
        ClaudeCodeSubprocess subprocess = runningProcesses.remove(task.id);
        if (subprocess != null) {
            LOG.infof("Cancelling task %d", task.id);
            subprocess.kill();
        }
    }

    /**
     * Builds the prompt for Claude Code. If the context has a resolved prompt template,
     * uses it. Otherwise falls back to a generic prompt.
     */
    private String buildPrompt(TaskEntity task, ActorContext context) {
        String template = context.getPromptTemplate();
        if (template != null && !template.isBlank()) {
            return template;
        }

        // Fallback: generic prompt
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are performing the following action: ").append(task.actionType).append("\n\n");
        if (task.input != null && !task.input.isEmpty()) {
            prompt.append("Task context:\n").append(task.input).append("\n");
        }
        return prompt.toString();
    }

    private TaskResult toTaskResult(ClaudeCodeResult result) {
        if (result.isSuccess()) {
            return TaskResult.success(result.result())
                    .sessionId(result.sessionId())
                    .costUsd(result.totalCostUsd())
                    .inputTokens(result.inputTokens())
                    .outputTokens(result.outputTokens())
                    .executionLog(result.executionLog())
                    .build();
        } else {
            return TaskResult.failure(result.result())
                    .sessionId(result.sessionId())
                    .costUsd(result.totalCostUsd())
                    .inputTokens(result.inputTokens())
                    .outputTokens(result.outputTokens())
                    .executionLog(result.executionLog())
                    .build();
        }
    }
}
