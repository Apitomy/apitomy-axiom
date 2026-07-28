package io.apitomy.axiom.engine.opencode;

import io.apitomy.axiom.actors.spi.Actor;
import io.apitomy.axiom.actors.spi.ActorContext;
import io.apitomy.axiom.actors.spi.TaskResult;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.engine.spi.AiEngineConfig;
import io.apitomy.axiom.engine.spi.AiEngineResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Actor implementation that executes tasks via the OpenCode AI engine.
 * Each task gets a session in the OpenCode server, scoped to the project's
 * working directory.
 */
@ApplicationScoped
public class OpenCodeActor implements Actor {

    private static final Logger LOG = Logger.getLogger(OpenCodeActor.class);

    @Inject
    OpenCodeEngine engine;

    @ConfigProperty(name = "axiom.opencode.model")
    Optional<String> model;

    @ConfigProperty(name = "axiom.opencode.max-steps", defaultValue = "50")
    int maxSteps;

    @ConfigProperty(name = "axiom.opencode.timeout-seconds", defaultValue = "600")
    int timeoutSeconds;

    /** Tracks running sessions for cancellation support. */
    private final Map<Long, String> runningSessions = new ConcurrentHashMap<>();

    @Override
    public String getType() {
        return "opencode";
    }

    @Override
    public CompletableFuture<TaskResult> execute(TaskEntity task, ActorContext context) {
        LOG.infof("Executing task %d (action: %s) via OpenCode", task.id, task.actionType);

        String prompt = buildPrompt(task, context);

        // Build engine config from actor context
        AiEngineConfig engineConfig = AiEngineConfig.builder()
                .systemPrompt(context.getSystemPrompt())
                .allowedTools(context.getAllowedTools())
                .disallowedTools(context.getDisallowedTools())
                .workingDirectory(context.getWorkingDirectory())
                .environment(context.getEnvironment() != null ? context.getEnvironment() : Map.of())
                .timeoutSeconds(timeoutSeconds)
                .maxSteps(context.getMaxSteps() != null ? context.getMaxSteps() : maxSteps)
                .model(resolveModel(context))
                .mcpConfigFile(context.getMcpConfigFile())
                .build();

        return engine.prompt(engineConfig, prompt)
                .thenApply(result -> {
                    runningSessions.remove(task.id);
                    return toTaskResult(result);
                })
                .exceptionally(throwable -> {
                    runningSessions.remove(task.id);
                    LOG.errorf(throwable, "Task %d execution failed", task.id);
                    return TaskResult.failure("Execution error: " + throwable.getMessage()).build();
                });
    }

    @Override
    public void cancel(TaskEntity task) {
        String sessionId = runningSessions.remove(task.id);
        if (sessionId != null) {
            LOG.infof("Cancelling task %d (session: %s)", task.id, sessionId);
            try {
                OpenCodeClient client = engine.getOrStartServer();
                client.abortSession(sessionId);
            } catch (Exception e) {
                LOG.warnf("Failed to abort session for task %d: %s", task.id, e.getMessage());
            }
        }
    }

    private String resolveModel(ActorContext context) {
        // Per-action-type model takes priority over global default
        if (context.getModel() != null && !context.getModel().isBlank()) {
            return context.getModel();
        }
        return model.orElse(null);
    }

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

    private TaskResult toTaskResult(AiEngineResult result) {
        if (result.success()) {
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
