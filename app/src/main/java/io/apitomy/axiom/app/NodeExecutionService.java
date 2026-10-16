package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActionTypeField;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.logging.LogContext;
import io.apitomy.axiom.core.services.InputBindingResolver;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Executes node-mode action types: the action type's code is an ES module exporting {@code run(inputs, ctx)},
 * which the bundled runner ({@code axiom-node-runner.mjs}) loads and calls with the task's declared inputs.
 * Process handling (working directory, environment, timeout, output extraction) matches script mode.
 */
@ApplicationScoped
public class NodeExecutionService {

    private static final Logger LOG = Logger.getLogger(NodeExecutionService.class);

    private static final String RUNNER_RESOURCE = "node-runner/axiom-node-runner.mjs";
    private static final String RUNNER_FILE = "axiom-node-runner.mjs";
    static final String NODE_MISSING =
            "Node.js is required to run node action types but was not found on PATH.";

    @Inject
    ScriptExecutionService scriptExecutionService;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Executes the node action for a task asynchronously and completes the task with its result.
     *
     * @param task the task whose action type defines the code to run
     */
    public void executeNode(TaskEntity task) {
        try (LogContext ignored = TaskExecutionService.taskLogContext(task)) {
            scriptExecutionService.markTaskInProgress(task.id);
        }

        CompletableFuture.runAsync(() -> {
            Arc.container().requestContext().activate();
            try (LogContext ignored = TaskExecutionService.taskLogContext(task)) {
                ScriptExecutionService.RunResult result = runNode(task, null);
                scriptExecutionService.completeTask(task.id, result.output(), result.exitCode() == 0,
                        result.executionLog());
            } catch (Exception e) {
                LOG.errorf(e, "Node execution failed for task %d", task.id);
                scriptExecutionService.failTask(task.id, "Node execution error: " + e.getMessage());
            } finally {
                Arc.container().requestContext().terminate();
            }
        });
    }

    /**
     * Runs the task's node action synchronously.
     *
     * @param task    the task to run
     * @param subject the task's subject, or {@code null} to load it
     * @return the run result
     * @throws IOException          if files cannot be written
     * @throws InterruptedException if interrupted while waiting for the process
     */
    ScriptExecutionService.RunResult runNode(TaskEntity task, SubjectEntity subject)
            throws IOException, InterruptedException {
        ActionTypeEntity actionType = ActionTypeEntity.find("name", task.actionType).firstResult();
        if (actionType == null || actionType.codeTemplate == null || actionType.codeTemplate.isBlank()) {
            return new ScriptExecutionService.RunResult(1,
                    "No code configured for action type: " + task.actionType, null);
        }
        if (subject == null && task.subjectId != null) {
            subject = SubjectEntity.findById(task.subjectId);
        }
        LOG.infof("Executing node action for task %d (%s), timeout %ds",
                task.id, task.actionType, scriptExecutionService.timeoutSeconds());
        Instant startTime = Instant.now();
        ScriptExecutionService.ActionRun run = runNodeAction(actionType,
                InputBindingResolver.parseInputs(task.input, objectMapper), subject, String.valueOf(task.id));
        if (NODE_MISSING.equals(run.stderr())) {
            return new ScriptExecutionService.RunResult(1, NODE_MISSING, null);
        }
        String executionLog = ScriptExecutionService.buildExecutionLog("Node Execution Log", task, "Code",
                actionType.codeTemplate, null, run.stdout(), run.stderr(), run.exitCode(), startTime,
                run.durationMs());
        return new ScriptExecutionService.RunResult(run.exitCode(), run.output(), executionLog);
    }

