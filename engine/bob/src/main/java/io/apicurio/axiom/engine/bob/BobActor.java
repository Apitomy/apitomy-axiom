package io.apicurio.axiom.engine.bob;

import io.apicurio.axiom.actors.spi.Actor;
import io.apicurio.axiom.actors.spi.ActorContext;
import io.apicurio.axiom.actors.spi.TaskResult;
import io.apicurio.axiom.core.entities.TaskEntity;
import io.apicurio.axiom.engine.spi.AiEngineConfig;
import io.apicurio.axiom.engine.spi.AiEngineResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Actor implementation that executes tasks via IBM Bob Shell.
 */
@ApplicationScoped
public class BobActor implements Actor {

    private static final Logger LOG = Logger.getLogger(BobActor.class);

    @Inject
    BobEngine engine;

    @ConfigProperty(name = "axiom.bob.timeout-seconds", defaultValue = "600")
    int timeoutSeconds;

    private final Map<Long, BobSubprocess> runningProcesses = new ConcurrentHashMap<>();

    @Override
    public String getType() {
        return "bob";
    }

    @Override
    public CompletableFuture<TaskResult> execute(TaskEntity task, ActorContext context) {
        LOG.infof("Executing task %d (action: %s) via IBM Bob", task.id, task.actionType);

        String prompt = buildPrompt(task, context);

        AiEngineConfig engineConfig = AiEngineConfig.builder()
                .systemPrompt(context.getSystemPrompt())
                .allowedTools(context.getAllowedTools())
                .disallowedTools(context.getDisallowedTools())
                .workingDirectory(context.getWorkingDirectory())
                .environment(context.getEnvironment() != null ? context.getEnvironment() : Map.of())
                .timeoutSeconds(timeoutSeconds)
                .build();

        return engine.prompt(engineConfig, prompt)
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
        BobSubprocess subprocess = runningProcesses.remove(task.id);
        if (subprocess != null) {
            LOG.infof("Cancelling task %d", task.id);
            subprocess.kill();
        }
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
