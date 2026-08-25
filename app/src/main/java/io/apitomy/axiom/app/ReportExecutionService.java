package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.core.tracing.TraceService;
import io.apitomy.axiom.core.util.SlugUtil;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.axiom.core.entities.EventSourceEntity;
import io.apitomy.axiom.core.entities.ReportDefinitionEntity;
import io.apitomy.axiom.core.entities.ReportEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.services.EncryptionService;
import io.apitomy.axiom.core.services.EnvironmentResolver;
import io.apitomy.axiom.core.services.ToolsetResolver;
import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.agents.spi.AgentRegistry;
import io.apitomy.axiom.agents.spi.AgentRequest;
import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.app.AgentLease;
import io.apitomy.axiom.app.AgentPool;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.UUID;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Executes report generation by invoking the AI engine with a prompt template,
 * read-only tools, and GitHub MCP tools. Stores the generated markdown content
 * in the ReportEntity.
 */
@ApplicationScoped
public class ReportExecutionService {

    private static final Logger LOG = Logger.getLogger(ReportExecutionService.class);

    @Inject
    Event<SseEvent> sseEvents;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AgentPool agentPool;

    @Inject
    AgentRegistry agentRegistry;

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

    @ConfigProperty(name = "axiom.agent.claude-code.model")
    Optional<String> model;

    @ConfigProperty(name = "axiom.agent.claude-code.timeout-seconds", defaultValue = "600")
    int timeoutSeconds;

    private static final String SYSTEM_PROMPT = """
            You are a report generator for Apicurio Axiom. Your job is to gather \
            information from GitHub repositories and produce a well-formatted markdown \
            report.

            Guidelines:
            - Use the provided tools (gh CLI) to query GitHub for issues, PRs, and activity
            - Format the report as clean, readable markdown with tables, links, and sections
            - Include clickable GitHub links for all issues and PRs: [#N](https://github.com/owner/repo/issues/N)
            - Start with a summary section highlighting key metrics
            - Group information by repository if multiple repos are covered
            - Use emoji for visual status indicators where appropriate
            - Be concise but thorough — highlight what matters
            """;

