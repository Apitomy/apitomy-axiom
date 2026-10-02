package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.ScheduledJobEntity;
import io.apitomy.axiom.core.entities.ScheduledJobRunEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowEventSubscriptionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowWaitEntity;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.core.tracing.TraceService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * REST tests for the traces API that verify traces and trace nodes can be
 * correlated with stream events by UUID.
 */
@QuarkusTest
class TraceResourceTest {

    private static final String TRACES_PATH = "/api/v1/traces";

    @Inject
    TraceService traceService;

    private UUID eventId;

    private Long projectId;
    private Long definitionId;
    private Long runId;
    private Long jobId;
    private Long activityLogId;

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            TraceNodeEntity.deleteAll();
            TraceEntity.deleteAll();
            if (eventId != null) {
                StreamEventEntity.deleteById(eventId);
            }
            if (activityLogId != null) {
                ActivityLogEntity.deleteById(activityLogId);
            }
            if (runId != null) {
                WorkflowWaitEntity.delete("runId", runId);
                WorkflowEventSubscriptionEntity.delete("runId", runId);
                WorkflowRunEntity.deleteById(runId);
            }
            if (definitionId != null) {
                WorkflowDefinitionEntity.deleteById(definitionId);
            }
            if (projectId != null) {
                ProjectEntity.deleteById(projectId);
            }
            if (jobId != null) {
                ScheduledJobRunEntity.delete("jobId", jobId);
                ScheduledJobEntity.deleteById(jobId);
            }
        });
    }

    /**
     * Filtering traces by a stream event UUID returns only the traces linked to that event.
     */
    @Test
    void filterTracesByStreamEventId() {
        eventId = createStreamEvent();
        UUID otherEventId = UUID.randomUUID();

        TraceContext matching = traceService.createTrace("manager", "matching trace",
                eventId, null, null, "manager-evaluation", "root", null, null);
        traceService.createTrace("manager", "other trace",
                otherEventId, null, null, "manager-evaluation", "root", null, null);

        given()
            .queryParam("filterEventId", eventId.toString())
            .when()
                .get(TRACES_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].traceId", equalTo(matching.traceId().toString()))
                .body("items.eventId", everyItem(equalTo(eventId.toString())));
    }

    /**
     * A malformed event ID filter is rejected with 400.
     */
    @Test
    void filterTracesByInvalidEventIdReturns400() {
        given()
            .queryParam("filterEventId", "42")
            .when()
                .get(TRACES_PATH)
            .then()
                .statusCode(400);
    }

    /**
     * A trace node referencing a stream event resolves the event as its detail.
     */
    @Test
    void eventTraceNodeDetailResolves() {
        eventId = createStreamEvent();

        TraceContext ctx = traceService.createTrace("manager", "event trace",
                eventId, null, null, "manager-evaluation", "root", null, null);
        Long nodeId = traceService.addUuidEntityNode(ctx, "event-received", "completed",
                "Event received", "event", eventId);

        given()
            .when()
                .get(TRACES_PATH + "/" + ctx.traceId() + "/nodes/" + nodeId)
            .then()
                .statusCode(200)
                .body("node.entityType", equalTo("event"))
                .body("node.entityId", equalTo(eventId.toString()))
                .body("detail.id", equalTo(eventId.toString()))
                .body("detail.type", equalTo("issue.opened"));
    }

    /**
     * Numeric entity references continue to resolve after entity_id became a string column.
     */
    @Test
    void numericEntityTraceNodeDetailStillResolves() {
        TraceContext ctx = traceService.createTrace("test", "numeric trace",
                null, null, null, "root", "root", null, null);
        Long nodeId = traceService.addNode(ctx, "task", "in-progress",
                "Task", "task", 987654321L);

        given()
            .when()
                .get(TRACES_PATH + "/" + ctx.traceId() + "/nodes/" + nodeId)
            .then()
                .statusCode(200)
                .body("node.entityId", equalTo("987654321"));
    }

    /**
     * A workflow-run node resolves the run with its definition and project names.
     */
    @Test
    void workflowRunTraceNodeDetailResolves() {
        createWorkflowRun();
        TraceContext ctx = traceService.createTrace("workflow", "Workflow: WF",
                null, projectId, null, "workflow", "Workflow: WF", "workflow-run", runId);

        given()
            .when()
                .get(TRACES_PATH + "/" + ctx.traceId() + "/nodes/" + ctx.currentParentNodeId())
            .then()
                .statusCode(200)
                .body("node.entityType", equalTo("workflow-run"))
                .body("detail.id", equalTo(runId.intValue()))
                .body("detail.status", equalTo("waiting"))
                .body("detail.definitionName", equalTo("Trace Detail WF"))
                .body("detail.projectId", equalTo(projectId.intValue()))
                .body("detail.projectName", equalTo("Trace Detail Project"))
                .body("detail.currentNodeId", equalTo("wait-1"))
                .body("detail.startedOn", notNullValue())
                .body("detail", not(hasKey("instanceState")));
    }

    /**
     * A pending workflow-wait node resolves the wait with its type, resume time and status.
     */
    @Test
    void workflowWaitTraceNodeDetailResolves() {
        createWorkflowRun();
        Long waitId = QuarkusTransaction.requiringNew().call(() -> {
            WorkflowWaitEntity wait = new WorkflowWaitEntity();
            wait.runId = runId;
            wait.nodeId = "wait-1";
            wait.resumeAt = Instant.now().plusSeconds(3600);
            wait.createdOn = Instant.now();
            wait.persist();
            return wait.id;
        });
        TraceContext ctx = workflowTrace();
        Long nodeId = traceService.addNode(ctx, "task", "in-progress", "Wait (PT1H)",
                "workflow-wait", waitId);

        given()
            .when()
                .get(TRACES_PATH + "/" + ctx.traceId() + "/nodes/" + nodeId)
            .then()
                .statusCode(200)
                .body("detail.id", equalTo(waitId.intValue()))
                .body("detail.waitType", equalTo("duration"))
                .body("detail.resumeAt", notNullValue())
                .body("detail.status", equalTo("waiting"))
                .body("detail.runId", equalTo(runId.intValue()))
                .body("detail.nodeId", equalTo("wait-1"))
                .body("detail.definitionName", equalTo("Trace Detail WF"));
    }

    /**
     * A workflow-wait whose row was deleted on resume still reports a resolved status.
     */
    @Test
    void resolvedWorkflowWaitTraceNodeDetailResolves() {
        createWorkflowRun();
        TraceContext ctx = workflowTrace();
        Long nodeId = traceService.addNode(ctx, "task", "in-progress", "Wait (PT1H)",
                "workflow-wait", 987654321L);
        traceService.completeNode(nodeId, "completed");

        given()
            .when()
                .get(TRACES_PATH + "/" + ctx.traceId() + "/nodes/" + nodeId)
            .then()
                .statusCode(200)
                .body("detail.waitType", equalTo("duration"))
                .body("detail.status", equalTo("resolved"))
                .body("detail.runId", equalTo(runId.intValue()));
    }

    /**
     * A workflow-event-subscription node resolves the awaited event type and status.
     */
    @Test
    void workflowEventSubscriptionTraceNodeDetailResolves() {
        createWorkflowRun();
        Long subId = QuarkusTransaction.requiringNew().call(() -> {
            WorkflowEventSubscriptionEntity sub = new WorkflowEventSubscriptionEntity();
            sub.runId = runId;
            sub.nodeId = "receive-1";
            sub.eventType = "pull_request.merged";
            sub.projectId = projectId;
            sub.createdOn = Instant.now();
            sub.persist();
            return sub.id;
        });
        TraceContext ctx = workflowTrace();
        Long nodeId = traceService.addNode(ctx, "task", "in-progress",
                "Receive event (awaiting: pull_request.merged)",
                "workflow-event-subscription", subId);

        given()
            .when()
                .get(TRACES_PATH + "/" + ctx.traceId() + "/nodes/" + nodeId)
            .then()
                .statusCode(200)
                .body("detail.id", equalTo(subId.intValue()))
                .body("detail.eventType", equalTo("pull_request.merged"))
                .body("detail.status", equalTo("waiting"))
                .body("detail.runId", equalTo(runId.intValue()))
                .body("detail.projectName", equalTo("Trace Detail Project"))
                .body("detail.matchedEventId", nullValue());
    }

    /**
     * An activity-log node resolves the entry type, summary and details.
     */
    @Test
    void activityLogTraceNodeDetailResolves() {
        activityLogId = QuarkusTransaction.requiringNew().call(() -> {
            ActivityLogEntity log = new ActivityLogEntity();
            log.entryType = "manager-evaluation";
            log.summary = "Evaluated event";
            log.details = "long execution log";
            log.createdOn = Instant.now();
            log.persist();
            return log.id;
        });
        TraceContext ctx = traceService.createTrace("manager", "manager trace",
                null, null, null, "event-ingested", "root", null, null);
        Long nodeId = traceService.addNode(ctx, "manager-evaluation", "in-progress",
                "Evaluation", null, null);
        traceService.completeNode(nodeId, "completed", "activity-log", activityLogId);

        given()
            .when()
                .get(TRACES_PATH + "/" + ctx.traceId() + "/nodes/" + nodeId)
            .then()
                .statusCode(200)
                .body("detail.entryType", equalTo("manager-evaluation"))
                .body("detail.summary", equalTo("Evaluated event"))
                .body("detail.details", equalTo("long execution log"));
    }

    /**
     * A scheduled-job-run node resolves the run with its job name.
     */
    @Test
    void scheduledJobRunTraceNodeDetailResolves() {
        Long jobRunId = QuarkusTransaction.requiringNew().call(() -> {
            ScheduledJobEntity job = new ScheduledJobEntity();
            job.name = "Trace Detail Job " + UUID.randomUUID();
            job.enabled = false;
            job.schedule = "daily";
            job.executionMode = "agent";
            job.promptTemplate = "Do something";
            job.createdOn = Instant.now();
            job.updatedOn = Instant.now();
            job.persist();
            jobId = job.id;
            ScheduledJobRunEntity run = new ScheduledJobRunEntity();
            run.jobId = job.id;
            run.status = "Completed";
            run.trigger = "manual";
            run.executionLog = "job log";
            run.createdOn = Instant.now();
            run.persist();
            return run.id;
        });
        TraceContext ctx = traceService.createTrace("scheduled-job-execution", "job",
                null, null, null, "scheduled-job-triggered", "root",
                "scheduled-job-run", jobRunId);

        given()
            .when()
                .get(TRACES_PATH + "/" + ctx.traceId() + "/nodes/" + ctx.currentParentNodeId())
            .then()
                .statusCode(200)
                .body("detail.id", equalTo(jobRunId.intValue()))
                .body("detail.jobId", equalTo(jobId.intValue()))
                .body("detail.jobName", notNullValue())
                .body("detail.executionLog", equalTo("job log"));
    }

    /**
     * Unknown entity types and references to deleted entities return the node without detail.
     */
    @Test
    void unknownOrDeletedEntityReturnsNodeWithoutDetail() {
        TraceContext ctx = traceService.createTrace("test", "missing trace",
                null, null, null, "root", "root", null, null);
        Long unknownNode = traceService.addNode(ctx, "task", "completed",
                "Unknown", "no-such-type", 1L);
        Long deletedRun = traceService.addNode(ctx, "task", "completed",
                "Deleted run", "workflow-run", 987654321L);
        Long deletedSub = traceService.addNode(ctx, "task", "completed",
                "Deleted sub", "workflow-event-subscription", 987654321L);
        Long deletedLog = traceService.addNode(ctx, "task", "completed",
                "Deleted log", "activity-log", 987654321L);

        for (Long nodeId : new Long[] { unknownNode, deletedRun, deletedSub, deletedLog }) {
            given()
                .when()
                    .get(TRACES_PATH + "/" + ctx.traceId() + "/nodes/" + nodeId)
                .then()
                    .statusCode(200)
                    .body("node.id", equalTo(nodeId.intValue()))
                    .body("detail", nullValue());
        }
    }

    private void createWorkflowRun() {
        QuarkusTransaction.requiringNew().run(() -> {
            ProjectEntity project = new ProjectEntity();
            project.name = "Trace Detail Project";
            project.type = "other";
            project.status = "new";
            project.ref = "test/trace-detail-" + UUID.randomUUID();
            project.createdOn = Instant.now();
            project.updatedOn = Instant.now();
            project.persist();
            projectId = project.id;

            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = "Trace Detail WF";
            def.content = "{}";
            def.currentVersion = 1;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();
            definitionId = def.id;

            WorkflowRunEntity run = new WorkflowRunEntity();
            run.projectId = project.id;
            run.definitionId = def.id;
            run.definitionVersion = 1;
            run.instanceState = "{\"big\":\"state\"}";
            run.status = "waiting";
            run.currentNodeId = "wait-1";
            run.startedOn = Instant.now();
            run.persist();
            runId = run.id;
        });
    }

    private TraceContext workflowTrace() {
        TraceContext ctx = traceService.createTrace("workflow", "Workflow: WF",
                null, projectId, null, "workflow", "Workflow: WF", "workflow-run", runId);
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
            run.traceId = ctx.traceId();
        });
        return ctx;
    }

    private UUID createStreamEvent() {
        return QuarkusTransaction.requiringNew().call(() -> {
            StreamEventEntity event = new StreamEventEntity();
            event.id = UUID.randomUUID();
            event.sourceEventId = "trace-test-" + event.id;
            event.source = "github";
            event.connectionId = "trace-test";
            event.type = "issue.opened";
            event.ref = "https://github.com/example/repo/issues/1";
            event.timestamp = Instant.now();
            event.actor = "{}";
            event.payload = "{}";
            event.createdOn = Instant.now();
            event.persist();
            return event.id;
        });
    }
}
