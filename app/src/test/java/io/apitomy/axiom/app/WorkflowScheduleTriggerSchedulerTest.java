package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowScheduleTriggerEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link WorkflowScheduleTriggerScheduler#fire(long, Instant)} directly (the {@code @Scheduled}
 * poller is disabled in the test profile).
 */
@QuarkusTest
class WorkflowScheduleTriggerSchedulerTest {

    @Inject
    WorkflowScheduleTriggerScheduler scheduler;

    /** The first firing creates the fixed subject and starts a run on it. */
    @Test
    void firstFiringCreatesSubjectAndStartsRun() {
        String subjectName = "Nightly triage " + UUID.randomUUID();
        long definitionId = TestFixtures.createWaitDefinition("sched-first-" + UUID.randomUUID());
        long triggerId = createTrigger(definitionId, subjectName, true, "{\"priority\": \"high\"}");

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        scheduler.fire(triggerId, now);

        List<SubjectEntity> subjects = subjectsNamed(subjectName);
        assertEquals(1, subjects.size());
        assertEquals("schedule", subjects.get(0).type);
        assertEquals("schedule:" + subjectName, subjects.get(0).ref);
        List<WorkflowRunEntity> runs = runsFor(subjects.get(0).id);
        assertEquals(1, runs.size());
        assertTrue(runs.get(0).inputs.contains("high"), "static inputs are passed to the run");
        WorkflowScheduleTriggerEntity trigger = loadTrigger(triggerId);
        assertEquals("started", trigger.lastOutcome);
        assertEquals(now, trigger.lastRunAt);
        assertTrue(trigger.nextRunAt.isAfter(now));
    }

    /** A second firing reuses the subject and is skipped while the first run is still active. */
    @Test
    void secondFiringReusesSubjectAndSkipsWhileActive() {
        String subjectName = "Nightly triage " + UUID.randomUUID();
        long definitionId = TestFixtures.createWaitDefinition("sched-skip-" + UUID.randomUUID());
        long triggerId = createTrigger(definitionId, subjectName, true, null);

        scheduler.fire(triggerId, Instant.now());
        makeDue(triggerId);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        scheduler.fire(triggerId, now);

        List<SubjectEntity> subjects = subjectsNamed(subjectName);
        assertEquals(1, subjects.size());
        assertEquals(1, runsFor(subjects.get(0).id).size());
        WorkflowScheduleTriggerEntity trigger = loadTrigger(triggerId);
        assertEquals("skipped", trigger.lastOutcome);
        assertEquals(now, trigger.lastRunAt);
        assertTrue(trigger.nextRunAt.isAfter(now), "nextRunAt advances on a skip");
    }