    /**
     * Generates a report asynchronously. Updates the ReportEntity with the result.
     *
     * @param definition the report definition
     * @param reportId the report entity ID to update
     */
    public void generateReport(ReportDefinitionEntity definition, Long reportId) {
        LOG.infof("Generating report '%s' (ID: %d)", definition.name, reportId);

        // Load DB-backed context (toolset resolution, repository lookup, trace
        // creation) in a short independent transaction. generateReport() itself
        // is not @Transactional (it's called directly from the queue consumer's
        // worker loop, outside any request/transaction scope), so any Panache
        // queries here — like ToolsetResolver.resolve()'s ToolsetEntity.find()
        // — would otherwise silently run without an active Hibernate session.
        GenerationContext genCtx = QuarkusTransaction.requiringNew().call(() -> {
            TraceContext ctx = null;
            Long nodeId = null;
            try {
                ctx = traceService.createTrace("report-generation",
                        "Generating report: " + definition.name,
                        null, null, reportId,
                        "report-triggered", "Report triggered: " + definition.name,
                        "report", reportId);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to create trace for report %d", reportId);
            }

            String repos = resolveRepositories(definition);
            List<String> tools = resolveAllowedTools(definition);
            Map<String, String> resolvedEnv = buildEnvironment(definition.environment);

            if (ctx != null) {
                try {
                    resolvedEnv.put("AXIOM_TRACE_ID", ctx.traceId().toString());
                    nodeId = traceService.addNode(ctx, "report-ai-invoked", "in-progress",
                            "Report execution (AI agent)", null, null);
                    resolvedEnv.put("AXIOM_PARENT_NODE_ID", String.valueOf(nodeId));
                } catch (Exception e) {
                    LOG.warnf(e, "Failed to add AI invocation trace node for report %d", reportId);
                }
            }

            return new GenerationContext(ctx, nodeId, repos, tools, resolvedEnv);
        });

        TraceContext traceCtx = genCtx.traceCtx();
        Long aiNodeId = genCtx.aiNodeId();
        String repoList = genCtx.repoList();
        List<String> allowedTools = genCtx.allowedTools();
        Map<String, String> env = genCtx.env();

        LOG.infof("Report %d resolved %d allowed tool(s): %s", reportId,
                allowedTools.size(), allowedTools);

        // Compute time range (null when no time window is configured)
        Instant now = Instant.now();
        boolean hasTimeWindow = definition.timeWindow != null && !definition.timeWindow.isBlank();
        Instant rangeStart = hasTimeWindow ? computeTimeRangeStart(definition, now) : null;
        Instant rangeEnd = hasTimeWindow ? now : null;

        // Build prompt from template
        DateTimeFormatter fmt = DateTimeFormatter.ISO_INSTANT;
        String humanWindow = hasTimeWindow
                ? describeTimeWindow(definition.timeWindow, rangeStart, rangeEnd) : "";

        String prompt = definition.promptTemplate
                .replace("{{repositories}}", repoList)
                .replace("{{timeRangeStart}}", rangeStart != null ? fmt.format(rangeStart) : "")
                .replace("{{timeRangeEnd}}", rangeEnd != null ? fmt.format(rangeEnd) : "")
                .replace("{{timeWindow}}", humanWindow);

        // Generate MCP config with report-related tools and trace env vars
        Path mcpConfig = mcpConfigGenerator.generateMcpConfig(reportId, env, allowedTools);
        // Ask the active agent's MCP manager to configure its MCP servers
        // for this task too. For file-config agents (Claude Code, Copilot)
        // this is a no-op re-generation; for OpenCode this is REQUIRED since
        // it registers MCP servers dynamically via HTTP rather than a config
        // file, and generateMcpConfig() above only produces a file that
        // OpenCode ignores.
        try {
            agentRegistry.getMcpManager(null).configureMcpServers(reportId, env, allowedTools);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to configure agent MCP servers for report %d", reportId);
        }

        // Build agent request
        int effectiveTimeout = definition.timeoutSeconds != null
                ? definition.timeoutSeconds : timeoutSeconds;
        AgentRequest agentRequest = AgentRequest.builder()
                .prompt(prompt)
                .systemPrompt(SYSTEM_PROMPT)
                .allowedTools(allowedTools)
                .timeoutSeconds(effectiveTimeout)
                .maxSteps(30)
                .model(model.orElse(null))
                .environment(env)
                .mcpConfigFile(mcpConfig)
                .build();

        // Acquire an agent lease from the pool
        String slug = definition.slug != null ? definition.slug : SlugUtil.slugify(definition.name);
        String capability = "report:" + slug;
        AgentLease lease = agentPool.tryLease(capability, null, "report", reportId)
                .orElse(null);
        if (lease == null) {
            LOG.warnf("All agents busy, report %d stays pending", reportId);
            return;
        }

        // Mark as generating (stores trace ID on the report entity)
        UUID traceId = traceCtx != null ? traceCtx.traceId() : null;
        markGenerating(reportId, rangeStart, rangeEnd, traceId);

        // Capture the current classloader so callbacks use the correct one
        // after Quarkus dev-mode live reloads.
        ClassLoader contextCl = Thread.currentThread().getContextClassLoader();

        // Capture trace context for async lambdas
        final TraceContext finalTraceCtx = traceCtx;
        final Long finalAiNodeId = aiNodeId;

        lease.agent().execute(agentRequest)
                .thenAccept(result -> {
                    Thread.currentThread().setContextClassLoader(contextCl);
                    agentPool.release(lease);
                    onReportCompleted(reportId, definition.id, result,
                            finalTraceCtx, finalAiNodeId);
                })
                .exceptionally(throwable -> {
                    Thread.currentThread().setContextClassLoader(contextCl);
                    agentPool.release(lease);
                    LOG.errorf(throwable, "Report %d generation failed unexpectedly", reportId);
                    failReport(reportId, "Unexpected error: " + throwable.getMessage(),
                            finalTraceCtx, finalAiNodeId);
                    return null;
                });
    }

