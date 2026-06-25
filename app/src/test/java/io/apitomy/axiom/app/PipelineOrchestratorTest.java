package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.EventQueueEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.services.WorkspaceService;
import io.apitomy.axiom.manager.ManagerDecision;
import io.apitomy.axiom.manager.ManagerService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for the PipelineOrchestrator.
 * Uses a mocked ManagerService to control decisions without needing Claude Code.
 */
@QuarkusTest
class PipelineOrchestratorTest {

    @Inject
    PipelineOrchestrator orchestrator;

    @InjectMock
    ManagerService managerService;

    @InjectMock
    WorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        // Default: all decisions meet confidence threshold
        when(managerService.meetsConfidenceThreshold(any())).thenReturn(true);
        // Mock workspace service to avoid real git clone operations
        when(workspaceService.ensureWorkspace(any())).thenReturn(java.nio.file.Path.of("/tmp/test-workspace"));
        when(workspaceService.getWorkspacePath(any())).thenReturn(java.nio.file.Path.of("/tmp/test-workspace"));
    }

    @AfterEach
    @Transactional
    void cleanup() {
        ThreadEntryEntity.deleteAll();
        TraceNodeEntity.deleteAll();
        TraceEntity.deleteAll();
        ActivityLogEntity.deleteAll();
        TaskEntity.deleteAll();
        ProjectEntity.deleteAll();
        EventQueueEntity.deleteAll();
        EventEntity.deleteAll();
    }

    // ── Create Task Decision ──────────────────────────────────────────

    @Test
    void testCreateTaskDecisionAutoCreatesProject() {
        EventQueueEntity queueEntry = createEventAndEnqueue(
                "github", "issue-created", "TestOrg/pipeline-test#1",
                "TestOrg/pipeline-test",
                "{\"action\":\"opened\",\"issue\":{\"number\":1,\"title\":\"Test\"}}");

        ManagerDecision decision = new ManagerDecision(
                "create_task", "analyze", null,
                "Analyze this new issue", 0.9, "New issue needs analysis");
        when(managerService.evaluate(any(), any())).thenReturn(List.of(decision));

        orchestrator.processEvent(queueEntry);

        // Verify project was auto-created
        ProjectEntity project = ProjectEntity.find("issueRef", "TestOrg/pipeline-test#1").firstResult();
        assertNotNull(project, "Project should be auto-created");
        assertEquals("issue", project.type);
        assertEquals("Created", project.status);
        assertEquals("github", project.issueSource);
        assertEquals("TestOrg/pipeline-test", project.repository);

        // Verify task was created
        List<TaskEntity> tasks = TaskEntity.list("projectId", project.id);
        assertEquals(1, tasks.size());
        TaskEntity task = tasks.getFirst();
        assertEquals("analyze", task.actionType);
        assertEquals("manager", task.createdBy);
        assertEquals("Pending", task.status);
        assertEquals("Analyze this new issue", task.input);

        // Verify event queue marked completed
        EventQueueEntity updated = EventQueueEntity.findById(queueEntry.id);
        assertEquals("completed", updated.status);
        assertNotNull(updated.processedAt);
    }

    @Test
    void testCreateTaskOnExistingProject() {
        // Create project first
        ProjectEntity project = createProject("TestOrg/pipeline-test#2", "TestOrg/pipeline-test");

        EventQueueEntity queueEntry = createEventAndEnqueue(
                "github", "comment-added", "TestOrg/pipeline-test#2",
                "TestOrg/pipeline-test",
                "{\"action\":\"created\",\"comment\":{\"body\":\"question?\"}}");

        ManagerDecision decision = new ManagerDecision(
                "create_task", "answer-question", null,
                "Answer the user's question", 0.85, "User asked a question");
        when(managerService.evaluate(any(), any())).thenReturn(List.of(decision));

        orchestrator.processEvent(queueEntry);

        // Should not create a second project
        long projectCount = ProjectEntity.count("issueRef", "TestOrg/pipeline-test#2");
        assertEquals(1, projectCount);

        // Task should be on the existing project
        List<TaskEntity> tasks = TaskEntity.list("projectId", project.id);
        assertEquals(1, tasks.size());
        assertEquals("answer-question", tasks.getFirst().actionType);
    }

    @Test
    void testCreateProjectFromPrEventSetsPullRequestType() {
        EventQueueEntity queueEntry = createEventAndEnqueue(
                "github", "pr-created", "TestOrg/pipeline-test#10",
                "TestOrg/pipeline-test",
                "{\"action\":\"opened\",\"pull_request\":{\"number\":10,\"title\":\"Fix bug\"}}");

        ManagerDecision decision = new ManagerDecision(
                "create_task", "review", null,
                "Review this PR", 0.9, "New PR needs review");
        when(managerService.evaluate(any(), any())).thenReturn(List.of(decision));

        orchestrator.processEvent(queueEntry);

        ProjectEntity project = ProjectEntity.find("issueRef", "TestOrg/pipeline-test#10").firstResult();
        assertNotNull(project, "Project should be auto-created for PR event");
        assertEquals("pull-request", project.type);
        assertEquals("github", project.issueSource);
    }

    @Test
    void testCreateProjectFromUnknownEventSetsOtherType() {
        EventQueueEntity queueEntry = createEventAndEnqueue(
                "github", "task-completed", "TestOrg/pipeline-test#11",
                "TestOrg/pipeline-test",
                "{\"action\":\"completed\",\"issue\":{\"number\":11,\"title\":\"Done\"}}");

        ManagerDecision decision = new ManagerDecision(
                "create_task", "analyze", null,
                "Analyze this", 0.9, "Needs analysis");
        when(managerService.evaluate(any(), any())).thenReturn(List.of(decision));

        orchestrator.processEvent(queueEntry);

        ProjectEntity project = ProjectEntity.find("issueRef", "TestOrg/pipeline-test#11").firstResult();
        assertNotNull(project, "Project should be auto-created for unknown event type");
        assertEquals("other", project.type);
    }

    @Test
    void testMultipleDecisionsFromSingleEvent() {
        EventQueueEntity queueEntry = createEventAndEnqueue(
                "github", "issue-created", "TestOrg/pipeline-test#3",
                "TestOrg/pipeline-test",
                "{\"action\":\"opened\",\"issue\":{\"number\":3,\"title\":\"Feature\"}}");

        List<ManagerDecision> decisions = List.of(
                new ManagerDecision("create_task", "auto-tag", null,
                        "Tag this issue", 0.95, "New issue should be tagged"),
                new ManagerDecision("create_task", "analyze", null,
                        "Analyze this issue", 0.88, "New issue needs analysis")
        );
        when(managerService.evaluate(any(), any())).thenReturn(decisions);

        orchestrator.processEvent(queueEntry);

        ProjectEntity project = ProjectEntity.find("issueRef", "TestOrg/pipeline-test#3").firstResult();
        assertNotNull(project);

        List<TaskEntity> tasks = TaskEntity.list("projectId", project.id);
        assertEquals(2, tasks.size());
    }

    // ── Ignore Decision ───────────────────────────────────────────────

    @Test
    void testIgnoreDecision() {
        EventQueueEntity queueEntry = createEventAndEnqueue(
                "github", "comment-added", "TestOrg/pipeline-test#10",
                "TestOrg/pipeline-test",
                "{\"action\":\"created\",\"comment\":{\"body\":\"bot message\"}}");

        ManagerDecision decision = new ManagerDecision(
                "ignore", null, null, null, 0.98, "Bot comment, ignoring");
        when(managerService.evaluate(any(), any())).thenReturn(List.of(decision));

        orchestrator.processEvent(queueEntry);

        // No project or task should be created
        assertNull(ProjectEntity.find("issueRef", "TestOrg/pipeline-test#10").firstResult());
        assertEquals(0, TaskEntity.count("projectId is null"));

        // Activity log should record the ignore
        List<ActivityLogEntity> logs = ActivityLogEntity.list("entryType", "event-ignored");
        assertTrue(logs.stream().anyMatch(l -> l.summary.contains("Bot comment")));

        // Queue entry should be completed
        assertEquals("completed", EventQueueEntity.<EventQueueEntity>findById(queueEntry.id).status);
    }

    // ── System Action Decisions ───────────────────────────────────────

    @Test
    void testCloseProjectScriptAction() {
        ProjectEntity project = createProject("TestOrg/pipeline-test#20", "TestOrg/pipeline-test", "Idle");

        EventQueueEntity queueEntry = createEventAndEnqueue(
                "github", "issue-closed", "TestOrg/pipeline-test#20",
                "TestOrg/pipeline-test",
                "{\"action\":\"closed\"}");

        ManagerDecision decision = new ManagerDecision(
                "script_action", "close-project", null, null, 0.9, "Issue closed");
        when(managerService.evaluate(any(), any())).thenReturn(List.of(decision));

        orchestrator.processEvent(queueEntry);

        // Script actions create a task rather than changing status directly
        List<TaskEntity> tasks = TaskEntity.list("projectId", project.id);
        assertTrue(tasks.stream().anyMatch(t -> "close-project".equals(t.actionType)));

        List<ActivityLogEntity> logs = ActivityLogEntity.list("entryType", "task-created");
        assertTrue(logs.stream().anyMatch(l -> l.projectId.equals(project.id)));
    }

    @Test
    void testReopenProjectScriptAction() {
        ProjectEntity project = createProject("TestOrg/pipeline-test#21", "TestOrg/pipeline-test", "Completed");

        EventQueueEntity queueEntry = createEventAndEnqueue(
                "github", "issue-reopened", "TestOrg/pipeline-test#21",
                "TestOrg/pipeline-test",
                "{\"action\":\"reopened\"}");

        ManagerDecision decision = new ManagerDecision(
                "script_action", "reopen-project", null, null, 0.85, "Issue reopened");
        when(managerService.evaluate(any(), any())).thenReturn(List.of(decision));

        orchestrator.processEvent(queueEntry);

        List<TaskEntity> tasks = TaskEntity.list("projectId", project.id);
        assertTrue(tasks.stream().anyMatch(t -> "reopen-project".equals(t.actionType)));
    }

    // ── Escalation ────────────────────────────────────────────────────

    @Test
    void testEscalationDecision() {
        EventQueueEntity queueEntry = createEventAndEnqueue(
                "github", "issue-updated", "TestOrg/pipeline-test#30",
                "TestOrg/pipeline-test",
                "{\"action\":\"edited\"}");

        ManagerDecision decision = new ManagerDecision(
                "escalate", null, null, null, 0.4, "Not sure what to do");
        when(managerService.evaluate(any(), any())).thenReturn(List.of(decision));

        orchestrator.processEvent(queueEntry);

        // Activity log should record the escalation
        List<ActivityLogEntity> logs = ActivityLogEntity.list("entryType", "manager-escalation");
        assertTrue(logs.stream().anyMatch(l -> l.summary.contains("Not sure what to do")));
    }

    @Test
    void testLowConfidenceAutoEscalation() {
        ProjectEntity project = createProject("TestOrg/pipeline-test#31", "TestOrg/pipeline-test");

        EventQueueEntity queueEntry = createEventAndEnqueue(
                "github", "comment-added", "TestOrg/pipeline-test#31",
                "TestOrg/pipeline-test",
                "{\"action\":\"created\"}");

        // Decision with confidence below threshold (0.7 default)
        ManagerDecision decision = new ManagerDecision(
                "create_task", "implement", null,
                "Implement something", 0.3, "Low confidence guess");
        when(managerService.evaluate(any(), any())).thenReturn(List.of(decision));
        when(managerService.meetsConfidenceThreshold(any())).thenReturn(false);

        orchestrator.processEvent(queueEntry);

        // Task should NOT be created (escalated instead)
        List<TaskEntity> tasks = TaskEntity.list("projectId", project.id);
        assertEquals(0, tasks.size());

        // Should be escalated
        List<ActivityLogEntity> logs = ActivityLogEntity.list("entryType", "manager-escalation");
        assertTrue(logs.stream().anyMatch(l -> l.summary.contains("Low confidence")));

        // Thread should have escalation entry
        List<ThreadEntryEntity> thread = ThreadEntryEntity.list("projectId", project.id);
        assertTrue(thread.stream().anyMatch(e ->
                "question".equals(e.entryType) && e.content.contains("Escalation")));
    }

    // ── No Decisions ──────────────────────────────────────────────────

    @Test
    void testManagerReturnsNoDecisions() {
        EventQueueEntity queueEntry = createEventAndEnqueue(
                "github", "issue-updated", "TestOrg/pipeline-test#40",
                "TestOrg/pipeline-test",
                "{\"action\":\"edited\"}");

        when(managerService.evaluate(any(), any())).thenReturn(Collections.emptyList());

        orchestrator.processEvent(queueEntry);

        // Queue should still be marked completed
        assertEquals("completed", EventQueueEntity.<EventQueueEntity>findById(queueEntry.id).status);

        // Activity log should note no decisions
        List<ActivityLogEntity> logs = ActivityLogEntity.list("entryType", "manager-no-decision");
        assertFalse(logs.isEmpty());
    }

    // ── Activity Log & Thread Entries ─────────────────────────────────

    @Test
    void testActivityLogAndThreadEntries() {
        EventQueueEntity queueEntry = createEventAndEnqueue(
                "github", "issue-created", "TestOrg/pipeline-test#50",
                "TestOrg/pipeline-test",
                "{\"action\":\"opened\",\"issue\":{\"number\":50,\"title\":\"Logging test\"}}");

        ManagerDecision decision = new ManagerDecision(
                "create_task", "analyze", null,
                "Analyze this", 0.95, "Testing activity logging");
        when(managerService.evaluate(any(), any())).thenReturn(List.of(decision));

        orchestrator.processEvent(queueEntry);

        ProjectEntity project = ProjectEntity.find("issueRef", "TestOrg/pipeline-test#50").firstResult();
        assertNotNull(project);

        // Check activity log entries
        List<ActivityLogEntity> logs = ActivityLogEntity.list("projectId", project.id);
        assertTrue(logs.stream().anyMatch(l -> "project-created".equals(l.entryType)));
        assertTrue(logs.stream().anyMatch(l -> "task-created".equals(l.entryType)));

        // Check thread entries
        List<ThreadEntryEntity> thread = ThreadEntryEntity.list(
                "projectId = ?1 order by createdOn", project.id);
        assertTrue(thread.stream().anyMatch(e -> "system".equals(e.authorType)
                && e.content.contains("Project created")));
        assertTrue(thread.stream().anyMatch(e -> "manager".equals(e.authorType)
                && e.content.contains("analyze")));
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private EventQueueEntity createEventAndEnqueue(String source, String eventType,
                                                    String issueRef, String repository,
                                                    String payload) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EventEntity event = new EventEntity();
            event.source = source;
            event.eventType = eventType;
            event.issueRef = issueRef;
            event.repository = repository;
            event.payload = payload;
            event.receivedAt = Instant.now();
            event.persist();

            EventQueueEntity queueEntry = new EventQueueEntity();
            queueEntry.eventId = event.id;
            queueEntry.status = "pending";
            queueEntry.enqueuedAt = Instant.now();
            queueEntry.persist();

            return queueEntry;
        });
    }

    private ProjectEntity createProject(String issueRef, String repository) {
        return createProject(issueRef, repository, "Created");
    }

    private ProjectEntity createProject(String issueRef, String repository, String status) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ProjectEntity project = new ProjectEntity();
            project.name = issueRef;
            project.type = "other";
            project.status = status;
            project.issueSource = "github";
            project.issueRef = issueRef;
            project.repository = repository;
            project.createdOn = Instant.now();
            project.updatedOn = Instant.now();
            project.persist();
            return project;
        });
    }
}
