package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.app.HumanTaskStatuses;
import io.apitomy.axiom.app.TestFixtures;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowEventSubscriptionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowScheduleTriggerEntity;
import io.apitomy.axiom.core.entities.WorkflowWaitEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REST tests for the dashboard workflow endpoints: run summary, waiting runs and schedules.
 */
@QuarkusTest
class WorkflowDashboardResourceTest {

    private static final String RUNS = "/api/v1/workflow/runs";

    /** Counts are grouped per status and scoped to the subjects carrying all the given labels. */
    @Test
    void summaryCountsPerStatusScopedByLabels() {
        String label = "dash-" + UUID.randomUUID();
        long definitionId = TestFixtures.createWaitDefinition("dash-sum-" + UUID.randomUUID());
        long subjectId = labelledSubject(label, "team:x");
        long otherSubject = labelledSubject("other-" + UUID.randomUUID());
        Instant now = Instant.now();
        createRun(subjectId, definitionId, "running", null);
        createRun(subjectId, definitionId, "running", null);
        createRun(subjectId, definitionId, "waiting", null);
        createRun(subjectId, definitionId, "completed", now);
        long failed = createRun(subjectId, definitionId, "failed", now);
        createRun(subjectId, definitionId, "cancelled", now);
        createRun(otherSubject, definitionId, "running", null);

        given().queryParam("filterLabels", label + ",team:x")
                .when().get(RUNS + "/summary")
                .then().statusCode(200)
                .body("counts.running", equalTo(2))
                .body("counts.waiting", equalTo(1))
                .body("counts.completed", equalTo(1))
                .body("counts.failed", equalTo(1))
                .body("counts.cancelled", equalTo(1))
                .body("recentFailed", hasSize(1))
                .body("recentFailed[0].runId", equalTo((int) failed))
                .body("recentFailed[0].status", equalTo("failed"));

        // A label the subject lacks scopes everything out.
        given().queryParam("filterLabels", label + ",team:y")
                .when().get(RUNS + "/summary")
                .then().statusCode(200)
                .body("counts.running", equalTo(0))
                .body("recentFailed", hasSize(0));
    }

    /** The since window applies to terminal counts and recent failures only; active counts stay current. */
    @Test
    void summarySinceWindowAppliesToTerminalRuns() {
        String label = "dash-" + UUID.randomUUID();
        long definitionId = TestFixtures.createWaitDefinition("dash-since-" + UUID.randomUUID());
        long subjectId = labelledSubject(label);
        Instant old = Instant.now().minus(Duration.ofDays(3));
        createRun(subjectId, definitionId, "running", null);
        createRun(subjectId, definitionId, "failed", old);
        createRun(subjectId, definitionId, "completed", old);
        long recentFailed = createRun(subjectId, definitionId, "failed", Instant.now());

        String since = Instant.now().minus(Duration.ofDays(1)).toString();
        given().queryParam("filterLabels", label).queryParam("since", since)
                .when().get(RUNS + "/summary")
                .then().statusCode(200)
                .body("counts.running", equalTo(1))
                .body("counts.failed", equalTo(1))
                .body("counts.completed", equalTo(0))
                .body("recentFailed", hasSize(1))
                .body("recentFailed[0].runId", equalTo((int) recentFailed));

        given().queryParam("filterLabels", label)
                .when().get(RUNS + "/summary")
                .then().statusCode(200)
                .body("counts.failed", equalTo(2))
                .body("counts.completed", equalTo(1));

        given().queryParam("since", "yesterday")
                .when().get(RUNS + "/summary")
                .then().statusCode(400);
    }

