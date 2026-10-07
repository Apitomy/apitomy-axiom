package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.logging.LogContext;
import io.apitomy.axiom.core.services.WorkspaceService;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.core.tracing.TraceService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that log lines written while a task executes carry the task's correlation IDs in the
 * logging MDC, including lines written by the asynchronous agent completion callback on another
 * thread, and that the MDC is empty again afterwards on both threads.
 */
@QuarkusTest
class LogCorrelationTest {

    @InjectMock
    AgentPool agentPool;

    @InjectMock
    WorkspaceService workspaceService;

    @Inject
    TaskExecutionService taskExecutionService;

    @Inject
    TraceService traceService;

    private final List<CapturedLog> captured = new CopyOnWriteArrayList<>();
    private final Handler captureHandler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            // Handlers publish synchronously on the logging thread, so the MDC read here is
            // the MDC the log line is formatted with.
            Map<String, String> mdc = new HashMap<>();
            MDC.getMap().forEach((k, v) -> mdc.put(k, String.valueOf(v)));
            captured.add(new CapturedLog(record.getLoggerName(),
                    record.getMessage() + " " + Arrays.toString(record.getParameters()),
                    Thread.currentThread().getName(), Map.copyOf(mdc)));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private final Logger rootLogger = Logger.getLogger("");
    private ExecutorService agentThread;

    private record CapturedLog(String logger, String message, String thread,
                               Map<String, String> mdc) {
    }

    @BeforeEach
    void installHandler() throws Exception {
        rootLogger.addHandler(captureHandler);
        agentThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "fake-agent-thread"));
        Path workspace = Files.createTempDirectory("axiom-log-correlation-");
        Mockito.when(workspaceService.ensureWorkspace(ArgumentMatchers.any())).thenReturn(workspace);
        MDC.clear();
    }

    @AfterEach
    void removeHandler() {
        rootLogger.removeHandler(captureHandler);
        agentThread.shutdownNow();
    }

    @Test
    void taskLogLinesCarryTraceIdAndMdcIsClearedAfterwards() throws Exception {
        Long subjectId = createSubject();
        TraceContext ctx = traceService.createTrace("create-workflow", "log correlation", null,
                subjectId, "create-workflow", "root", null, null);
        Long taskId = createTask(subjectId, ctx.traceId());
        traceService.addNode(ctx, "task", "in-progress", "Task", "task", taskId);

        AtomicReference<String> traceIdSeenByAgent = new AtomicReference<>();
        CompletableFuture<AgentResult> agentFuture = new CompletableFuture<>();
        Agent agent = Mockito.mock(Agent.class);
        Mockito.when(agent.execute(ArgumentMatchers.any())).thenAnswer(inv -> {
            traceIdSeenByAgent.set((String) MDC.get(LogContext.TRACE_ID));
            return agentFuture;
        });
        Mockito.when(agentPool.tryLease(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(Optional.of(new AgentLease(null, "fake-agent", agent)));

        TaskEntity task = QuarkusTransaction.requiringNew().call(() ->
                TaskEntity.<TaskEntity>findById(taskId));
        taskExecutionService.executeTask(task);

        // Synchronous part: the agent was invoked inside the task's context, and the
        // caller's MDC is restored once executeTask returns.
        assertEquals(ctx.traceId().toString(), traceIdSeenByAgent.get());
        assertTrue(MDC.getMap().isEmpty(), "MDC leaked after executeTask: " + MDC.getMap());

        // Asynchronous completion on a different thread, as the real agents do.
        agentThread.submit(() -> agentFuture.complete(AgentResult.success("done")))
                .get(30, TimeUnit.SECONDS);

        String expectedTrace = ctx.traceId().toString();
        CapturedLog completion = captured.stream()
                .filter(c -> c.message().startsWith("Task " + taskId + " completed"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No completion log line captured: " + captured));
        assertEquals("fake-agent-thread", completion.thread());
        assertEquals(expectedTrace, completion.mdc().get(LogContext.TRACE_ID));
        assertEquals(String.valueOf(taskId), completion.mdc().get(LogContext.TASK_ID));
        assertEquals(String.valueOf(subjectId), completion.mdc().get(LogContext.SUBJECT_ID));
        assertTrue(completion.mdc().get(LogContext.SUMMARY).contains("traceId=" + expectedTrace));

        // Every line logged by the task services for this task carries the trace ID.
        List<CapturedLog> taskLines = captured.stream()
                .filter(c -> c.logger().equals(TaskExecutionService.class.getName()))
                .toList();
        assertFalse(taskLines.isEmpty());
        taskLines.forEach(c -> assertEquals(expectedTrace, c.mdc().get(LogContext.TRACE_ID),
                "Line without trace ID: " + c));

        // No leak on the agent's (pooled) thread once the callback has finished.
        Map<String, String> agentThreadMdc = agentThread.submit(() -> Map.copyOf(
                LogContext.capture())).get(30, TimeUnit.SECONDS);
        assertTrue(agentThreadMdc.isEmpty(), "MDC leaked on agent thread: " + agentThreadMdc);
        assertNull(MDC.get(LogContext.SUMMARY));

        assertEquals("Completed", QuarkusTransaction.requiringNew().call(() ->
                TaskEntity.<TaskEntity>findById(taskId).status));
    }

    @Test
    void taskContextNestsInsideCallerContext() {
        Long subjectId = createSubject();
        TraceContext ctx = traceService.createTrace("create-workflow", "log correlation fail", null,
                subjectId, "create-workflow", "root", null, null);
        Long taskId = createTask(subjectId, ctx.traceId());
        Mockito.when(agentPool.tryLease(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Optional.empty());

        try (LogContext outer = LogContext.create().traceId("outer-trace")) {
            TaskEntity task = QuarkusTransaction.requiringNew().call(() ->
                    TaskEntity.<TaskEntity>findById(taskId));
            taskExecutionService.executeTask(task);
            // The task's context is nested: the caller's trace ID is restored.
            assertEquals("outer-trace", MDC.get(LogContext.TRACE_ID));
            assertNull(MDC.get(LogContext.TASK_ID));
        }
        assertTrue(MDC.getMap().isEmpty(), "MDC leaked: " + MDC.getMap());
    }

    private Long createSubject() {
        return QuarkusTransaction.requiringNew().call(() -> {
            SubjectEntity subject = new SubjectEntity();
            subject.name = "log-correlation-" + UUID.randomUUID();
            subject.type = "test-log";
            subject.ref = "https://example.com/" + UUID.randomUUID();
            subject.createdOn = Instant.now();
            subject.updatedOn = Instant.now();
            subject.persist();
            return subject.id;
        });
    }

    private Long createTask(Long subjectId, UUID traceId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            TaskEntity task = new TaskEntity();
            task.subjectId = subjectId;
            task.actionType = "log-correlation-action";
            task.createdBy = "test";
            task.status = "Pending";
            task.createdOn = Instant.now();
            task.traceId = traceId;
            task.persist();
            return task.id;
        });
    }
}