    /**
     * Holds the DB-derived context needed to launch report generation, computed
     * inside a short transaction before the (non-transactional) AI engine call.
     */
    private record GenerationContext(
            TraceContext traceCtx,
            Long aiNodeId,
            String repoList,
            List<String> allowedTools,
            Map<String, String> env) {
    }

    @Transactional
    void markGenerating(Long reportId, Instant rangeStart, Instant rangeEnd, UUID traceId) {
        ReportEntity report = ReportEntity.findById(reportId);
        if (report != null) {
            report.status = "Generating";
            report.timeRangeStart = rangeStart;
            report.timeRangeEnd = rangeEnd;
            report.traceId = traceId;

            sseEvents.fire(SseEvent.reportUpdated(reportId, report.status));

            ReportDefinitionEntity def = ReportDefinitionEntity.findById(report.definitionId);
            String defName = def != null ? def.name : "Report #" + reportId;
            logActivity("report-generating",
                    "Report generation started: " + defName);
        }
    }

    @Transactional
    void onReportCompleted(Long reportId, Long definitionId, AgentResult result,
            TraceContext traceCtx, Long aiNodeId) {
        ReportEntity report = ReportEntity.findById(reportId);
        if (report == null) return;

        ReportDefinitionEntity def = ReportDefinitionEntity.findById(definitionId);

        if (result.success()) {
            report.status = "Completed";
            report.content = result.output();
            if (def != null && def.titleTemplate != null && !def.titleTemplate.isBlank()) {
                report.title = resolveTitleTemplate(def.titleTemplate, def);
            } else {
                report.title = extractTitle(result.output());
            }
        } else {
            report.status = "Failed";
            report.content = "Report generation failed: " + result.output();
        }
        report.executionLog = result.executionLog();
        report.costUsd = result.costUsd();
        report.completedOn = Instant.now();
        report.durationMs = java.time.Duration.between(report.createdOn, report.completedOn).toMillis();

        LOG.infof("Report %d %s (cost: $%s)",
                reportId, report.status,
                result.costUsd() != null ? String.format("%.4f", result.costUsd()) : "n/a");

        // Record AI usage
        AiUsageEntity usage = new AiUsageEntity();
        usage.invocationType = "report";
        usage.actionType = "generate-report";
        usage.costUsd = result.costUsd();
        usage.inputTokens = result.inputTokens();
        usage.outputTokens = result.outputTokens();
        usage.createdOn = Instant.now();
        usage.persist();

        // Log activity
        String defName = def != null ? def.name : "Report #" + reportId;
        String statusText = result.success() ? "completed" : "failed";
        String summary = "Report " + statusText + ": " + defName;
        if (report.durationMs != null) {
            summary += String.format(" (%ds)", report.durationMs / 1000);
        }
        if (result.costUsd() != null) {
            summary += String.format(" — $%.4f", result.costUsd());
        }
        logActivity("report-" + statusText, summary);
        sseEvents.fire(SseEvent.reportUpdated(reportId, report.status));
        mcpConfigGenerator.cleanupTempFiles(reportId);

        // Complete the trace
        if (traceCtx != null) {
            try {
                if (aiNodeId != null) {
                    traceService.completeNode(aiNodeId, statusText, "report", reportId);
                }
                traceService.completeTrace(traceCtx.traceId(),
                        result.success() ? "completed" : "failed");
            } catch (Exception e) {
                LOG.warnf(e, "Failed to complete trace for report %d", reportId);
            }
        }
    }

    @Transactional
    void failReport(Long reportId, String reason,
            TraceContext traceCtx, Long aiNodeId) {
        ReportEntity report = ReportEntity.findById(reportId);
        if (report != null) {
            report.status = "Failed";
            report.content = reason;
            report.completedOn = Instant.now();
            report.durationMs = java.time.Duration.between(report.createdOn, report.completedOn).toMillis();

            ReportDefinitionEntity def = ReportDefinitionEntity.findById(report.definitionId);
            String defName = def != null ? def.name : "Report #" + reportId;
            logActivity("report-failed", "Report failed: " + defName + " — " + reason);
            sseEvents.fire(SseEvent.reportUpdated(reportId, "Failed"));
            mcpConfigGenerator.cleanupTempFiles(reportId);
        }

        // Complete the trace
        if (traceCtx != null) {
            try {
                if (aiNodeId != null) {
                    traceService.completeNode(aiNodeId, "failed", "report", reportId);
                }
                traceService.completeTrace(traceCtx.traceId(), "failed");
            } catch (Exception e) {
                LOG.warnf(e, "Failed to complete trace for failed report %d", reportId);
            }
        }
    }

