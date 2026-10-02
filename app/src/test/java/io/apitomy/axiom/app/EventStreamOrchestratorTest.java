package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.agents.spi.AgentResult;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the EventStreamOrchestrator.
 */
@QuarkusTest
class EventStreamOrchestratorTest {

    @Inject
    EventStreamOrchestrator orchestrator;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    TaskExecutionService taskExecutionService;

    @AfterEach
    @Transactional
    void cleanup() {
        EventProcessingLedgerEntity.deleteAll();
        StreamEventEntity.deleteAll();
        EventSubscriptionEntity.deleteAll();
        TaskEntity.delete("createdBy", "subscription");
        // Delete activity log and thread entries for test projects before deleting projects
        ActivityLogEntity.delete("projectId IN (SELECT p.id FROM ProjectEntity p WHERE p.type = ?1)", "test-event");
        ThreadEntryEntity.delete("projectId IN (SELECT p.id FROM ProjectEntity p WHERE p.type = ?1)", "test-event");
        ProjectEntity.delete("type", "test-event");
        ActionTypeEntity.delete("name", "test-action");
    }

    // ── Helper methods ──────────────────────────────────────────────

    private UUID insertStreamEvent(String sourceEventId, String type, String connectionId, String ref) {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity event = new StreamEventEntity();
            event.id = id;
            event.sourceEventId = sourceEventId;
            event.source = "github";
            event.connectionId = connectionId;
            event.type = type;
            event.ref = ref;
            event.timestamp = Instant.now();
            event.actor = "{\"login\":\"testuser\"}";
            event.payload = "{\"issue\":{\"title\":\"Test\",\"state\":\"open\",\"number\":\"1\"}}";
            event.createdOn = Instant.now();
            event.persist();
        });
        return id;
    }

    private Long insertSubscription(String name, String filterExpression, boolean enabled, String routing) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EventSubscriptionEntity sub = new EventSubscriptionEntity();
            sub.name = name;
            sub.enabled = enabled;
            sub.filters = filterExpression;
            sub.routing = routing;
            sub.createdOn = Instant.now();
            sub.modifiedOn = Instant.now();
            sub.persist();
            return sub.id;
        });
    }

    // ── buildEventMap ────────────────────────────────────────────────

    @Test
    void testBuildEventMapProducesExpectedKeys() throws Exception {
        StreamEventEntity event = new StreamEventEntity();
        event.id = UUID.randomUUID();
        event.sourceEventId = "gh-12345";
        event.source = "github";
        event.connectionId = "my-org-repo";
        event.type = "issue.created";
        event.ref = "https://github.com/my-org/repo/issues/42";
        event.timestamp = Instant.parse("2026-09-19T10:30:00Z");
        event.actor = "{\"login\":\"octocat\",\"url\":\"https://github.com/octocat\"}";
        event.payload = "{\"title\":\"Bug report\",\"state\":\"open\"}";
        event.createdOn = Instant.now();

        JsonNode payloadNode = objectMapper.readTree(event.payload);
        Map<String, Object> map = orchestrator.buildEventMap(event, payloadNode);

        // Verify all expected keys are present
        assertEquals("issue.created", map.get("type"));
        assertEquals("github", map.get("source"));
        assertEquals("my-org-repo", map.get("connectionId"));
        assertEquals("https://github.com/my-org/repo/issues/42", map.get("ref"));
        assertEquals("2026-09-19T10:30:00Z", map.get("timestamp"));

        // Actor should be parsed into a map
        assertInstanceOf(Map.class, map.get("actor"));
        @SuppressWarnings("unchecked")
        Map<String, Object> actor = (Map<String, Object>) map.get("actor");
        assertEquals("octocat", actor.get("login"));

        // Payload should be parsed into a map
        assertInstanceOf(Map.class, map.get("payload"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) map.get("payload");
        assertEquals("Bug report", payload.get("title"));
        assertEquals("open", payload.get("state"));
    }

    @Test
    void testBuildEventMapWithNullPayload() {
        StreamEventEntity event = new StreamEventEntity();
        event.id = UUID.randomUUID();
        event.sourceEventId = "gh-99";
        event.source = "github";
        event.connectionId = "conn-1";
        event.type = "pr.merged";
        event.ref = "https://github.com/org/repo/pull/5";
        event.timestamp = Instant.parse("2026-09-19T12:00:00Z");
        event.actor = null;
        event.payload = null;
        event.createdOn = Instant.now();

        Map<String, Object> map = orchestrator.buildEventMap(event, null);

        assertEquals("pr.merged", map.get("type"));
        assertEquals("github", map.get("source"));
        assertNotNull(map.get("payload"));
        assertEquals(Map.of(), map.get("payload"));
        // Actor should not be present when null
        assertFalse(map.containsKey("actor"));
    }

    // ── Smoke test: empty stream ─────────────────────────────────────

    @Test
    void testProcessNewEventsOnEmptyStream() {
        // Calling processNewEvents on an empty database should not throw.
        // No subscriptions exist, so it returns immediately.
        orchestrator.processNewEvents();
    }

    // ── Smoke test: event with no subscriptions ──────────────────────

    @Test
    void testProcessNewEventsWithNoSubscriptions() {
        // Insert a stream event
        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity event = new StreamEventEntity();
            event.id = UUID.randomUUID();
            event.sourceEventId = "smoke-1";
            event.source = "github";
            event.connectionId = "test-conn";
            event.type = "issue.created";
            event.ref = "https://github.com/org/repo/issues/1";
            event.timestamp = Instant.now();
            event.actor = "{\"login\":\"tester\"}";
            event.payload = "{\"title\":\"Test\"}";
            event.createdOn = Instant.now();
            event.persist();
        });

        // With no subscriptions enabled, processNewEvents returns immediately
        orchestrator.processNewEvents();
    }

    // ── Processing Ledger Tests ──────────────────────────────────────

    @Test
    void newSubscriptionProcessesExistingEvents() {
        // Insert events first
        UUID e1 = insertStreamEvent("ev-1", "issue.created", "conn-1", "https://github.com/org/repo/issues/1");
        UUID e2 = insertStreamEvent("ev-2", "pr.merged", "conn-1", "https://github.com/org/repo/pull/2");

        // Create an enabled subscription that matches all events (no filter)
        Long subId = insertSubscription("all-events", null, true, null);

        // Process
        orchestrator.processNewEvents();

        // Verify ledger entries were created for both events
        long ledgerCount = QuarkusTransaction.requiringNew().call(() ->
            EventProcessingLedgerEntity.count("subscriptionId", subId));
        assertEquals(2, ledgerCount, "Should have ledger entries for both events");
    }

    @Test
    void skippedEntriesPreventReEvaluation() {
        UUID eventId = insertStreamEvent("ev-skip-1", "push", "conn-1", "https://github.com/org/repo");

        // Subscription that only matches issue events
        Long subId = insertSubscription("issues-only", "event.type.startsWith('issue.')", true, null);

        // First processing creates a "skipped" entry
        orchestrator.processNewEvents();

        String status1 = QuarkusTransaction.requiringNew().call(() -> {
            EventProcessingLedgerEntity entry = EventProcessingLedgerEntity
                .find("eventId = ?1 and subscriptionId = ?2", eventId, subId).firstResult();
            assertNotNull(entry, "Ledger entry should exist");
            return entry.status;
        });
        assertEquals("skipped", status1);

        // Second processing should not create any new entries
        orchestrator.processNewEvents();

        long count = QuarkusTransaction.requiringNew().call(() ->
            EventProcessingLedgerEntity.count("eventId = ?1 and subscriptionId = ?2", eventId, subId));
        assertEquals(1, count, "Should still have exactly one entry (not re-evaluated)");
    }

    @Test
    void filterExpressionMatchesCorrectly() {
        UUID matched = insertStreamEvent("ev-m1", "issue.created", "conn-1", "https://github.com/org/repo/issues/1");
        UUID notMatched = insertStreamEvent("ev-m2", "pr.merged", "conn-1", "https://github.com/org/repo/pull/2");

        Long subId = insertSubscription("issues-only", "event.type.startsWith('issue.')", true, null);

        orchestrator.processNewEvents();

        QuarkusTransaction.requiringNew().run(() -> {
            EventProcessingLedgerEntity matchedEntry = EventProcessingLedgerEntity
                .find("eventId = ?1 and subscriptionId = ?2", matched, subId).firstResult();
            assertNotNull(matchedEntry);
            // With no routing rules, a matched event gets "completed" status
            assertTrue("completed".equals(matchedEntry.status) || "pending".equals(matchedEntry.status),
                "Matched event should be completed or pending, was: " + matchedEntry.status);

            EventProcessingLedgerEntity notMatchedEntry = EventProcessingLedgerEntity
                .find("eventId = ?1 and subscriptionId = ?2", notMatched, subId).firstResult();
            assertNotNull(notMatchedEntry);
            assertEquals("skipped", notMatchedEntry.status, "Non-matching event should be skipped");
        });
    }

    @Test
    void failedEntriesAreRetried() {
        UUID eventId = insertStreamEvent("ev-retry-1", "issue.created", "conn-1", "https://github.com/org/repo/issues/1");

        // Create subscription with invoke-action pointing to non-existent action type
        String routing = "[{\"type\":\"invoke-action\",\"actionTypeId\":99999}]";
        Long subId = insertSubscription("retry-test", null, true, routing);

        // First run: routing fails (action type 99999 doesn't exist)
        orchestrator.processNewEvents();

        String status = QuarkusTransaction.requiringNew().call(() -> {
            EventProcessingLedgerEntity entry = EventProcessingLedgerEntity
                .find("eventId = ?1 and subscriptionId = ?2", eventId, subId).firstResult();
            assertNotNull(entry, "Ledger entry should exist");
            return entry.status;
        });
        assertEquals("failed", status, "Should be failed due to missing action type");

        // Second run: retry is attempted (but will fail again since action type still doesn't exist)
        orchestrator.processNewEvents();

        // Verify the entry still exists and is still failed (with potentially updated processedOn)
        QuarkusTransaction.requiringNew().run(() -> {
            EventProcessingLedgerEntity entry = EventProcessingLedgerEntity
                .find("eventId = ?1 and subscriptionId = ?2", eventId, subId).firstResult();
            assertNotNull(entry, "Ledger entry should still exist after retry");
            assertEquals("failed", entry.status, "Should still be failed");
            assertNotNull(entry.errorMessage, "Should have error message");
        });
    }

    @Test
    void startupRecoveryConvertsOrphanedPendingToFailed() {
        UUID eventId = insertStreamEvent("ev-orphan-1", "issue.created", "conn-1", "https://github.com/org/repo/issues/1");
        Long subId = insertSubscription("orphan-test", null, true, null);

        // Manually create a "pending" ledger entry (simulating a crash mid-processing)
        QuarkusTransaction.requiringNew().run(() -> {
            EventProcessingLedgerEntity entry = new EventProcessingLedgerEntity();
            entry.eventId = eventId;
            entry.subscriptionId = subId;
            entry.status = "pending";
            entry.createdOn = Instant.now().minusSeconds(300);
            entry.persist();
        });

        // Reset the startup recovery flag via reflection so it runs again.
        // The injected orchestrator is a CDI proxy; unwrap to get the actual bean instance.
        try {
            Object actualInstance = ClientProxy.unwrap(orchestrator);
            Field field = EventStreamOrchestrator.class.getDeclaredField("startupRecoveryDone");
            field.setAccessible(true);
            field.set(actualInstance, false);
        } catch (Exception e) {
            fail("Failed to reset startupRecoveryDone flag: " + e.getMessage());
        }

        // processNewEvents should trigger startup recovery on this call
        orchestrator.processNewEvents();

        // The orphaned "pending" entry should no longer be "pending"
        QuarkusTransaction.requiringNew().run(() -> {
            EventProcessingLedgerEntity entry = EventProcessingLedgerEntity
                .find("eventId = ?1 and subscriptionId = ?2", eventId, subId).firstResult();
            assertNotNull(entry);
            // Recovery marks pending -> failed, then retry loop picks it up.
            // Since there are no routing rules, the retry succeeds and it becomes "completed".
            assertNotEquals("pending", entry.status,
                "Orphaned pending entry should not remain pending");
        });
    }

    @Test
    void subscriptionDeletionCascadesLedgerEntries() {
        // The test DB is generated by Hibernate (drop-and-create), so the
        // ON DELETE CASCADE FK from Flyway migrations is not present. Add
        // it here so we can verify the cascade behavior that production uses.
        QuarkusTransaction.requiringNew().run(() ->
            EventProcessingLedgerEntity.getEntityManager()
                .createNativeQuery("ALTER TABLE event_processing_ledger " +
                    "ADD CONSTRAINT fk_epl_cascade_sub FOREIGN KEY (subscription_id) " +
                    "REFERENCES event_subscription(id) ON DELETE CASCADE")
                .executeUpdate());

        UUID eventId = insertStreamEvent("ev-cascade-1", "issue.created", "conn-1", "https://github.com/org/repo/issues/1");
        Long subId = insertSubscription("cascade-test", null, true, null);

        // Process to create ledger entries
        orchestrator.processNewEvents();

        long before = QuarkusTransaction.requiringNew().call(() ->
            EventProcessingLedgerEntity.count("subscriptionId", subId));
        assertTrue(before > 0, "Should have ledger entries before deletion");

        // Delete the subscription using native SQL so the DB-level ON DELETE CASCADE
        // on event_processing_ledger.subscription_id is triggered directly.
        QuarkusTransaction.requiringNew().run(() -> {
            EventSubscriptionEntity.getEntityManager()
                .createNativeQuery("DELETE FROM event_subscription_label WHERE event_subscription_id = ?1")
                .setParameter(1, subId).executeUpdate();
            EventSubscriptionEntity.getEntityManager()
                .createNativeQuery("DELETE FROM event_subscription WHERE id = ?1")
                .setParameter(1, subId).executeUpdate();
        });

        // Verify ledger entries are gone
        long after = QuarkusTransaction.requiringNew().call(() ->
            EventProcessingLedgerEntity.count("subscriptionId", subId));
        assertEquals(0, after, "Ledger entries should be deleted by cascade");

        // Clean up the FK constraint to avoid interfering with other tests
        QuarkusTransaction.requiringNew().run(() ->
            EventProcessingLedgerEntity.getEntityManager()
                .createNativeQuery("ALTER TABLE event_processing_ledger " +
                    "DROP CONSTRAINT fk_epl_cascade_sub")
                .executeUpdate());
    }

    // ── Routing Rule Tests ───────────────────────────────────────────

    @Test
    void invokeActionCreatesTask() {
        // Create an action type for routing
        Long actionTypeId = QuarkusTransaction.requiringNew().call(() -> {
            ActionTypeEntity at = new ActionTypeEntity();
            at.name = "test-action";
            at.description = "Test action for routing";
            at.executionMode = "agent";
            at.managerTriggerable = false;
            at.userTriggerable = false;
            at.workflowEnabled = false;
            at.emitsEvent = false;
            at.persist();
            return at.id;
        });

        UUID eventId = insertStreamEvent("ev-action-1", "issue.created", "conn-1",
            "https://github.com/test-org/test-repo/issues/42");

        String routing = "[{\"type\":\"invoke-action\",\"actionTypeId\":" + actionTypeId + "}]";
        insertSubscription("action-test", null, true, routing);

        orchestrator.processNewEvents();

        // Verify a task was created
        QuarkusTransaction.requiringNew().run(() -> {
            TaskEntity task = TaskEntity.find("actionType = ?1 and createdBy = 'subscription'",
                "test-action").firstResult();
            assertNotNull(task, "Task should have been created by invoke-action routing");
            assertEquals("Pending", task.status);
            assertNotNull(task.projectId, "Task should be linked to a project");
        });
    }

    @Test
    void invokeActionTraceCompletesOnlyWhenTaskFinishes() {
        Long actionTypeId = QuarkusTransaction.requiringNew().call(() -> {
            ActionTypeEntity at = new ActionTypeEntity();
            at.name = "test-action";
            at.description = "Test action for trace lifecycle";
            at.executionMode = "agent";
            at.managerTriggerable = false;
            at.userTriggerable = false;
            at.workflowEnabled = false;
            at.emitsEvent = false;
            at.persist();
            return at.id;
        });
        insertStreamEvent("ev-trace-1", "issue.created", "conn-1",
            "https://github.com/test-org/test-repo/issues/4242");
        String routing = "[{\"type\":\"invoke-action\",\"actionTypeId\":" + actionTypeId + "}]";
        insertSubscription("trace-test", null, true, routing);

        orchestrator.processNewEvents();

        TaskEntity task = QuarkusTransaction.requiringNew().call(() ->
            TaskEntity.<TaskEntity>find("actionType = ?1 and createdBy = 'subscription'",
                "test-action").firstResult());
        assertNotNull(task);
        assertNotNull(task.traceId);
        UUID traceId = task.traceId;
        assertEquals("in-progress", QuarkusTransaction.requiringNew().call(() ->
                TraceEntity.<TraceEntity>findById(traceId).status),
            "Trace must stay open while the task is pending");

        taskExecutionService.onTaskCompleted(task.id, AgentResult.success("done"));

        TraceEntity trace = QuarkusTransaction.requiringNew().call(() ->
            TraceEntity.<TraceEntity>findById(traceId));
        assertEquals("completed", trace.status);
        assertNotNull(trace.completedOn);
        long openNodes = QuarkusTransaction.requiringNew().call(() ->
            TraceNodeEntity.count("traceId = ?1 and status = 'in-progress'", traceId));
        assertEquals(0, openNodes, "Task node must be completed with the trace");
    }

    @Test
    void projectAutoCreatedFromEventRef() {
        // Use a unique ref that won't match any existing project
        String uniqueRef = "https://github.com/test-org/test-repo/issues/99999";

        Long actionTypeId = QuarkusTransaction.requiringNew().call(() -> {
            ActionTypeEntity at = new ActionTypeEntity();
            at.name = "test-action";
            at.description = "Test";
            at.executionMode = "agent";
            at.managerTriggerable = false;
            at.userTriggerable = false;
            at.workflowEnabled = false;
            at.emitsEvent = false;
            at.persist();
            return at.id;
        });

        insertStreamEvent("ev-proj-1", "issue.created", "conn-1", uniqueRef);
        String routing = "[{\"type\":\"invoke-action\",\"actionTypeId\":" + actionTypeId + "}]";
        insertSubscription("project-test", null, true, routing);

        orchestrator.processNewEvents();

        // Verify project was auto-created with correct type
        QuarkusTransaction.requiringNew().run(() -> {
            ProjectEntity project = ProjectEntity.find("ref", uniqueRef).firstResult();
            assertNotNull(project, "Project should have been auto-created");
            assertEquals("issue", project.type, "Project type should be 'issue' for issue events");
        });
    }

    @Test
    void projectReusedForSameRef() {
        String ref = "https://github.com/test-org/test-repo/issues/88888";

        Long actionTypeId = QuarkusTransaction.requiringNew().call(() -> {
            ActionTypeEntity at = new ActionTypeEntity();
            at.name = "test-action";
            at.description = "Test";
            at.executionMode = "agent";
            at.managerTriggerable = false;
            at.userTriggerable = false;
            at.workflowEnabled = false;
            at.emitsEvent = false;
            at.persist();
            return at.id;
        });

        // Insert two events with the same ref
        insertStreamEvent("ev-reuse-1", "issue.created", "conn-1", ref);
        insertStreamEvent("ev-reuse-2", "issue.updated", "conn-1", ref);

        String routing = "[{\"type\":\"invoke-action\",\"actionTypeId\":" + actionTypeId + "}]";
        insertSubscription("reuse-test", null, true, routing);

        orchestrator.processNewEvents();

        // Verify only one project was created
        long projectCount = QuarkusTransaction.requiringNew().call(() ->
            ProjectEntity.count("ref", ref));
        assertEquals(1, projectCount, "Should reuse the same project for both events");

        // But two tasks should exist
        long taskCount = QuarkusTransaction.requiringNew().call(() ->
            TaskEntity.count("actionType = ?1 and createdBy = 'subscription'", "test-action"));
        assertEquals(2, taskCount, "Should have created two tasks (one per event)");
    }

    @Test
    void disabledSubscriptionIsSkipped() {
        insertStreamEvent("ev-disabled-1", "issue.created", "conn-1", "https://github.com/org/repo/issues/1");

        // Create a disabled subscription
        Long subId = insertSubscription("disabled-test", null, false, null);

        orchestrator.processNewEvents();

        // No ledger entries should exist (disabled subscriptions are not loaded)
        long count = QuarkusTransaction.requiringNew().call(() ->
            EventProcessingLedgerEntity.count("subscriptionId", subId));
        assertEquals(0, count, "Disabled subscription should not create ledger entries");
    }
}