    /** Once the previous run has ended, the next firing starts a new run on the same subject. */
    @Test
    void firingAfterRunEndsStartsNewRunOnSameSubject() {
        String subjectName = "Nightly triage " + UUID.randomUUID();
        long definitionId = TestFixtures.createWaitDefinition("sched-again-" + UUID.randomUUID());
        long triggerId = createTrigger(definitionId, subjectName, true, null);

        scheduler.fire(triggerId, Instant.now());
        long subjectId = subjectsNamed(subjectName).get(0).id;
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunEntity run = WorkflowRunEntity.find("subjectId", subjectId).firstResult();
            run.status = "completed";
            run.completedOn = Instant.now();
        });
        makeDue(triggerId);
        scheduler.fire(triggerId, Instant.now());

        assertEquals(1, subjectsNamed(subjectName).size());
        assertEquals(2, runsFor(subjectId).size());
        assertEquals("started", loadTrigger(triggerId).lastOutcome);
    }

    /** A disabled trigger is neither fired nor returned as due. */
    @Test
    void disabledTriggerDoesNotFire() {
        String subjectName = "Nightly triage " + UUID.randomUUID();
        long definitionId = TestFixtures.createWaitDefinition("sched-off-" + UUID.randomUUID());
        long triggerId = createTrigger(definitionId, subjectName, false, null);

        assertFalse(QuarkusTransaction.requiringNew()
                .call(() -> scheduler.findDueTriggerIds(Instant.now())).contains(triggerId));
        scheduler.fire(triggerId, Instant.now());

        assertTrue(subjectsNamed(subjectName).isEmpty());
        WorkflowScheduleTriggerEntity trigger = loadTrigger(triggerId);
        assertNull(trigger.lastRunAt);
        assertNull(trigger.lastOutcome);
    }

    /** An enabled trigger whose next run time has passed is returned as due. */
    @Test
    void dueTriggerIsFound() {
        long definitionId = TestFixtures.createWaitDefinition("sched-due-" + UUID.randomUUID());
        long triggerId = createTrigger(definitionId, "Due " + UUID.randomUUID(), true, null);

        assertTrue(QuarkusTransaction.requiringNew()
                .call(() -> scheduler.findDueTriggerIds(Instant.now())).contains(triggerId));
    }

    /**
     * A failed run start still records the outcome and advances the schedule, and the subject it created
     * is kept.
     */
    @Test
    void failedStartRecordsOutcomeAndAdvances() {
        String subjectName = "Nightly triage " + UUID.randomUUID();
        long definitionId = QuarkusTransaction.requiringNew().call(() -> {
            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = "sched-unpublished-" + UUID.randomUUID();
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();
            return def.id;
        });
        long triggerId = createTrigger(definitionId, subjectName, true, null);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        scheduler.fire(triggerId, now);

        WorkflowScheduleTriggerEntity trigger = loadTrigger(triggerId);
        assertTrue(trigger.lastOutcome.startsWith("failed: "), trigger.lastOutcome);
        assertTrue(trigger.lastOutcome.contains("no published version"), trigger.lastOutcome);
        assertEquals(now, trigger.lastRunAt);
        assertTrue(trigger.nextRunAt.isAfter(now));
        List<SubjectEntity> subjects = subjectsNamed(subjectName);
        assertEquals(1, subjects.size());
        assertTrue(runsFor(subjects.get(0).id).isEmpty());
    }

    /** If claiming the firing fails (subject cannot be created), the failure is still recorded and the schedule advances. */
    @Test
    void claimFailureRecordsOutcomeAndAdvances() {
        long definitionId = TestFixtures.createWaitDefinition("sched-claimfail-" + UUID.randomUUID());
        String tooLong = "n".repeat(SubjectEntity.REF_MAX_LENGTH);
        long triggerId = createTrigger(definitionId, tooLong, true, null);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        scheduler.fire(triggerId, now);

        WorkflowScheduleTriggerEntity trigger = loadTrigger(triggerId);
        assertTrue(trigger.lastOutcome.startsWith("failed:"), trigger.lastOutcome);
        assertEquals(now, trigger.lastRunAt);
        assertTrue(trigger.nextRunAt.isAfter(now));
        assertTrue(subjectsNamed(tooLong).isEmpty());
    }

    /** Failure reasons longer than the column are truncated. */
    @Test
    void outcomeIsTruncatedToColumnLength() {
        String longReason = "x".repeat(5000);
        String outcome = WorkflowScheduleTriggerScheduler.failedOutcome(new RuntimeException(longReason));
        assertEquals(WorkflowScheduleTriggerEntity.LAST_OUTCOME_MAX_LENGTH, outcome.length());
        assertTrue(outcome.startsWith("failed: x"));
    }

    private static long createTrigger(long definitionId, String subjectName, boolean enabled, String inputs) {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkflowScheduleTriggerEntity trigger = new WorkflowScheduleTriggerEntity();
            trigger.definitionId = definitionId;
            trigger.cron = "0 2 * * *";
            trigger.subjectName = subjectName;
            trigger.inputs = inputs;
            trigger.enabled = enabled;
            trigger.nextRunAt = Instant.now().minus(1, ChronoUnit.MINUTES);
            trigger.createdOn = Instant.now();
            trigger.updatedOn = Instant.now();
            trigger.persist();
            return trigger.id;
        });
    }

    private static void makeDue(long triggerId) {
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowScheduleTriggerEntity trigger = WorkflowScheduleTriggerEntity.findById(triggerId);
            trigger.nextRunAt = Instant.now().minus(1, ChronoUnit.MINUTES);
        });
    }

    private static WorkflowScheduleTriggerEntity loadTrigger(long triggerId) {
        return QuarkusTransaction.requiringNew().call(() -> WorkflowScheduleTriggerEntity.findById(triggerId));
    }

    private static List<SubjectEntity> subjectsNamed(String name) {
        return QuarkusTransaction.requiringNew().call(() -> SubjectEntity.<SubjectEntity>list("name", name));
    }

    private static List<WorkflowRunEntity> runsFor(long subjectId) {
        return QuarkusTransaction.requiringNew()
                .call(() -> WorkflowRunEntity.<WorkflowRunEntity>list("subjectId", subjectId));
    }
}