    /** Wait, receive-event and human-task wait points are listed longest-waiting first with their details. */
    @Test
    void waitingRunsListEveryWaitKind() {
        String label = "dash-" + UUID.randomUUID();
        long definitionId = TestFixtures.createWaitDefinition("dash-wait-" + UUID.randomUUID());
        long subjectId = labelledSubject(label);
        Instant now = Instant.now();
        long waitRun = createRun(subjectId, definitionId, "waiting", null);
        long eventRun = createRun(subjectId, definitionId, "waiting", null);
        long humanRun = createRun(subjectId, definitionId, "waiting", null);
        long runningRun = createRun(subjectId, definitionId, "running", null);
        Instant resumeAt = now.plus(Duration.ofHours(1));
        Instant timeoutAt = now.plus(Duration.ofHours(2));
        long taskId = QuarkusTransaction.requiringNew().call(() -> {
            WorkflowWaitEntity wait = new WorkflowWaitEntity();
            wait.runId = waitRun;
            wait.nodeId = "w1";
            wait.resumeAt = resumeAt;
            wait.createdOn = now.minus(Duration.ofMinutes(30));
            wait.persist();

            WorkflowEventSubscriptionEntity sub = new WorkflowEventSubscriptionEntity();
            sub.runId = eventRun;
            sub.nodeId = "r1";
            sub.eventType = "github.pull_request.closed";
            sub.subjectId = subjectId;
            sub.correlationKey = "pr-42";
            sub.timeoutAt = timeoutAt;
            sub.createdOn = now.minus(Duration.ofMinutes(20));
            sub.persist();

            TaskEntity task = new TaskEntity();
            task.subjectId = subjectId;
            task.actionType = "human";
            task.createdBy = "workflow";
            task.status = HumanTaskStatuses.AWAITING_INPUT;
            task.workflowRunId = humanRun;
            task.nodeId = "h1";
            task.createdOn = now.minus(Duration.ofMinutes(10));
            task.persist();

            // A wait on a run that is not waiting is not listed.
            WorkflowWaitEntity stale = new WorkflowWaitEntity();
            stale.runId = runningRun;
            stale.nodeId = "w1";
            stale.resumeAt = resumeAt;
            stale.createdOn = now.minus(Duration.ofHours(5));
            stale.persist();
            return task.id;
        });

        JsonPath body = given().queryParam("filterLabels", label)
                .when().get(RUNS + "/waiting")
                .then().statusCode(200)
                .body("", hasSize(3))
                .extract().jsonPath();

        assertEquals(List.of("wait", "receive-event", "human-task"), body.getList("waitKind"));
        assertEquals(List.of((int) waitRun, (int) eventRun, (int) humanRun), body.getList("runId"));
        assertEquals("w1", body.getString("[0].nodeId"));
        assertNotNull(body.getString("[0].dueAt"));
        assertEquals((int) definitionId, body.getInt("[0].workflowDefinitionId"));
        assertNotNull(body.getString("[0].workflowName"));
        assertEquals((int) subjectId, body.getInt("[0].subjectId"));
        assertEquals("github.pull_request.closed", body.getString("[1].eventType"));
        assertEquals("pr-42", body.getString("[1].correlationKey"));
        assertNotNull(body.getString("[1].dueAt"));
        assertEquals((int) taskId, body.getInt("[2].taskId"));
        assertNull(body.get("[2].dueAt"));

        given().queryParam("filterLabels", label).queryParam("limit", 1)
                .when().get(RUNS + "/waiting")
                .then().statusCode(200)
                .body("", hasSize(1))
                .body("[0].waitKind", equalTo("wait"));
    }

