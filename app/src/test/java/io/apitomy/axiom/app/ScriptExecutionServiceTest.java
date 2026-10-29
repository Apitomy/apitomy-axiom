package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActionTypeField;
import io.apitomy.axiom.core.services.EnvironmentResolver;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.services.WorkspaceService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ScriptExecutionService#substitutePlaceholders} focusing
 * on declared-input binding and shell-injection hardening (issue #267): input values must be passed
 * to the script as environment variables and referenced via quoted expansions,
 * never inlined into the script text.
 */
class ScriptExecutionServiceTest {

    private ScriptExecutionService newService() {
        ScriptExecutionService svc = new ScriptExecutionService();
        svc.objectMapper = new ObjectMapper();
        svc.httpPort = 9090;
        svc.workspaceService = new WorkspaceService() {
            @Override
            public Path getWorkspacePath(SubjectEntity subject) {
                return Path.of("/ws", "subject-" + subject.id);
            }
        };
        return svc;
    }

    private TaskEntity task(long id, Long subjectId, String input) {
        TaskEntity task = new TaskEntity();
        task.id = id;
        task.subjectId = subjectId;
        task.eventId = UUID.fromString("00000000-0000-0000-0000-000000000055");
        task.input = input;
        return task;
    }

    private SubjectEntity subject() {
        SubjectEntity subject = new SubjectEntity();
        subject.ref = "owner/repo#42";
        subject.repository = "owner/repo";
        subject.name = "My Subject";
        subject.id = 42L;
        return subject;
    }

    private static final List<ActionTypeField> INPUTS = List.of(
            new ActionTypeField("title", "string", true, null),
            new ActionTypeField("count", "number", false, null),
            new ActionTypeField("x", "string", false, null));

    private TaskEntity workflowTask(String input) {
        TaskEntity task = task(7L, 42L, input);
        task.workflowRunId = 99L;
        return task;
    }

    private String substitute(String template, String title, Map<String, String> env) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("title", title);
        try {
            return newService().substitutePlaceholders(template,
                    workflowTask(new ObjectMapper().writeValueAsString(inputs)), INPUTS, env);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void scriptRunsInItsWorkingDirectoryNotTheJvmDirectory() throws IOException, InterruptedException {
        Path workDir = Files.createTempDirectory("axiom-test-workdir-");
        Path scriptFile = Files.createTempFile("axiom-script-", ".sh");
        try {
            Files.writeString(scriptFile, "pwd; git rev-parse --show-toplevel 2>/dev/null || echo no-git\n");
            Process process = ScriptExecutionService.scriptProcess(scriptFile, workDir, Map.of()).start();
            String out = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor(30, TimeUnit.SECONDS);
            String[] lines = out.split("\n");
            assertEquals(workDir.toRealPath().toString(), lines[0]);
            assertEquals("no-git", lines[1], "a script must not see the repository Axiom runs from");
        } finally {
            Files.deleteIfExists(scriptFile);
            Files.deleteIfExists(workDir);
        }
    }

    @Test
    void workingDirectoryIsTheSubjectWorkspaceOrATemporaryDirectory() throws IOException {
        Path workspace = Files.createTempDirectory("axiom-test-ws-");
        ScriptExecutionService svc = newService();
        svc.workspaceService = new WorkspaceService() {
            @Override
            public Path ensureWorkspace(SubjectEntity subject) {
                return workspace;
            }
        };
        ScriptExecutionService.WorkingDirectory forSubject = svc.workingDirectory(subject());
        assertEquals(workspace, forSubject.path());
        assertFalse(forSubject.temporary());

        ScriptExecutionService.WorkingDirectory noSubject = svc.workingDirectory(null);
        assertTrue(noSubject.temporary());
        assertTrue(Files.isDirectory(noSubject.path()));
        noSubject.cleanUp();
        assertFalse(Files.exists(noSubject.path()));
        assertTrue(Files.isDirectory(workspace), "the subject workspace is never deleted");
        forSubject.cleanUp();
        assertTrue(Files.isDirectory(workspace));
        Files.deleteIfExists(workspace);
    }

    @Test
    void baseEnvironmentHasPlumbingOnlyAndNoSubjectData() {
        Map<String, String> env = newService().buildBaseEnvironment(workflowTask(null), Path.of("/ws/subject-42"));

        assertEquals("http://localhost:9090/api/v1", env.get("AXIOM_API_URL"));
        assertEquals("7", env.get("AXIOM_TASK_ID"));
        assertEquals("/ws/subject-42", env.get("AXIOM_WORK_DIR"));
        for (String removed : List.of("AXIOM_SUBJECT_ID", "AXIOM_REF", "AXIOM_REPOSITORY",
                "AXIOM_SUBJECT_NAME", "AXIOM_EVENT_ID")) {
            assertFalse(env.containsKey(removed), removed + " must not be set");
        }
    }

    @Test
    void removedSubjectPlaceholdersAreNotBound() {
        Map<String, String> env = new LinkedHashMap<>();
        String template = "echo {{subjectId}} {{ref}} {{repository}} {{subjectName}} {{eventId}} "
                + "{{taskId}} {{apiBaseUrl}} {{workDir}}";

        String resolved = substitute(template, "t", env);

        assertEquals(template, resolved);
        assertTrue(env.isEmpty());
    }

    @Test
    void declaredInputsBecomeQuotedEnvReferences() {
        Map<String, String> env = new LinkedHashMap<>();
        String resolved = newService().substitutePlaceholders(
                "echo {{inputs.title}} {{inputs.count}}",
                workflowTask("{\"title\":\"$(evil)\",\"count\":3}"), INPUTS, env);

        assertEquals("echo \"${AXIOM_INPUT_title}\" \"${AXIOM_INPUT_count}\"", resolved);
        assertEquals("$(evil)", env.get("AXIOM_INPUT_title"));
        assertEquals("3", env.get("AXIOM_INPUT_count"));
    }

    @Test
    void unmappedOptionalInputRendersEmpty() throws IOException, InterruptedException {
        Map<String, String> env = new LinkedHashMap<>();
        String resolved = substitute("printf '[%s]' {{inputs.x}}", "t", env);

        assertEquals("printf '[%s]' \"${AXIOM_INPUT_x}\"", resolved);
        assertEquals("", env.get("AXIOM_INPUT_x"));
        assertEquals("[]", runScript(resolved, env));
    }

    @Test
    void undeclaredInputIsNotBound() {
        Map<String, String> env = new LinkedHashMap<>();
        String resolved = newService().substitutePlaceholders("echo {{inputs.extra}}",
                workflowTask("{\"extra\":\"v\"}"), INPUTS, env);

        assertEquals("echo {{inputs.extra}}", resolved);
        assertTrue(env.isEmpty());
    }

    @Test
    void nonWorkflowTaskHasNoInputValues() {
        Map<String, String> env = new LinkedHashMap<>();
        String resolved = newService().substitutePlaceholders("echo {{inputs.title}}",
                task(1L, 2L, "{\"title\":\"v\"}"), INPUTS, env);

        assertEquals("echo \"${AXIOM_INPUT_title}\"", resolved);
        assertEquals("", env.get("AXIOM_INPUT_title"));
    }

    @Test
    void injectionPayloadIsNotInlinedIntoScript() {
        Map<String, String> env = new LinkedHashMap<>();
        String payload = "$(touch /tmp/pwned); `id`; rm -rf /";

        String resolved = substitute("echo {{inputs.title}}", payload, env);

        // The dangerous characters live only in the env value, not the script.
        assertEquals("echo \"${AXIOM_INPUT_title}\"", resolved);
        assertEquals(payload, env.get("AXIOM_INPUT_title"));
        assertFalse(resolved.contains("touch"));
        assertFalse(resolved.contains("rm -rf"));
    }

    @Test
    void placeholderInsideDoubleQuotesIsNotReQuoted() {
        Map<String, String> env = new LinkedHashMap<>();

        String resolved = substitute("git commit -m \"Automated fix: {{inputs.title}}\"",
                "fix login bug", env);

        // Inside an existing double-quoted string the reference must NOT add its
        // own quotes, otherwise the value would word-split (issue: PR #280 review).
        assertEquals("git commit -m \"Automated fix: ${AXIOM_INPUT_title}\"", resolved);
        assertEquals("fix login bug", env.get("AXIOM_INPUT_title"));
    }

    @Test
    void placeholderInsideSingleQuotesBreaksOutToExpand() {
        Map<String, String> env = new LinkedHashMap<>();

        String resolved = substitute("echo '{{inputs.title}}'", "hello world", env);

        // Single quotes suppress expansion, so the reference closes the region,
        // expands within double quotes, then reopens it.
        assertEquals("echo ''\"${AXIOM_INPUT_title}\"''", resolved);
        assertEquals("hello world", env.get("AXIOM_INPUT_title"));
    }

    @Test
    void apostropheInsideCommentDoesNotQuoteLaterPlaceholder() {
        Map<String, String> env = new LinkedHashMap<>();

        String resolved = substitute("# sync-target's flag\n{{inputs.title}}\n", "1", env);

        // An apostrophe in a comment starts no quote region, so the placeholder on the
        // next line resolves in the unquoted form, not the single-quote break-out form
        // that would arrive as literal quote characters.
        assertEquals("# sync-target's flag\n\"${AXIOM_INPUT_title}\"\n", resolved);
        assertEquals("1", env.get("AXIOM_INPUT_title"));
    }

    @Test
    void placeholderInsideCommentIsLeftUntouchedAndNotBound() {
        Map<String, String> env = new LinkedHashMap<>();

        String resolved = substitute("# uses {{inputs.title}}\necho done\n", "1", env);

        // bash discards comments before expansion, so the placeholder stays inert text
        // and no variable is bound for a value the script could never see.
        assertEquals("# uses {{inputs.title}}\necho done\n", resolved);
        assertFalse(env.containsKey("AXIOM_INPUT_title"));
    }

    @Test
    void whitespaceValueInDoubleQuotesStaysSingleArgument()
            throws IOException, InterruptedException {
        Map<String, String> env = new LinkedHashMap<>();

        String resolved = substitute(
                "set -- \"Automated fix: {{inputs.title}}\"\nprintf '%s|%s' \"$#\" \"$1\"",
                "fix login bug", env);

        assertEquals("1|Automated fix: fix login bug", runScript(resolved, env));
    }

    @Test
    void whitespaceValueInSingleQuotesExpandsAsSingleArgument()
            throws IOException, InterruptedException {
        Map<String, String> env = new LinkedHashMap<>();

        String resolved = substitute("set -- '{{inputs.title}}'\nprintf '%s|%s' \"$#\" \"$1\"",
                "fix login bug", env);

        assertEquals("1|fix login bug", runScript(resolved, env));
    }

    private String runScript(String script, Map<String, String> env)
            throws IOException, InterruptedException {
        Path scriptFile = Files.createTempFile("axiom-script-", ".sh");
        try {
            Files.writeString(scriptFile, script);
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", scriptFile.toString())
                    .redirectErrorStream(true);
            pb.environment().putAll(env);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor(30, TimeUnit.SECONDS);
            return output;
        } finally {
            Files.deleteIfExists(scriptFile);
        }
    }

    @Test
    void jsonArrayInUnquotedHeredocArrivesIntact() throws IOException, InterruptedException {
        Map<String, String> env = new LinkedHashMap<>();
        String resolved = substitute("""
                cat <<LABELS | jq -r 'join(",")'
                {{inputs.title}}
                LABELS
                """, "[\"type/question\", \"Impacts Documentation\"]", env);
        assertEquals("type/question,Impacts Documentation", runScript(resolved, env).trim());
    }

    @Test
    void dashHeredocAlsoExpands() throws IOException, InterruptedException {
        Map<String, String> env = new LinkedHashMap<>();
        String resolved = substitute("cat <<-END\n\t{{inputs.title}}\n\tEND\n", "hello world", env);
        assertEquals("hello world", runScript(resolved, env).trim());
    }

    @Test
    void injectionPayloadInHeredocIsNotExecuted() throws IOException, InterruptedException {
        Map<String, String> env = new LinkedHashMap<>();
        Path marker = Files.createTempFile("axiom-inject-heredoc-", ".marker");
        Files.deleteIfExists(marker);
        String payload = "$(touch " + marker + ") `touch " + marker + "`";
        String resolved = substitute("cat <<EOF\n{{inputs.title}}\nEOF\n", payload, env);
        assertEquals(payload, runScript(resolved, env).trim());
        assertFalse(Files.exists(marker), "heredoc expansion must not execute the value");
    }

    @Test
    void placeholderAfterHeredocIsQuotedAgain() {
        Map<String, String> env = new LinkedHashMap<>();
        String resolved = substitute("cat <<EOF\n{{inputs.title}}\nEOF\necho {{inputs.title}}\n", "x", env);
        assertEquals("cat <<EOF\n${AXIOM_INPUT_title}\nEOF\necho \"${AXIOM_INPUT_title}\"\n", resolved);
    }

    @Test
    void injectionPayloadIsNotExecutedByBash() throws IOException, InterruptedException {
        Map<String, String> env = new LinkedHashMap<>();

        Path marker = Files.createTempFile("axiom-inject-", ".marker");
        Files.deleteIfExists(marker);
        String payload = "$(touch " + marker + ")";

        String resolved = substitute("printf '%s' {{inputs.title}}", payload, env);

        Path scriptFile = Files.createTempFile("axiom-inject-script-", ".sh");
        try {
            Files.writeString(scriptFile, resolved);
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", scriptFile.toString())
                    .redirectErrorStream(true);
            pb.environment().putAll(env);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor(30, TimeUnit.SECONDS);

            // The payload is echoed literally and the command substitution never ran.
            assertEquals(payload, output);
            assertFalse(Files.exists(marker), "Injected command must not execute");
        } finally {
            Files.deleteIfExists(scriptFile);
            Files.deleteIfExists(marker);
        }
    }

    @Test
    void runsAScriptActionWithoutATask() throws Exception {
        ActionTypeEntity at = new ActionTypeEntity();
        at.name = "draft-script";
        at.executionMode = "script";
        at.inputs = new ArrayList<>(List.of(new ActionTypeField("name", "string", true, null)));
        at.scriptTemplate = "#!/usr/bin/env bash\nprintf '{\"greeting\":\"hello %s\"}\\n' {{inputs.name}}\n"
                + "echo 'log line' >&2\n";
        ScriptExecutionService svc = newService();
        // Plain unit test (no database): report a custom, empty environment so no secrets are loaded.
        svc.environmentResolver = new EnvironmentResolver() {
            @Override
            public boolean hasCustomEnvironment(String environmentJson) {
                return true;
            }

            @Override
            public Map<String, String> resolve(String environmentJson) {
                return Map.of();
            }
        };
        svc.timeoutSeconds = 30;
        ScriptExecutionService.ActionRun run =
                svc.runScriptAction(at, Map.of("name", "world"), null, "test-run");
        assertEquals(0, run.exitCode());
        assertTrue(run.finished());
        assertEquals("{\"greeting\":\"hello world\"}", run.output());
        assertTrue(run.stderr().contains("log line"));
        assertTrue(run.resolvedScript().contains("AXIOM_INPUT_name"));
    }
}
