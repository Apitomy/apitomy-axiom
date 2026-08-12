package io.apitomy.axiom.app;

import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.core.tracing.TraceService;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.ScheduledJobEntity;
import io.apitomy.axiom.core.entities.ScheduledJobRunEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.axiom.core.services.EncryptionService;
import io.apitomy.axiom.core.services.EnvironmentResolver;
import io.apitomy.axiom.core.services.ToolsetResolver;
import io.apitomy.axiom.engine.spi.AiEngine;
import io.apitomy.axiom.engine.spi.AiEngineConfig;
import io.apitomy.axiom.engine.spi.AiEngineResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Executes scheduled job runs. Supports both AI actor mode (via AiEngine)
 * and script mode (via ProcessBuilder). Scheduled jobs run without project
 * context — they are global and self-contained.
 */
@ApplicationScoped
public class ScheduledJobExecutionService {

    private static final Logger LOG = Logger.getLogger(ScheduledJobExecutionService.class);

    private static final String SYSTEM_PROMPT = """
            You are executing a scheduled job for Apicurio Axiom. Follow the instructions \
            in the prompt carefully. Use the tools available to you to complete the task. \
            Return a clear summary of what you did and any results.""";

    @Inject
    Event<SseEvent> sseEvents;

    @Inject
    AiEngine aiEngine;

    @Inject
    McpConfigGenerator mcpConfigGenerator;

    @Inject
    ToolsetResolver toolsetResolver;

    @Inject
    EncryptionService encryptionService;

    @Inject
    EnvironmentResolver environmentResolver;

    @Inject
    TraceService traceService;

    @ConfigProperty(name = "axiom.claude-code.model")
    Optional<String> defaultModel;

