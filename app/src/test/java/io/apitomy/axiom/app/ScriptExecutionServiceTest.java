package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ScriptExecutionService#substitutePlaceholders} focusing
 * on shell-injection hardening (issue #267): placeholder values must be passed
 * to the script as environment variables and referenced via quoted expansions,
 * never inlined into the script text.
 */
class ScriptExecutionServiceTest {

    private ScriptExecutionService newService() {
        ScriptExecutionService svc = new ScriptExecutionService();
        svc.objectMapper = new ObjectMapper();
        svc.httpPort = 9090;
        return svc;
    }

    private TaskEntity task(long id, Long projectId, String input) {
        TaskEntity task = new TaskEntity();
        task.id = id;
        task.projectId = projectId;
        task.eventId = 55L;
        task.input = input;
        return task;
    }

    private ProjectEntity project(String ref, String repository, String name) {
        ProjectEntity project = new ProjectEntity();
        project.ref = ref;
        project.repository = repository;
        project.name = name;
        return project;
    }

    @Test
    void standardPlaceholdersBecomeQuotedEnvReferences() {
        ScriptExecutionService svc = newService();
        Map<String, String> env = new LinkedHashMap<>();
        String template = "echo {{projectId}} {{taskId}} {{ref}} {{repository}} "
                + "{{projectName}} {{apiBaseUrl}} {{workDir}} {{managerInput}}";

        String resolved = svc.substitutePlaceholders(template,
                task(7L, 42L, "do the thing"),
                project("owner/repo#42", "owner/repo", "My Project"), env);

        // No raw values are inlined; each placeholder is a quoted env reference.
        assertEquals("echo \"${AXIOM_PROJECT_ID}\" \"${AXIOM_TASK_ID}\" \"${AXIOM_REF}\" "
                + "\"${AXIOM_REPOSITORY}\" \"${AXIOM_PROJECT_NAME}\" \"${AXIOM_API_URL}\" "
                + "\"${AXIOM_WORK_DIR}\" \"${AXIOM_MANAGER_INPUT}\"", resolved);

        assertEquals("42", env.get("AXIOM_PROJECT_ID"));
        assertEquals("7", env.get("AXIOM_TASK_ID"));
        assertEquals("owner/repo#42", env.get("AXIOM_REF"));
        assertEquals("owner/repo", env.get("AXIOM_REPOSITORY"));
        assertEquals("My Project", env.get("AXIOM_PROJECT_NAME"));
        assertEquals("http://localhost:9090/api/v1", env.get("AXIOM_API_URL"));
        assertEquals("do the thing", env.get("AXIOM_MANAGER_INPUT"));
    }

    @Test
    void onlyReferencedPlaceholdersAreExported() {
        ScriptExecutionService svc = newService();
        Map<String, String> env = new LinkedHashMap<>();

        svc.substitutePlaceholders("echo {{ref}}", task(1L, 2L, null),
                project("r", "repo", "n"), env);

        assertTrue(env.containsKey("AXIOM_REF"));
        assertFalse(env.containsKey("AXIOM_MANAGER_INPUT"));
        assertFalse(env.containsKey("AXIOM_PROJECT_NAME"));
    }

    @Test
    void injectionPayloadIsNotInlinedIntoScript() {
        ScriptExecutionService svc = newService();
        Map<String, String> env = new LinkedHashMap<>();
        String payload = "$(touch /tmp/pwned); `id`; rm -rf /";

        String resolved = svc.substitutePlaceholders("echo {{managerInput}}",
                task(1L, 2L, payload), project("r", "repo", "n"), env);

        // The dangerous characters live only in the env value, not the script.
        assertEquals("echo \"${AXIOM_MANAGER_INPUT}\"", resolved);
        assertEquals(payload, env.get("AXIOM_MANAGER_INPUT"));
        assertFalse(resolved.contains("touch"));
        assertFalse(resolved.contains("rm -rf"));
    }

    @Test
    void workflowInputsBecomeQuotedEnvReferences() {
        ScriptExecutionService svc = newService();
        Map<String, String> env = new LinkedHashMap<>();
        TaskEntity task = task(1L, 2L, "{\"title\":\"$(evil)\",\"count\":3}");
        task.workflowRunId = 99L;

        String resolved = svc.substitutePlaceholders(
                "echo {{inputs.title}} {{inputs.count}}", task,
                project("r", "repo", "n"), env);

        assertEquals("echo \"${AXIOM_INPUT_title}\" \"${AXIOM_INPUT_count}\"", resolved);
        assertEquals("$(evil)", env.get("AXIOM_INPUT_title"));
        assertEquals("3", env.get("AXIOM_INPUT_count"));
    }

    @Test
    void placeholderInsideDoubleQuotesIsNotReQuoted() {
        ScriptExecutionService svc = newService();
        Map<String, String> env = new LinkedHashMap<>();

        String resolved = svc.substitutePlaceholders(
                "git commit -m \"Automated fix: {{managerInput}}\"",
                task(1L, 2L, "fix login bug"), project("r", "repo", "n"), env);

        // Inside an existing double-quoted string the reference must NOT add its
        // own quotes, otherwise the value would word-split (issue: PR #280 review).
        assertEquals("git commit -m \"Automated fix: ${AXIOM_MANAGER_INPUT}\"", resolved);
        assertEquals("fix login bug", env.get("AXIOM_MANAGER_INPUT"));
    }

    @Test
    void placeholderInsideSingleQuotesBreaksOutToExpand() {
        ScriptExecutionService svc = newService();
        Map<String, String> env = new LinkedHashMap<>();

        String resolved = svc.substitutePlaceholders("echo '{{managerInput}}'",
                task(1L, 2L, "hello world"), project("r", "repo", "n"), env);

        // Single quotes suppress expansion, so the reference closes the region,
        // expands within double quotes, then reopens it. The template's own
        // quotes remain, yielding empty-string + expansion + empty-string.
        assertEquals("echo ''\"${AXIOM_MANAGER_INPUT}\"''", resolved);
        assertEquals("hello world", env.get("AXIOM_MANAGER_INPUT"));
    }

    @Test
    void whitespaceValueInDoubleQuotesStaysSingleArgument()
            throws IOException, InterruptedException {
        ScriptExecutionService svc = newService();
        Map<String, String> env = new LinkedHashMap<>();

        // Print the argument count and the first argument so we can confirm the
        // whitespace-bearing value was passed as exactly one argument.
        String resolved = svc.substitutePlaceholders(
                "set -- \"Automated fix: {{managerInput}}\"\nprintf '%s|%s' \"$#\" \"$1\"",
                task(1L, 2L, "fix login bug"), project("r", "repo", "n"), env);

        String output = runScript(resolved, env);
        assertEquals("1|Automated fix: fix login bug", output);
    }

    @Test
    void whitespaceValueInSingleQuotesExpandsAsSingleArgument()
            throws IOException, InterruptedException {
        ScriptExecutionService svc = newService();
        Map<String, String> env = new LinkedHashMap<>();

        String resolved = svc.substitutePlaceholders(
                "set -- '{{managerInput}}'\nprintf '%s|%s' \"$#\" \"$1\"",
                task(1L, 2L, "fix login bug"), project("r", "repo", "n"), env);

        String output = runScript(resolved, env);
        assertEquals("1|fix login bug", output);
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
    void injectionPayloadIsNotExecutedByBash() throws IOException, InterruptedException {
        ScriptExecutionService svc = newService();
        Map<String, String> env = new LinkedHashMap<>();

        Path marker = Files.createTempFile("axiom-inject-", ".marker");
        Files.deleteIfExists(marker);
        String payload = "$(touch " + marker + ")";

        String resolved = svc.substitutePlaceholders("printf '%s' {{managerInput}}",
                task(1L, 2L, payload), project("r", "repo", "n"), env);

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
}
