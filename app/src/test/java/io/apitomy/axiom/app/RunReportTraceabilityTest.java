package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.ReportDefinitionEntity;
import io.apitomy.axiom.core.entities.ReportEntity;
import io.apitomy.axiom.core.entities.ScheduledJobEntity;
import io.apitomy.axiom.core.entities.ScheduledJobRunEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.core.tracing.TraceService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that scheduled job runs (#424) and reports (#425) are linked to their trace, AI usage
 * and activity rows, and that their trigger is recorded.
 */
@QuarkusTest
class RunReportTraceabilityTest {

    @InjectMock
    AgentPool agentPool;

    @Inject
    ScheduledJobExecutionService scheduledJobExecutionService;

    @Inject
    ReportExecutionService reportExecutionService;

    @Inject
    ScheduledJobScheduler scheduledJobScheduler;

    @Inject
    ReportScheduler reportScheduler;

    @Inject
    TraceService traceService;

    @BeforeEach
    void noAgentsAvailable() {
        Mockito.when(agentPool.tryLease(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Optional.empty());
    }

    // ── Scheduled job runs: correlation columns ─────────────────────

    @Test
    void agentRunActivityAndAiUsageCarryRunId() {
        Long[] ids = createJobAndRun("agent", null, null);
        TraceContext ctx = traceService.createTrace("scheduled-job-execution", "run link",
                null, null, null, "scheduled-job-triggered", "root", "scheduled-job-run", ids[1]);

        scheduledJobExecutionService.markRunning(ids[1], ctx.traceId());
        scheduledJobExecutionService.onAgentCompleted(ids[1], ids[0], AgentResult.success("ok"),
                ctx, null);

        List<ActivityLogEntity> rows = activityForRun(ids[1]);
        assertEquals(List.of("scheduled-job-running", "scheduled-job-completed"),
                rows.stream().map(r -> r.entryType).toList());
        List<AiUsageEntity> usage = QuarkusTransaction.requiringNew().call(() ->
                AiUsageEntity.<AiUsageEntity>list("scheduledJobRunId", ids[1]));
        assertEquals(1, usage.size());
        assertEquals(ctx.traceId(), usage.get(0).traceId);
    }

    @Test
    void agentRunWithoutAgentWritesFailedActivityWithRunId() {
        Long[] ids = createJobAndRun("agent", null, null);
        executeRun(ids);

        List<ActivityLogEntity> rows = activityForRun(ids[1]);
        assertTrue(rows.stream().anyMatch(r -> "scheduled-job-failed".equals(r.entryType)));
    }

    @Test
    void scriptRunCreatesClosedTraceWithScriptNode() {
        Long[] ids = createJobAndRun("script", "echo hello", null);
        executeRun(ids);

        ScheduledJobRunEntity run = findRun(ids[1]);
        assertEquals("Completed", run.status);
        assertNotNull(run.traceId, "Script runs must be traced");
        TraceEntity trace = findTrace(run.traceId);
        assertEquals("completed", trace.status);
        assertNotNull(trace.completedOn);

        List<TraceNodeEntity> nodes = nodes(run.traceId);
        TraceNodeEntity root = nodes.get(0);
        assertNull(root.parentNodeId);
        assertEquals("scheduled-job-run", root.entityType);
        assertEquals(String.valueOf(ids[1]), root.entityId);
        TraceNodeEntity script = nodes.stream()
                .filter(n -> "scheduled-job-script-executed".equals(n.nodeType))
                .findFirst().orElse(null);
        assertNotNull(script, "Script runs must have a script-execution node");
        assertEquals("completed", script.status);
        assertEquals(root.id, script.parentNodeId);
        assertFalse(nodes.stream().anyMatch(n -> "scheduled-job-ai-invoked".equals(n.nodeType)),
                "Script runs have no AI node");

        List<ActivityLogEntity> rows = activityForRun(ids[1]);
        assertEquals(List.of("scheduled-job-running", "scheduled-job-completed"),
                rows.stream().map(r -> r.entryType).toList());
        rows.forEach(r -> assertEquals(run.traceId, r.traceId));
    }

    @Test
    void failingScriptRunClosesTraceAsFailed() {
        Long[] ids = createJobAndRun("script", "exit 3", null);
        executeRun(ids);

        ScheduledJobRunEntity run = findRun(ids[1]);
        assertEquals("Failed", run.status);
        assertNotNull(run.traceId);
        assertEquals("failed", findTrace(run.traceId).status);
        assertTrue(nodes(run.traceId).stream().anyMatch(n ->
                "scheduled-job-script-executed".equals(n.nodeType) && "failed".equals(n.status)));
        assertTrue(activityForRun(ids[1]).stream()
                .anyMatch(r -> "scheduled-job-failed".equals(r.entryType)
                        && run.traceId.equals(r.traceId)));
    }

    @Test
    void scriptRunWithoutTemplateClosesTraceAsFailed() {
        Long[] ids = createJobAndRun("script", null, null);
        executeRun(ids);

        ScheduledJobRunEntity run = findRun(ids[1]);
        assertEquals("Failed", run.status);
        assertNotNull(run.traceId);
        assertEquals("failed", findTrace(run.traceId).status);
    }

    // ── Scheduled job runs: trigger ──────────────────────────────────

    @Test
    void scheduledRunIsTriggeredByScheduler() {
        Long[] ids = createJobAndRun("agent", null, Instant.now().minus(1, ChronoUnit.MINUTES));
        List<Long> runIds = QuarkusTransaction.requiringNew().call(() ->
                scheduledJobScheduler.createPendingRuns());

        ScheduledJobRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                ScheduledJobRunEntity.<ScheduledJobRunEntity>find(
                        "jobId = ?1 and id <> ?2", ids[0], ids[1]).firstResult());
        assertNotNull(run);
        assertTrue(runIds.contains(run.id));
        assertEquals("scheduled", run.trigger);
        assertEquals("scheduler", run.triggeredBy);
        assertNull(run.triggeredByTraceId);
    }

    @Test
    void manualRunIsTriggeredByManual() {
        Long[] ids = createJobAndRun("agent", null, null);
        given().when().post("/api/v1/scheduled-jobs/" + ids[0] + "/run")
                .then().statusCode(200)
                .body("trigger", equalTo("manual"))
                .body("triggeredBy", equalTo("manual"));
    }

    @Test
    void manualRunFromAgentRecordsCallerTrace() {
        Long[] ids = createJobAndRun("agent", null, null);
        TraceContext caller = traceService.createTrace("agent-session", "caller", null, null, null,
                "agent", "caller root", null, null);
        int runId = given().header("X-Axiom-Trace-Id", caller.traceId().toString())
                .when().post("/api/v1/scheduled-jobs/" + ids[0] + "/run")
                .then().statusCode(200)
                .body("triggeredBy", equalTo("manual"))
                .body("triggeredByTraceId", equalTo(caller.traceId().toString()))
                .extract().path("id");
        assertEquals(caller.traceId(), findRun((long) runId).triggeredByTraceId);
    }

    // ── Scheduled job runs: API filters ──────────────────────────────

    @Test
    void activityAndUsageCanBeFilteredByRun() {
        Long[] ids = createJobAndRun("agent", null, null);
        TraceContext ctx = traceService.createTrace("scheduled-job-execution", "run filter",
                null, null, null, "scheduled-job-triggered", "root", "scheduled-job-run", ids[1]);
        scheduledJobExecutionService.markRunning(ids[1], ctx.traceId());
        scheduledJobExecutionService.onAgentCompleted(ids[1], ids[0], AgentResult.success("ok"),
                ctx, null);

        given().queryParam("filterScheduledJobRunId", ids[1])
                .when().get("/api/v1/activity")
                .then().statusCode(200)
                .body("totalCount", equalTo(2))
                .body("items.scheduledJobRunId", everyItem(equalTo(ids[1].intValue())));
        given().queryParam("filterScheduledJobRunId", ids[1])
                .when().get("/api/v1/usage/ai")
                .then().statusCode(200)
                .body("totalCount", equalTo(1))
                .body("items[0].scheduledJobRunId", equalTo(ids[1].intValue()));
    }

    @Test
    void tracesCanBeFilteredByRun() {
        Long[] ids = createJobAndRun("script", "echo traced", null);
        executeRun(ids);
        UUID traceId = findRun(ids[1]).traceId;

        given().queryParam("filterScheduledJobRunId", ids[1])
                .when().get("/api/v1/traces")
                .then().statusCode(200)
                .body("totalCount", equalTo(1))
                .body("items[0].traceId", equalTo(traceId.toString()));
    }

    @Test
    void malformedRunAndReportFiltersReturn400() {
        given().queryParam("filterScheduledJobRunId", "abc").when().get("/api/v1/activity")
                .then().statusCode(400);
        given().queryParam("filterReportId", "abc").when().get("/api/v1/activity")
                .then().statusCode(400);
        given().queryParam("filterScheduledJobRunId", "abc").when().get("/api/v1/usage/ai")
                .then().statusCode(400);
        given().queryParam("filterReportId", "abc").when().get("/api/v1/usage/ai")
                .then().statusCode(400);
        given().queryParam("filterScheduledJobRunId", "abc").when().get("/api/v1/traces")
                .then().statusCode(400);
    }

    // ── Reports ──────────────────────────────────────────────────────

    @Test
    void reportActivityAndAiUsageCarryReportAndDefinitionIds() {
        Long[] ids = createDefinitionAndReport(null);
        TraceContext ctx = traceService.createTrace("report-generation", "report link", null, null,
                ids[1], "report-triggered", "root", "report", ids[1]);
        Instant now = Instant.now();

        reportExecutionService.markGenerating(ids[1], now, now, ctx.traceId());
        reportExecutionService.onReportCompleted(ids[1], ids[0], AgentResult.success("# Title"),
                ctx, null);

        List<ActivityLogEntity> rows = activityForReport(ids[1]);
        assertEquals(List.of("report-generating", "report-completed"),
                rows.stream().map(r -> r.entryType).toList());
        rows.forEach(r -> assertEquals(ids[0], r.reportDefinitionId));
        List<AiUsageEntity> usage = QuarkusTransaction.requiringNew().call(() ->
                AiUsageEntity.<AiUsageEntity>list("reportId", ids[1]));
        assertEquals(1, usage.size());

        given().queryParam("filterReportId", ids[1])
                .when().get("/api/v1/activity")
                .then().statusCode(200)
                .body("totalCount", equalTo(2))
                .body("items.reportId", everyItem(equalTo(ids[1].intValue())))
                .body("items.reportDefinitionId", everyItem(equalTo(ids[0].intValue())));
        given().queryParam("filterReportId", ids[1])
                .when().get("/api/v1/usage/ai")
                .then().statusCode(200)
                .body("totalCount", equalTo(1))
                .body("items[0].reportId", equalTo(ids[1].intValue()));
    }

    @Test
    void failedReportActivityCarriesReportAndDefinitionIds() {
        Long[] ids = createDefinitionAndReport(null);
        ReportDefinitionEntity def = QuarkusTransaction.requiringNew().call(() ->
                ReportDefinitionEntity.<ReportDefinitionEntity>findById(ids[0]));
        reportExecutionService.generateReport(def, ids[1]);

        List<ActivityLogEntity> rows = activityForReport(ids[1]);
        assertTrue(rows.stream().anyMatch(r -> "report-failed".equals(r.entryType)
                && ids[0].equals(r.reportDefinitionId)));
    }

    @Test
    void scheduledReportIsTriggeredByScheduler() {
        Long[] ids = createDefinitionAndReport(Instant.now().minus(1, ChronoUnit.MINUTES));
        QuarkusTransaction.requiringNew().call(() -> reportScheduler.createPendingReports());

        ReportEntity report = QuarkusTransaction.requiringNew().call(() ->
                ReportEntity.<ReportEntity>find("definitionId = ?1 and id <> ?2", ids[0], ids[1])
                        .firstResult());
        assertNotNull(report);
        assertEquals("scheduled", report.trigger);
        assertEquals("scheduler", report.triggeredBy);
        assertNull(report.triggeredByTraceId);

        given().when().get("/api/v1/reports/" + report.id)
                .then().statusCode(200)
                .body("trigger", equalTo("scheduled"))
                .body("triggeredBy", equalTo("scheduler"));
    }

    @Test
    void manualReportFromAgentRecordsCallerTrace() {
        Long[] ids = createDefinitionAndReport(null);
        TraceContext caller = traceService.createTrace("agent-session", "caller", null, null, null,
                "agent", "caller root", null, null);
        given().header("X-Axiom-Trace-Id", caller.traceId().toString())
                .when().post("/api/v1/reports/definitions/" + ids[0] + "/run")
                .then().statusCode(200)
                .body("trigger", equalTo("manual"))
                .body("triggeredBy", equalTo("manual"))
                .body("triggeredByTraceId", equalTo(caller.traceId().toString()));
        given().when().post("/api/v1/reports/definitions/" + ids[0] + "/run")
                .then().statusCode(200)
                .body("trigger", equalTo("manual"))
                .body("triggeredBy", equalTo("manual"));
        List<String> triggers = QuarkusTransaction.requiringNew().call(() ->
                ReportEntity.<ReportEntity>list("definitionId = ?1 and id <> ?2", ids[0], ids[1])
                        .stream().map(r -> r.trigger).toList());
        assertEquals(List.of("manual", "manual"), triggers);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private Long[] createJobAndRun(String mode, String script, Instant nextRunAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ScheduledJobEntity job = new ScheduledJobEntity();
            job.name = "traceability-job-" + UUID.randomUUID();
            job.enabled = nextRunAt != null;
            job.schedule = "daily";
            job.executionMode = mode;
            job.promptTemplate = "Do something";
            job.scriptTemplate = script;
            job.nextRunAt = nextRunAt;
            job.createdOn = Instant.now();
            job.updatedOn = Instant.now();
            job.persist();
            ScheduledJobRunEntity run = new ScheduledJobRunEntity();
            run.jobId = job.id;
            run.status = "Pending";
            run.trigger = "manual";
            run.triggeredBy = "manual";
            run.createdOn = Instant.now();
            run.persist();
            return new Long[] { job.id, run.id };
        });
    }

    private Long[] createDefinitionAndReport(Instant nextRunAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ReportDefinitionEntity def = new ReportDefinitionEntity();
            def.name = "traceability-report-" + UUID.randomUUID();
            def.schedule = "daily";
            def.promptTemplate = "Report on {{repositories}}";
            def.enabled = nextRunAt != null;
            def.nextRunAt = nextRunAt;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();
            ReportEntity report = new ReportEntity();
            report.definitionId = def.id;
            report.status = "Pending";
            report.trigger = "manual";
            report.triggeredBy = "manual";
            report.createdOn = Instant.now();
            report.persist();
            return new Long[] { def.id, report.id };
        });
    }

    private void executeRun(Long[] ids) {
        ScheduledJobEntity job = QuarkusTransaction.requiringNew().call(() ->
                ScheduledJobEntity.<ScheduledJobEntity>findById(ids[0]));
        scheduledJobExecutionService.executeRun(job, ids[1]);
    }

    private ScheduledJobRunEntity findRun(Long runId) {
        return QuarkusTransaction.requiringNew().call(() ->
                ScheduledJobRunEntity.<ScheduledJobRunEntity>findById(runId));
    }

    private TraceEntity findTrace(UUID traceId) {
        return QuarkusTransaction.requiringNew().call(() ->
                TraceEntity.<TraceEntity>findById(traceId));
    }

    private List<TraceNodeEntity> nodes(UUID traceId) {
        return QuarkusTransaction.requiringNew().call(() ->
                TraceNodeEntity.<TraceNodeEntity>list("traceId = ?1 order by id", traceId));
    }

    private List<ActivityLogEntity> activityForRun(Long runId) {
        return QuarkusTransaction.requiringNew().call(() ->
                ActivityLogEntity.<ActivityLogEntity>list(
                        "scheduledJobRunId = ?1 order by id", runId));
    }

    private List<ActivityLogEntity> activityForReport(Long reportId) {
        return QuarkusTransaction.requiringNew().call(() ->
                ActivityLogEntity.<ActivityLogEntity>list("reportId = ?1 order by id", reportId));
    }
}