    /**
     * Runs a node action type with explicit input values, outside any task (used for test runs). Only the
     * declared inputs are passed to the action.
     *
     * @param actionType the action type (may be transient); its {@code codeTemplate} must not be blank
     * @param inputs     input values by name
     * @param subject    the subject whose workspace to run in, or {@code null} for a temporary directory
     * @param runId      identifier exposed to the action as {@code AXIOM_TASK_ID}
     * @return the run; when node is not installed, exit code 1 with {@link #NODE_MISSING} as stderr
     * @throws IOException          if files cannot be written
     * @throws InterruptedException if interrupted while waiting for the process
     */
    ScriptExecutionService.ActionRun runNodeAction(ActionTypeEntity actionType, Map<String, Object> inputs,
                                                   SubjectEntity subject, String runId)
            throws IOException, InterruptedException {
        Path codeDir = Files.createTempDirectory("axiom-node-");
        ScriptExecutionService.WorkingDirectory workDir = null;
        try {
            long timeoutSeconds = scriptExecutionService.timeoutSeconds();
            Path runner = writeRunner(codeDir);
            Path actionFile = codeDir.resolve("action.mjs");
            Files.writeString(actionFile, actionType.codeTemplate);
            workDir = scriptExecutionService.workingDirectory(subject);
            ProcessBuilder pb = new ProcessBuilder("node", runner.toString(), actionFile.toString())
                    .directory(workDir.path().toFile());
            pb.environment().putAll(scriptExecutionService.buildBaseEnvironment(runId, workDir.path()));
            scriptExecutionService.applyActionEnvironment(pb, actionType);
            byte[] inputBytes = objectMapper.writeValueAsString(declaredInputs(inputs, actionType))
                    .getBytes(StandardCharsets.UTF_8);
            Instant startTime = Instant.now();
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                if (isNodeMissing(e, workDir.path())) {
                    return new ScriptExecutionService.ActionRun(1, "", NODE_MISSING, null, 0, true, null);
                }
                throw e;
            }
            // Written asynchronously so capture()'s timeout bounds a process that never drains stdin.
            CompletableFuture.runAsync(() -> {
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(inputBytes);
                } catch (IOException e) {
                    // The process exited before reading its inputs; its stderr explains why.
                    LOG.debugf("Could not write inputs to node action %s: %s", runId, e.getMessage());
                }
            });
            ScriptExecutionService.CapturedOutput captured =
                    ScriptExecutionService.capture(process, timeoutSeconds);
            long durationMs = Duration.between(startTime, Instant.now()).toMillis();
            String stdout = captured.stdout();
            if (!captured.finished()) {
                stdout = stdout + "\n[Node action timed out after " + timeoutSeconds + "s]";
            }
            return new ScriptExecutionService.ActionRun(captured.exitCode(), stdout, captured.stderr(),
                    ScriptExecutionService.extractScriptOutput(stdout), durationMs, captured.finished(), null);
        } finally {
            deleteRecursively(codeDir);
            if (workDir != null) {
                workDir.cleanUp();
            }
        }
    }

    /**
     * Collects the action type's declared inputs that have a value; undeclared and absent inputs are omitted.
     */
    private Map<String, Object> declaredInputs(Map<String, Object> values, ActionTypeEntity actionType) {
        Map<String, Object> declared = new LinkedHashMap<>();
        if (actionType.inputs != null) {
            for (ActionTypeField input : actionType.inputs) {
                if (input.name != null && values.containsKey(input.name)) {
                    declared.put(input.name, values.get(input.name));
                }
            }
        }
        return declared;
    }

    /**
     * Copies the bundled runner from the classpath into {@code dir}.
     *
     * @param dir the directory to write to
     * @return the path of the written runner
     * @throws IOException if the resource is missing or cannot be written
     */
    static Path writeRunner(Path dir) throws IOException {
        Path runner = dir.resolve(RUNNER_FILE);
        try (InputStream in = NodeExecutionService.class.getClassLoader().getResourceAsStream(RUNNER_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing classpath resource " + RUNNER_RESOURCE);
            }
            Files.copy(in, runner, StandardCopyOption.REPLACE_EXISTING);
        }
        return runner;
    }

    /**
     * Decides whether a start failure means node is missing; a missing working directory raises the same
     * "No such file" error, so it only counts when the directory exists.
     *
     * @param e       the start failure
     * @param workDir the process working directory
     * @return true if node itself could not be found
     */
    static boolean isNodeMissing(IOException e, Path workDir) {
        String message = e.getMessage();
        return message != null && (message.contains("error=2") || message.contains("No such file"))
                && Files.isDirectory(workDir);
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            LOG.debugf("Failed to delete temporary node directory %s: %s", dir, e.getMessage());
        }
    }
}