    private Instant computeTimeRangeStart(ReportDefinitionEntity definition, Instant now) {
        if (definition.lastRunAt != null && "since-last-run".equals(definition.timeWindow)) {
            return definition.lastRunAt;
        }
        return switch (definition.timeWindow) {
            case "last-24h", "since-last-run" -> now.minus(24, ChronoUnit.HOURS);
            case "last-7d" -> now.minus(7, ChronoUnit.DAYS);
            case "last-30d" -> now.minus(30, ChronoUnit.DAYS);
            default -> now.minus(24, ChronoUnit.HOURS);
        };
    }

    /**
     * Resolves the allowed tools for a report definition, expanding any
     * {@code @ToolsetName} references into their constituent tools.
     * Falls back to default report tools if none are configured.
     */
    private List<String> resolveAllowedTools(ReportDefinitionEntity definition) {
        if (definition.allowedTools != null && !definition.allowedTools.isBlank()) {
            return toolsetResolver.resolve(definition.allowedTools);
        }
        return List.of();
    }

    /**
     * Resolves the list of repositories for a report by querying all GitHub
     * event sources and building the repository list from their configuration.
     *
     * @param definition the report definition
     * @return a comma-separated list of owner/repo strings
     */
    private String resolveRepositories(ReportDefinitionEntity definition) {
        // Query all GitHub event sources and build the repository list
        List<EventSourceEntity> all = EventSourceEntity.list("sourceType", "github");
        return all.stream()
                .map(source -> {
                    try {
                        JsonNode config = objectMapper.readTree(source.configuration);
                        String owner = config.path("owner").asText("");
                        String name = config.path("name").asText("");
                        return owner + "/" + name;
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(s -> s != null && !s.equals("/"))
                .reduce((a, b) -> a + ", " + b)
                .orElse("(no repositories configured)");
    }

    private String describeTimeWindow(String timeWindow, Instant start, Instant end) {
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("MMMM d, yyyy")
                .withZone(ZoneId.systemDefault());
        return switch (timeWindow) {
            case "last-24h" -> "Last 24 hours (" + dateFmt.format(start) + " — " + dateFmt.format(end) + ")";
            case "last-7d" -> "Last 7 days (" + dateFmt.format(start) + " — " + dateFmt.format(end) + ")";
            case "last-30d" -> "Last 30 days (" + dateFmt.format(start) + " — " + dateFmt.format(end) + ")";
            case "since-last-run" -> "Since last run (" + dateFmt.format(start) + " — " + dateFmt.format(end) + ")";
            default -> dateFmt.format(start) + " — " + dateFmt.format(end);
        };
    }

    private String extractTitle(String markdownContent) {
        if (markdownContent == null) return "Untitled Report";
        for (String line : markdownContent.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }
        return "Report";
    }

    /**
     * Resolves a title template by replacing placeholders with actual values.
     *
     * @param template the title template string
     * @param def the report definition entity
     * @return the resolved title string
     */
    private String resolveTitleTemplate(String template, ReportDefinitionEntity def) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(ZoneId.systemDefault());
        String date = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String time = now.format(DateTimeFormatter.ofPattern("HH:mm"));
        String datetime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        return template
                .replace("{{name}}", def.name != null ? def.name : "")
                .replace("{{date}}", date)
                .replace("{{time}}", time)
                .replace("{{datetime}}", datetime)
                .replace("{{timeWindow}}", def.timeWindow != null ? def.timeWindow : "")
                .replace("{{schedule}}", def.schedule != null ? def.schedule : "");
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
                LOG.warnf("Failed to decrypt secret '%s' for report — skipping", secret.name);
            }
        }
        return env;
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