    /** Schedules are ordered by next fire time with disabled ones last; outcomes are mapped. */
    @Test
    void schedulesOrderedByNextFireDisabledLast() {
        long definitionId = TestFixtures.createWaitDefinition("dash-sched-" + UUID.randomUUID());
        String subjectName = "dash-sched-subject-" + UUID.randomUUID();
        Instant now = Instant.now();
        long subjectId = QuarkusTransaction.requiringNew().call(
                () -> SubjectEntity.findOrCreateByName(subjectName).id);
        Instant lastRunAt = now.minus(Duration.ofMinutes(5));
        long startedRun = createRun(subjectId, definitionId, "running", null);
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunEntity run = WorkflowRunEntity.findById(startedRun);
            run.startedOn = lastRunAt.plusSeconds(1);
        });
        long later = createTrigger(definitionId, subjectName, true, now.plus(Duration.ofDays(9000)), null, null);
        long disabled = createTrigger(definitionId, subjectName, false, null, null, "failed: boom");
        long sooner = createTrigger(definitionId, subjectName, true, now.plus(Duration.ofDays(8000)),
                lastRunAt, "started");
        long skipped = createTrigger(definitionId, subjectName, true, now.plus(Duration.ofDays(9500)),
                lastRunAt, "skipped");

        JsonPath body = given().queryParam("limit", 500)
                .when().get("/api/v1/workflow/schedules")
                .then().statusCode(200)
                .extract().jsonPath();
        List<Map<String, Object>> all = body.getList("");
        List<Integer> ids = all.stream().map(m -> (Integer) m.get("triggerId"))
                .filter(id -> id == sooner || id == later || id == skipped || id == disabled)
                .toList();
        assertEquals(List.of((int) sooner, (int) later, (int) skipped, (int) disabled), ids);
        // Disabled triggers sort after every enabled one.
        int disabledIndex = indexOf(all, disabled);
        assertTrue(all.subList(disabledIndex, all.size()).stream().noneMatch(m -> Boolean.TRUE.equals(m.get("enabled"))));

        Map<String, Object> soonerBean = all.get(indexOf(all, sooner));
        assertEquals("started", soonerBean.get("lastOutcome"));
        assertEquals((int) startedRun, soonerBean.get("lastRunId"));
        assertEquals((int) subjectId, soonerBean.get("subjectId"));
        assertNotNull(soonerBean.get("workflowName"));
        assertNotNull(soonerBean.get("nextFireAt"));
        assertEquals("skipped-duplicate", all.get(indexOf(all, skipped)).get("lastOutcome"));
        assertEquals("failed", all.get(indexOf(all, disabled)).get("lastOutcome"));
        assertNull(all.get(indexOf(all, later)).get("lastOutcome"));
    }

    private static int indexOf(List<Map<String, Object>> all, long triggerId) {
        for (int i = 0; i < all.size(); i++) {
            if (((Integer) all.get(i).get("triggerId")) == triggerId) {
                return i;
            }
        }
        throw new AssertionError("trigger " + triggerId + " not listed");
    }

    private static long createTrigger(long definitionId, String subjectName, boolean enabled, Instant nextRunAt,
                                      Instant lastRunAt, String lastOutcome) {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkflowScheduleTriggerEntity trigger = new WorkflowScheduleTriggerEntity();
            trigger.definitionId = definitionId;
            trigger.cron = "0 2 * * *";
            trigger.subjectName = subjectName;
            trigger.enabled = enabled;
            trigger.nextRunAt = nextRunAt;
            trigger.lastRunAt = lastRunAt;
            trigger.lastOutcome = lastOutcome;
            trigger.createdOn = Instant.now();
            trigger.updatedOn = Instant.now();
            trigger.persist();
            return trigger.id;
        });
    }

    private static long labelledSubject(String... labels) {
        long id = TestFixtures.createSubject("dash-subject-" + UUID.randomUUID());
        QuarkusTransaction.requiringNew().run(() -> {
            SubjectEntity subject = SubjectEntity.findById(id);
            subject.labels.addAll(List.of(labels));
        });
        return id;
    }

    private static long createRun(long subjectId, long definitionId, String status, Instant completedOn) {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.subjectId = subjectId;
            run.definitionId = definitionId;
            run.definitionVersion = 1;
            run.instanceState = "{}";
            run.status = status;
            run.startedOn = completedOn != null ? completedOn.minusSeconds(60) : Instant.now();
            run.completedOn = completedOn;
            run.persist();
            return run.id;
        });
    }
}
