package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ReportDefinitionEntity;
import io.apitomy.axiom.core.entities.ReportDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.ReportEntity;
import io.apitomy.axiom.core.entities.ScheduledJobEntity;
import io.apitomy.axiom.core.entities.ScheduledJobRunEntity;
import io.apitomy.axiom.core.entities.ScheduledJobVersionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that each scheduled job run and report records the configuration it ran with
 * (#426): snapshots are stored on scheduled and manual paths, survive later edits, are shared
 * by identical configurations, exclude secrets, are exposed by the API and are deleted with
 * their definition.
 */
@QuarkusTest
class ConfigSnapshotTest {

    @InjectMock
    ScheduledJobQueueConsumer jobQueue;

    @InjectMock
    ReportQueueConsumer reportQueue;

    @Inject
    ScheduledJobScheduler jobScheduler;

    @Inject
    ReportScheduler reportScheduler;

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            ScheduledJobRunEntity.deleteAll();
            ScheduledJobVersionEntity.deleteAll();
            ScheduledJobEntity.deleteAll();
            ReportEntity.deleteAll();
            ReportDefinitionVersionEntity.deleteAll();
            ReportDefinitionEntity.deleteAll();
        });
    }

    // ── Scheduled jobs ──────────────────────────────────────────────

    @Test
    void manualRunStoresSnapshotAndApiReturnsIt() {
        long jobId = createJob("You are a helper", "{\"TOKEN\":\"${secret:GH_TOKEN}\"}");

        long runId = given().when().post("/api/v1/scheduled-jobs/" + jobId + "/run")
                .then().statusCode(200).extract().jsonPath().getLong("id");

        ScheduledJobRunEntity run = findRun(runId);
        assertNotNull(run.configVersionId, "manual run must record a configuration version");

        given().when().get("/api/v1/scheduled-jobs/runs/" + runId + "/config")
                .then().statusCode(200)
                .body("versionId", equalTo(run.configVersionId.intValue()))
                .body("changed", is(false))
                .body("configHash", notNullValue())
                .body("fields.find { it.name == 'promptTemplate' }.value", equalTo("You are a helper"))
                .body("fields.find { it.name == 'model' }.value", equalTo("sonnet"))
                .body("fields.name", not(hasItem("name")))
                .body("fields.name", not(hasItem("schedule")))
                .body("fields.name", not(hasItem("enabled")));
    }

    @Test
    void scheduledRunStoresSnapshot() {
        long jobId = createJob("Scheduled prompt", null);
        QuarkusTransaction.requiringNew().run(() -> {
            ScheduledJobEntity job = ScheduledJobEntity.findById(jobId);
            job.enabled = true;
            job.nextRunAt = Instant.now().minus(Duration.ofMinutes(1));
        });

        jobScheduler.createPendingRuns();

        List<ScheduledJobRunEntity> runs = QuarkusTransaction.requiringNew()
                .call(() -> ScheduledJobRunEntity.<ScheduledJobRunEntity>list("jobId", jobId));
        assertEquals(1, runs.size());
        assertEquals("scheduled", runs.get(0).trigger);
        assertNotNull(runs.get(0).configVersionId);
    }

    @Test
    void editingJobKeepsOldSnapshotAndSetsChanged() {
        long jobId = createJob("Original prompt", null);
        long firstRun = manualJobRun(jobId);

        given().contentType(ContentType.JSON)
                .body("{\"name\":\"job-" + jobId + "\",\"schedule\":\"none\",\"executionMode\":\"agent\","
                        + "\"promptTemplate\":\"Edited prompt\",\"model\":\"sonnet\"}")
                .when().put("/api/v1/scheduled-jobs/" + jobId)
                .then().statusCode(200);

        given().when().get("/api/v1/scheduled-jobs/runs/" + firstRun + "/config")
                .then().statusCode(200)
                .body("changed", is(true))
                .body("fields.find { it.name == 'promptTemplate' }.value", equalTo("Original prompt"))
                .body("fields.find { it.name == 'promptTemplate' }.currentValue", equalTo("Edited prompt"))
                .body("fields.find { it.name == 'promptTemplate' }.changed", is(true))
                .body("fields.find { it.name == 'model' }.changed", is(false));

        long secondRun = manualJobRun(jobId);
        assertNotEquals(findRun(firstRun).configVersionId, findRun(secondRun).configVersionId);
        given().when().get("/api/v1/scheduled-jobs/runs/" + secondRun + "/config")
                .then().statusCode(200).body("changed", is(false));
    }

    @Test
    void identicalJobConfigsShareVersion() {
        long jobId = createJob("Same prompt", null);
        long first = manualJobRun(jobId);
        long second = manualJobRun(jobId);

        assertEquals(findRun(first).configVersionId, findRun(second).configVersionId);
        long versions = QuarkusTransaction.requiringNew()
                .call(() -> ScheduledJobVersionEntity.count("jobId", jobId));
        assertEquals(1, versions);
    }

    @Test
    void jobSnapshotExcludesSecretValues() {
        long jobId = createJob("Prompt",
                "{\"API_KEY\":\"sk-literal-credential\",\"TOKEN\":\"${secret:GH_TOKEN}\"}");
        long runId = manualJobRun(jobId);

        String snapshot = QuarkusTransaction.requiringNew().call(() -> {
            ScheduledJobVersionEntity v = ScheduledJobVersionEntity.findById(findRun(runId).configVersionId);
            return v.configSnapshot;
        });
        assertFalse(snapshot.contains("sk-literal-credential"), snapshot);
        assertTrue(snapshot.contains("${secret:GH_TOKEN}"), snapshot);
        assertTrue(snapshot.contains(ConfigSnapshotService.REDACTED), snapshot);

        given().when().get("/api/v1/scheduled-jobs/runs/" + runId + "/config")
                .then().statusCode(200)
                .body(not(containsString("sk-literal-credential")));
    }

    @Test
    void deletingJobRemovesVersions() {
        long jobId = createJob("Prompt", null);
        manualJobRun(jobId);

        given().when().delete("/api/v1/scheduled-jobs/" + jobId).then().statusCode(204);

        long versions = QuarkusTransaction.requiringNew()
                .call(() -> ScheduledJobVersionEntity.count("jobId", jobId));
        assertEquals(0, versions);
    }

    @Test
    void runWithoutSnapshotReturns404() {
        long jobId = createJob("Prompt", null);
        long runId = QuarkusTransaction.requiringNew().call(() -> {
            ScheduledJobRunEntity run = new ScheduledJobRunEntity();
            run.jobId = jobId;
            run.status = "Completed";
            run.trigger = "manual";
            run.createdOn = Instant.now();
            run.persist();
            return run.id;
        });
        given().when().get("/api/v1/scheduled-jobs/runs/" + runId + "/config").then().statusCode(404);
    }

    // ── Reports ─────────────────────────────────────────────────────

    @Test
    void manualReportStoresSnapshotAndApiReturnsIt() {
        long defId = createDefinition("Report prompt", "{\"KEY\":\"plain-secret-value\"}");

        long reportId = manualReport(defId);
        ReportEntity report = findReport(reportId);
        assertNotNull(report.configVersionId);

        given().when().get("/api/v1/reports/" + reportId + "/config")
                .then().statusCode(200)
                .body("changed", is(false))
                .body("fields.find { it.name == 'promptTemplate' }.value", equalTo("Report prompt"))
                .body("fields.find { it.name == 'timeWindow' }.value", equalTo("last-24h"))
                .body(not(containsString("plain-secret-value")));
    }

    @Test
    void scheduledReportStoresSnapshot() {
        long defId = createDefinition("Scheduled report", null);
        QuarkusTransaction.requiringNew().run(() -> {
            ReportDefinitionEntity def = ReportDefinitionEntity.findById(defId);
            def.enabled = true;
            def.nextRunAt = Instant.now().minus(Duration.ofMinutes(1));
        });

        reportScheduler.createPendingReports();

        List<ReportEntity> reports = QuarkusTransaction.requiringNew()
                .call(() -> ReportEntity.<ReportEntity>list("definitionId", defId));
        assertEquals(1, reports.size());
        assertNotNull(reports.get(0).configVersionId);
    }

    @Test
    void editingDefinitionKeepsOldReportSnapshotAndSharesIdenticalVersions() {
        long defId = createDefinition("Original report prompt", null);
        long first = manualReport(defId);
        long second = manualReport(defId);
        assertEquals(findReport(first).configVersionId, findReport(second).configVersionId);

        QuarkusTransaction.requiringNew().run(() -> {
            ReportDefinitionEntity def = ReportDefinitionEntity.findById(defId);
            def.promptTemplate = "Edited report prompt";
        });

        given().when().get("/api/v1/reports/" + first + "/config")
                .then().statusCode(200)
                .body("changed", is(true))
                .body("fields.find { it.name == 'promptTemplate' }.value", equalTo("Original report prompt"))
                .body("fields.find { it.name == 'promptTemplate' }.currentValue",
                        equalTo("Edited report prompt"));
    }

    @Test
    void deletingDefinitionRemovesVersions() {
        long defId = createDefinition("Prompt", null);
        manualReport(defId);

        given().when().delete("/api/v1/reports/definitions/" + defId).then().statusCode(204);

        long versions = QuarkusTransaction.requiringNew()
                .call(() -> ReportDefinitionVersionEntity.count("definitionId", defId));
        assertEquals(0, versions);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private long createJob(String prompt, String environment) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ScheduledJobEntity job = new ScheduledJobEntity();
            job.name = "job-" + UUID.randomUUID();
            job.enabled = false;
            job.schedule = "none";
            job.executionMode = "agent";
            job.promptTemplate = prompt;
            job.model = "sonnet";
            job.environment = environment;
            job.createdOn = Instant.now();
            job.updatedOn = Instant.now();
            job.persist();
            return job.id;
        });
    }

    private long manualJobRun(long jobId) {
        return given().when().post("/api/v1/scheduled-jobs/" + jobId + "/run")
                .then().statusCode(200).extract().jsonPath().getLong("id");
    }

    private ScheduledJobRunEntity findRun(long runId) {
        return QuarkusTransaction.requiringNew().call(() -> ScheduledJobRunEntity.findById(runId));
    }

    private long createDefinition(String prompt, String environment) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ReportDefinitionEntity def = new ReportDefinitionEntity();
            def.name = "report-" + UUID.randomUUID();
            def.schedule = "daily";
            def.scheduleTime = "08:00";
            def.timeWindow = "last-24h";
            def.promptTemplate = prompt;
            def.environment = environment;
            def.enabled = false;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();
            return def.id;
        });
    }

    private long manualReport(long defId) {
        return given().when().post("/api/v1/reports/definitions/" + defId + "/run")
                .then().statusCode(200).extract().jsonPath().getLong("id");
    }

    private ReportEntity findReport(long reportId) {
        return QuarkusTransaction.requiringNew().call(() -> ReportEntity.findById(reportId));
    }
}
