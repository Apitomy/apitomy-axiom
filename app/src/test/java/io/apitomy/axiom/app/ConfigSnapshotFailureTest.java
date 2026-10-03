package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ReportDefinitionEntity;
import io.apitomy.axiom.core.entities.ReportEntity;
import io.apitomy.axiom.core.entities.ScheduledJobEntity;
import io.apitomy.axiom.core.entities.ScheduledJobRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies that a failure to record a configuration snapshot never blocks the creation of
 * scheduled job runs or reports (#426).
 */
@QuarkusTest
class ConfigSnapshotFailureTest {

    @InjectMock
    ConfigSnapshotService snapshots;

    @InjectMock
    ScheduledJobQueueConsumer jobQueue;

    @InjectMock
    ReportQueueConsumer reportQueue;

    @Inject
    ScheduledJobScheduler jobScheduler;

    @Inject
    ReportScheduler reportScheduler;

    @BeforeEach
    void failSnapshots() {
        Mockito.when(snapshots.recordJobVersion(ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("simulated snapshot failure"));
        Mockito.when(snapshots.recordReportVersion(ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("simulated snapshot failure"));
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            ScheduledJobRunEntity.deleteAll();
            ScheduledJobEntity.deleteAll();
            ReportEntity.deleteAll();
            ReportDefinitionEntity.deleteAll();
        });
    }

    @Test
    void scheduledTickCreatesAllRunsWhenSnapshotFails() {
        long first = createJob(true);
        long second = createJob(true);

        jobScheduler.createPendingRuns();

        for (long jobId : List.of(first, second)) {
            List<ScheduledJobRunEntity> runs = QuarkusTransaction.requiringNew()
                    .call(() -> ScheduledJobRunEntity.<ScheduledJobRunEntity>list("jobId", jobId));
            assertEquals(1, runs.size());
            assertNull(runs.get(0).configVersionId);
        }
    }

    @Test
    void manualRunSucceedsWhenSnapshotFails() {
        long jobId = createJob(false);
        given().when().post("/api/v1/scheduled-jobs/" + jobId + "/run").then().statusCode(200);
    }

    @Test
    void reportsAreCreatedWhenSnapshotFails() {
        long first = createDefinition(true);
        long second = createDefinition(true);

        reportScheduler.createPendingReports();

        for (long defId : List.of(first, second)) {
            assertEquals(1L, (long) QuarkusTransaction.requiringNew()
                    .call(() -> ReportEntity.count("definitionId", defId)));
        }
        long manual = createDefinition(false);
        given().when().post("/api/v1/reports/definitions/" + manual + "/run").then().statusCode(200);
    }

    private long createJob(boolean due) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ScheduledJobEntity job = new ScheduledJobEntity();
            job.name = "job-" + UUID.randomUUID();
            job.enabled = due;
            job.nextRunAt = due ? Instant.now().minus(Duration.ofMinutes(1)) : null;
            job.schedule = "none";
            job.executionMode = "agent";
            job.promptTemplate = "Prompt";
            job.createdOn = Instant.now();
            job.updatedOn = Instant.now();
            job.persist();
            return job.id;
        });
    }

    private long createDefinition(boolean due) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ReportDefinitionEntity def = new ReportDefinitionEntity();
            def.name = "report-" + UUID.randomUUID();
            def.schedule = "daily";
            def.scheduleTime = "08:00";
            def.promptTemplate = "Prompt";
            def.enabled = due;
            def.nextRunAt = due ? Instant.now().minus(Duration.ofMinutes(1)) : null;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();
            return def.id;
        });
    }
}