    @ConfigProperty(name = "axiom.claude-code.timeout-seconds", defaultValue = "600")
    int defaultTimeoutSeconds;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "9090")
    int httpPort;

    @ConfigProperty(name = "axiom.script.timeout-seconds", defaultValue = "60")
    int scriptTimeoutSeconds;

    /**
     * Executes a scheduled job run. Dispatches to actor or script mode
     * based on the job's execution mode.
     *
     * @param job   the scheduled job definition
     * @param runId the run entity ID
     */
    public void executeRun(ScheduledJobEntity job, Long runId) {
        if ("script".equals(job.executionMode)) {
            executeScript(job, runId);
        } else {
            executeActor(job, runId);
        }
    }

    /**
     * Executes a job run in actor mode via the AI engine.
     */
    private void executeActor(ScheduledJobEntity job, Long runId) {
        LOG.infof("Executing scheduled job '%s' in actor mode (run ID: %d)", job.name, runId);

        TraceContext traceCtx = null;
        Long aiNodeId = null;
        try {
            traceCtx = traceService.createTrace("scheduled-job-execution",
                    "Executing scheduled job: " + job.name,
                    null, null, null,
                    "scheduled-job-triggered", "Scheduled job triggered: " + job.name,
                    null, null);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to create trace for scheduled job run %d", runId);
        }

        String prompt = resolvePromptTemplate(job.promptTemplate, job);
        List<String> allowedTools = resolveAllowedTools(job);
        Map<String, String> env = buildEnvironment(job.environment);

        if (traceCtx != null) {
            try {
                env.put("AXIOM_TRACE_ID", traceCtx.traceId().toString());
                aiNodeId = traceService.addNode(traceCtx, "scheduled-job-ai-invoked",
                        "in-progress",
                        "Scheduled job execution (AI agent): " + job.name, null, null);
                env.put("AXIOM_PARENT_NODE_ID", String.valueOf(aiNodeId));
            } catch (Exception e) {
                LOG.warnf(e, "Failed to add AI invocation trace node for run %d", runId);
            }
        }

        Path mcpConfig = mcpConfigGenerator.generateMcpConfig(runId, env, allowedTools);

        String effectiveModel = job.model != null ? job.model : defaultModel.orElse(null);
        int effectiveMaxSteps = job.maxSteps != null ? job.maxSteps : 30;
        Double effectiveMaxBudget = job.maxBudgetUsd;

        AiEngineConfig engineConfig = AiEngineConfig.builder()
                .systemPrompt(SYSTEM_PROMPT)
                .allowedTools(allowedTools)
                .timeoutSeconds(defaultTimeoutSeconds)
                .maxSteps(effectiveMaxSteps)
                .maxBudgetUsd(effectiveMaxBudget)
                .model(effectiveModel)
                .environment(env)
                .mcpConfigFile(mcpConfig)
                .build();

        UUID traceId = traceCtx != null ? traceCtx.traceId() : null;
        markRunning(runId, traceId);

        ClassLoader contextCl = Thread.currentThread().getContextClassLoader();
        final TraceContext finalTraceCtx = traceCtx;
        final Long finalAiNodeId = aiNodeId;

        aiEngine.prompt(engineConfig, prompt)
                .thenAccept(result -> {
                    Thread.currentThread().setContextClassLoader(contextCl);
                    onActorCompleted(runId, job.id, result, finalTraceCtx, finalAiNodeId);
                })
                .exceptionally(throwable -> {
                    Thread.currentThread().setContextClassLoader(contextCl);
                    LOG.errorf(throwable, "Scheduled job run %d failed unexpectedly", runId);
                    failRun(runId, "Unexpected error: " + throwable.getMessage(),
                            finalTraceCtx, finalAiNodeId);
                    return null;
                });
    }

    /**
     * Executes a job run in script mode via ProcessBuilder.
     */
    private void executeScript(ScheduledJobEntity job, Long runId) {
        LOG.infof("Executing scheduled job '%s' in script mode (run ID: %d)", job.name, runId);

        markRunning(runId, null);

        try {
            if (job.scriptTemplate == null || job.scriptTemplate.isBlank()) {
                failRun(runId, "No script template configured for scheduled job: " + job.name,
                        null, null);
                return;
            }

            String script = resolveScriptTemplate(job.scriptTemplate, job, runId);
            Instant startTime = Instant.now();

            Path scriptFile = Files.createTempFile("axiom-scheduled-job-", ".sh");
            try {
                Files.writeString(scriptFile, script);
                scriptFile.toFile().setExecutable(true);

                ProcessBuilder pb = new ProcessBuilder("/bin/bash", scriptFile.toString())
                        .redirectErrorStream(true);
                String apiBaseUrl = "http://localhost:" + httpPort + "/api/v1";
                pb.environment().put("AXIOM_API_URL", apiBaseUrl);
                pb.environment().put("AXIOM_JOB_ID", String.valueOf(job.id));
                pb.environment().put("AXIOM_RUN_ID", String.valueOf(runId));

                if (environmentResolver.hasCustomEnvironment(job.environment)) {
                    pb.environment().putAll(environmentResolver.resolve(job.environment));
                } else {
                    for (SecretEntity secret : SecretEntity.<SecretEntity>listAll()) {
                        try {
                            pb.environment().put(secret.name,
                                    encryptionService.decrypt(secret.encryptedValue));
                        } catch (Exception e) {
                            LOG.warnf("Failed to decrypt secret '%s' — skipping", secret.name);
                        }
                    }
                }

                Process process = pb.start();
                String output = new String(process.getInputStream().readAllBytes());
                boolean finished = process.waitFor(scriptTimeoutSeconds, TimeUnit.SECONDS);
                Instant endTime = Instant.now();
                long durationMs = Duration.between(startTime, endTime).toMillis();

                int exitCode;
                if (!finished) {
                    process.destroyForcibly();
                    exitCode = 1;
                    output = output + "\n[Script timed out after " + scriptTimeoutSeconds + "s]";
                } else {
                    exitCode = process.exitValue();
                }

                String executionLog = buildScriptExecutionLog(job, script, output,
                        exitCode, startTime, durationMs);

                if (exitCode == 0) {
                    completeRun(runId, output, executionLog, durationMs);
                } else {
                    failRunWithLog(runId, output, executionLog, durationMs);
                }
            } finally {
                Files.deleteIfExists(scriptFile);
            }
        } catch (Exception e) {
            LOG.errorf(e, "Script execution failed for scheduled job run %d", runId);
            failRun(runId, "Script execution error: " + e.getMessage(), null, null);
        }
    }

    @Transactional
    void markRunning(Long runId, UUID traceId) {
        ScheduledJobRunEntity run = ScheduledJobRunEntity.findById(runId);
        if (run != null) {
            run.status = "Running";
            run.startedAt = Instant.now();
            run.traceId = traceId;
            sseEvents.fire(SseEvent.scheduledJobRunUpdated(runId, "Running"));

            ScheduledJobEntity job = ScheduledJobEntity.findById(run.jobId);
            String jobName = job != null ? job.name : "Job #" + run.jobId;
            logActivity("scheduled-job-running",
                    "Scheduled job execution started: " + jobName);
        }
    }

    @Transactional
    void onActorCompleted(Long runId, Long jobId, AiEngineResult result,
                          TraceContext traceCtx, Long aiNodeId) {
        ScheduledJobRunEntity run = ScheduledJobRunEntity.findById(runId);
        if (run == null) return;

        ScheduledJobEntity job = ScheduledJobEntity.findById(jobId);

        if (result.success()) {
            run.status = "Completed";
            run.output = result.result();
        } else {
            run.status = "Failed";
            run.error = result.result();
        }
        run.executionLog = result.executionLog();
        run.costUsd = result.costUsd();
        run.completedAt = Instant.now();
        run.durationMs = Duration.between(run.startedAt, run.completedAt).toMillis();

        LOG.infof("Scheduled job run %d %s (cost: $%s)",
                runId, run.status,
                result.costUsd() != null ? String.format("%.4f", result.costUsd()) : "n/a");

        AiUsageEntity usage = new AiUsageEntity();
        usage.invocationType = "scheduled-job";
        usage.actionType = job != null ? job.name : "unknown";
        usage.costUsd = result.costUsd();
        usage.inputTokens = result.inputTokens();
        usage.outputTokens = result.outputTokens();
        usage.createdOn = Instant.now();
        usage.persist();

        String jobName = job != null ? job.name : "Job #" + jobId;
        String statusText = result.success() ? "completed" : "failed";
        String summary = "Scheduled job " + statusText + ": " + jobName;
        if (run.durationMs != null) {
            summary += String.format(" (%ds)", run.durationMs / 1000);
        }
        if (result.costUsd() != null) {
            summary += String.format(" — $%.4f", result.costUsd());
        }
        logActivity("scheduled-job-" + statusText, summary);
        sseEvents.fire(SseEvent.scheduledJobRunUpdated(runId, run.status));
        mcpConfigGenerator.cleanupTempFiles(runId);

        if (traceCtx != null) {
            try {
                if (aiNodeId != null) {
                    traceService.completeNode(aiNodeId, statusText, null, null);
                }
                traceService.completeTrace(traceCtx.traceId(),
                        result.success() ? "completed" : "failed");
            } catch (Exception e) {
                LOG.warnf(e, "Failed to complete trace for scheduled job run %d", runId);
            }
        }
    }

    @Transactional
    void completeRun(Long runId, String output, String executionLog, long durationMs) {
        ScheduledJobRunEntity run = ScheduledJobRunEntity.findById(runId);
        if (run != null) {
            run.status = "Completed";
            run.output = output;
            run.executionLog = executionLog;
            run.completedAt = Instant.now();
            run.durationMs = durationMs;

            ScheduledJobEntity job = ScheduledJobEntity.findById(run.jobId);
            String jobName = job != null ? job.name : "Job #" + run.jobId;
            logActivity("scheduled-job-completed",
                    String.format("Scheduled job completed: %s (%ds)", jobName,
                            durationMs / 1000));
            sseEvents.fire(SseEvent.scheduledJobRunUpdated(runId, "Completed"));
        }
    }

    @Transactional
    void failRunWithLog(Long runId, String error, String executionLog, long durationMs) {
        ScheduledJobRunEntity run = ScheduledJobRunEntity.findById(runId);
        if (run != null) {
            run.status = "Failed";
            run.error = error;
            run.executionLog = executionLog;
            run.completedAt = Instant.now();
            run.durationMs = durationMs;

            ScheduledJobEntity job = ScheduledJobEntity.findById(run.jobId);
            String jobName = job != null ? job.name : "Job #" + run.jobId;
            logActivity("scheduled-job-failed",
                    "Scheduled job failed: " + jobName);
            sseEvents.fire(SseEvent.scheduledJobRunUpdated(runId, "Failed"));
        }
    }

    @Transactional
    void failRun(Long runId, String reason, TraceContext traceCtx, Long aiNodeId) {
        ScheduledJobRunEntity run = ScheduledJobRunEntity.findById(runId);
        if (run != null) {
            run.status = "Failed";
            run.error = reason;
            run.completedAt = Instant.now();
            if (run.startedAt != null) {
                run.durationMs = Duration.between(run.startedAt, run.completedAt).toMillis();
            }

            ScheduledJobEntity job = ScheduledJobEntity.findById(run.jobId);
            String jobName = job != null ? job.name : "Job #" + run.jobId;
            logActivity("scheduled-job-failed",
                    "Scheduled job failed: " + jobName + " — " + reason);
            sseEvents.fire(SseEvent.scheduledJobRunUpdated(runId, "Failed"));
            mcpConfigGenerator.cleanupTempFiles(runId);
        }

        if (traceCtx != null) {
            try {
                if (aiNodeId != null) {
                    traceService.completeNode(aiNodeId, "failed", null, null);
                }
                traceService.completeTrace(traceCtx.traceId(), "failed");
            } catch (Exception e) {
                LOG.warnf(e, "Failed to complete trace for failed run %d", runId);
            }
        }
    }

    private String resolvePromptTemplate(String template, ScheduledJobEntity job) {
        if (template == null) return "";
        String apiBaseUrl = "http://localhost:" + httpPort + "/api/v1";
        return template
                .replace("{{jobName}}", job.name != null ? job.name : "")
                .replace("{{apiBaseUrl}}", apiBaseUrl);
    }

    private String resolveScriptTemplate(String template, ScheduledJobEntity job, Long runId) {
        if (template == null) return "";
        String apiBaseUrl = "http://localhost:" + httpPort + "/api/v1";
        return template
                .replace("{{jobName}}", job.name != null ? job.name : "")
                .replace("{{jobId}}", String.valueOf(job.id))
                .replace("{{runId}}", String.valueOf(runId))
                .replace("{{apiBaseUrl}}", apiBaseUrl);
    }

    private List<String> resolveAllowedTools(ScheduledJobEntity job) {
        if (job.allowedTools != null && !job.allowedTools.isBlank()) {
            return toolsetResolver.resolve(job.allowedTools);
        }
        return List.of();
    }

    private Map<String, String> buildEnvironment(String customEnvironmentJson) {
        if (environmentResolver.hasCustomEnvironment(customEnvironmentJson)) {
            return environmentResolver.resolve(customEnvironmentJson);
        }
        Map<String, String> env = new java.util.HashMap<>();
        for (SecretEntity secret : SecretEntity.<SecretEntity>listAll()) {
            try {
                env.put(secret.name, encryptionService.decrypt(secret.encryptedValue));
            } catch (Exception e) {
                LOG.warnf("Failed to decrypt secret '%s' — skipping", secret.name);
            }
        }
        return env;
    }

    private String buildScriptExecutionLog(ScheduledJobEntity job, String resolvedScript,
                                            String output, int exitCode,
                                            Instant startTime, long durationMs) {
        StringBuilder log = new StringBuilder();
        log.append("═══════════════════════════════════════════════════════════════\n");
        log.append("  Scheduled Job Script Execution Log\n");
        log.append("═══════════════════════════════════════════════════════════════\n");
        log.append("  Job:       ").append(job.name).append("\n");
        log.append("  Job ID:    #").append(job.id).append("\n");
        log.append("  Started:   ").append(startTime).append("\n");
        log.append("  Duration:  ").append(durationMs).append(" ms\n");
        log.append("  Exit Code: ").append(exitCode).append("\n");
        log.append("  Status:    ").append(exitCode == 0 ? "SUCCESS" : "FAILED").append("\n");
        log.append("═══════════════════════════════════════════════════════════════\n\n");

        log.append("── Resolved Script ────────────────────────────────────────────\n");
        log.append(resolvedScript.strip()).append("\n\n");

        log.append("── Output ─────────────────────────────────────────────────────\n");
        if (output != null && !output.isBlank()) {
            log.append(output.strip()).append("\n");
        } else {
            log.append("(no output)\n");
        }

        log.append("\n═══════════════════════════════════════════════════════════════\n");
        return log.toString();
    }

    private void logActivity(String entryType, String summary) {
        ActivityLogEntity log = new ActivityLogEntity();
        log.entryType = entryType;
        log.summary = summary != null && summary.length() > 1024
                ? summary.substring(0, 1021) + "..."
                : summary;
        log.createdOn = Instant.now();
        log.persist();
    }
}
