package io.apitomy.axiom.core.services;

import io.apitomy.axiom.api.beans.NewScheduledJob;
import io.apitomy.axiom.core.services.ScheduledJobValidator.Severity;
import io.apitomy.axiom.core.services.ScheduledJobValidator.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ScheduledJobValidator}.
 */
class ScheduledJobValidatorTest {

    // ── Name validation ─────────────────────────────────────────────

    @Test
    void missingNameIsError() {
        NewScheduledJob job = makeActor(null, "desc", "daily", "Do work");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("name")));
    }

    @Test
    void blankNameIsError() {
        NewScheduledJob job = makeActor("  ", "desc", "daily", "Do work");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.hasErrors());
    }

    @Test
    void validNameNoError() {
        NewScheduledJob job = makeActor("nightly-cleanup", "desc", "daily", "Do work");
        job.setScheduleTime("08:00");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("name")));
    }

    // ── Description validation ──────────────────────────────────────

    @Test
    void missingDescriptionIsWarning() {
        NewScheduledJob job = makeActor("test", null, "daily", "Do work");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertFalse(result.hasErrors());
        assertTrue(result.warnings().stream().anyMatch(m -> m.field().equals("description")));
    }

    // ── Schedule validation ─────────────────────────────────────────

    @Test
    void missingScheduleIsError() {
        NewScheduledJob job = makeActor("test", "desc", null, "Do work");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("schedule")));
    }

    @Test
    void invalidScheduleIsError() {
        NewScheduledJob job = makeActor("test", "desc", "biweekly", "Do work");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("schedule") && m.message().contains("biweekly")));
    }

    @Test
    void allPresetSchedulesAreValid() {
        for (String schedule : List.of("none", "hourly", "daily", "weekly", "monthly")) {
            NewScheduledJob job = makeActor("test", "desc", schedule, "Do work");
            if ("weekly".equals(schedule)) {
                job.setScheduleDayOfWeek("monday");
            }
            if (!"none".equals(schedule)) {
                job.setScheduleTime("09:00");
            }

            ValidationResult result = ScheduledJobValidator.validate(job);

            assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("schedule")),
                    "Schedule '" + schedule + "' should be valid");
        }
    }

    @Test
    void weeklyWithoutDayOfWeekIsWarning() {
        NewScheduledJob job = makeActor("test", "desc", "weekly", "Do work");
        job.setScheduleTime("08:00");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.warnings().stream().anyMatch(m -> m.field().equals("scheduleDayOfWeek")));
    }

    @Test
    void weeklyWithInvalidDayIsError() {
        NewScheduledJob job = makeActor("test", "desc", "weekly", "Do work");
        job.setScheduleTime("08:00");
        job.setScheduleDayOfWeek("funday");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("scheduleDayOfWeek") && m.message().contains("funday")));
    }

    @Test
    void weeklyWithValidDayAccepted() {
        NewScheduledJob job = makeActor("test", "desc", "weekly", "Do work");
        job.setScheduleTime("08:00");
        job.setScheduleDayOfWeek("monday");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertFalse(result.messages().stream().anyMatch(m -> m.field().equals("scheduleDayOfWeek")));
    }

    @Test
    void nonNoneScheduleWithoutTimeIsWarning() {
        NewScheduledJob job = makeActor("test", "desc", "daily", "Do work");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.warnings().stream().anyMatch(
                m -> m.field().equals("scheduleTime") && m.message().contains("08:00")));
    }

    @Test
    void invalidScheduleTimeIsError() {
        NewScheduledJob job = makeActor("test", "desc", "daily", "Do work");
        job.setScheduleTime("25:99");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("scheduleTime")));
    }

    @Test
    void validScheduleTimeAccepted() {
        NewScheduledJob job = makeActor("test", "desc", "daily", "Do work");
        job.setScheduleTime("08:00");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("scheduleTime")));
    }

    // ── Execution mode validation ───────────────────────────────────

    @Test
    void missingExecutionModeIsError() {
        NewScheduledJob job = new NewScheduledJob();
        job.setName("test");
        job.setDescription("desc");
        job.setSchedule("daily");
        job.setScheduleTime("08:00");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("executionMode")));
    }

    @Test
    void invalidExecutionModeIsError() {
        NewScheduledJob job = new NewScheduledJob();
        job.setName("test");
        job.setDescription("desc");
        job.setSchedule("daily");
        job.setScheduleTime("08:00");
        job.setExecutionMode("batch");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("executionMode") && m.message().contains("batch")));
    }

    @Test
    void validActorExecutionMode() {
        NewScheduledJob job = makeActor("test", "desc", "daily", "Do work");
        job.setScheduleTime("08:00");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("executionMode")));
    }

    @Test
    void validScriptExecutionMode() {
        NewScheduledJob job = makeScript("test", "desc", "daily", "echo hi");
        job.setScheduleTime("08:00");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("executionMode")));
    }

    // ── Prompt template validation (agent mode) ─────────────────────

    @Test
    void actorMissingPromptTemplateIsErrorWhenEnabled() {
        NewScheduledJob job = makeActor("test", "desc", "daily", null);
        job.setScheduleTime("08:00");
        job.setEnabled(true);

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("promptTemplate")));
    }

    @Test
    void actorMissingPromptTemplateIsWarningWhenDisabled() {
        NewScheduledJob job = makeActor("test", "desc", "daily", null);
        job.setScheduleTime("08:00");
        job.setEnabled(false);

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("promptTemplate")));
        assertTrue(result.warnings().stream().anyMatch(m -> m.field().equals("promptTemplate")));
    }

    @Test
    void actorBlankPromptTemplateIsErrorWhenEnabled() {
        NewScheduledJob job = makeActor("test", "desc", "daily", "  ");
        job.setScheduleTime("08:00");
        job.setEnabled(true);

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.hasErrors());
    }

    @Test
    void actorValidPromptTemplateNoError() {
        NewScheduledJob job = makeActor("test", "desc", "daily", "Run the nightly cleanup");
        job.setScheduleTime("08:00");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("promptTemplate")));
    }

    @Test
    void actorPromptWithRecognizedPlaceholderIsValid() {
        NewScheduledJob job = makeActor("test", "desc", "daily",
                "Run job {{jobName}} via {{apiBaseUrl}}");
        job.setScheduleTime("08:00");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("promptTemplate")));
    }

    @Test
    void actorPromptWithUnrecognizedPlaceholderIsError() {
        NewScheduledJob job = makeActor("test", "desc", "daily",
                "Do {{foo}} with {{jobName}}");
        job.setScheduleTime("08:00");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("promptTemplate") && m.message().contains("{{foo}}")));
    }

    @Test
    void actorPromptWithMalformedPlaceholderIsError() {
        NewScheduledJob job = makeActor("test", "desc", "daily",
                "Do {{ jobName }} thing");
        job.setScheduleTime("08:00");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("promptTemplate") && m.message().contains("malformed")));
    }

    // ── Script template validation (script mode) ────────────────────

    @Test
    void scriptMissingScriptTemplateIsErrorWhenEnabled() {
        NewScheduledJob job = makeScript("test", "desc", "daily", null);
        job.setScheduleTime("08:00");
        job.setEnabled(true);

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("scriptTemplate")));
    }

    @Test
    void scriptMissingScriptTemplateIsWarningWhenDisabled() {
        NewScheduledJob job = makeScript("test", "desc", "daily", null);
        job.setScheduleTime("08:00");
        job.setEnabled(false);

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("scriptTemplate")));
        assertTrue(result.warnings().stream().anyMatch(m -> m.field().equals("scriptTemplate")));
    }

    @Test
    void scriptValidScriptTemplateNoError() {
        NewScheduledJob job = makeScript("test", "desc", "daily", "echo hello");
        job.setScheduleTime("08:00");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("scriptTemplate")));
    }

    @Test
    void scriptWithRecognizedPlaceholdersIsValid() {
        NewScheduledJob job = makeScript("test", "desc", "daily",
                "echo {{jobName}} {{jobId}} {{runId}} {{apiBaseUrl}}");
        job.setScheduleTime("08:00");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("scriptTemplate")));
    }

    @Test
    void scriptWithUnrecognizedPlaceholderIsError() {
        NewScheduledJob job = makeScript("test", "desc", "daily",
                "echo {{badPlaceholder}}");
        job.setScheduleTime("08:00");

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("scriptTemplate")
                        && m.message().contains("{{badPlaceholder}}")));
    }

    // ── Labels validation ───────────────────────────────────────────

    @Test
    void blankLabelIsError() {
        NewScheduledJob job = makeActor("test", "desc", "daily", "Do work");
        job.setScheduleTime("08:00");
        job.setLabels(List.of("good", "  "));

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(m -> m.field().contains("labels")));
    }

    @Test
    void duplicateLabelsIsWarning() {
        NewScheduledJob job = makeActor("test", "desc", "daily", "Do work");
        job.setScheduleTime("08:00");
        job.setLabels(List.of("nightly", "Nightly"));

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.warnings().stream().anyMatch(
                m -> m.message().contains("Duplicate label")));
    }

    // ── Allowed tools validation (agent mode) ───────────────────────

    @Test
    void validToolRefsPass() {
        NewScheduledJob job = makeActor("test", "desc", "daily", "Do work");
        job.setScheduleTime("08:00");
        job.setAllowedTools(List.of(
                "@Read-Only Tools",
                "mcp__axiom-tools__list_github_prs",
                "mcp__axiom-sdk__axiom_list_projects",
                "Read", "Bash(gh pr *)"));

        ScheduledJobValidator.KnownNames known = new ScheduledJobValidator.KnownNames(
                null,
                Set.of("list_github_prs"),
                Set.of("Read-Only Tools"),
                Set.of("mcp__axiom-sdk__axiom_list_projects"));

        assertFalse(ScheduledJobValidator.validate(job, known).hasErrors());
    }

    @Test
    void toolsetRefToMissingToolsetIsError() {
        NewScheduledJob job = makeActor("test", "desc", "daily", "Do work");
        job.setScheduleTime("08:00");
        job.setAllowedTools(List.of("@Missing"));

        ScheduledJobValidator.KnownNames known = new ScheduledJobValidator.KnownNames(
                null, null, Set.of("Read-Only Tools"), null);

        assertTrue(ScheduledJobValidator.validate(job, known).hasErrors());
    }

    @Test
    void toolsetRefToExistingToolsetIsValid() {
        NewScheduledJob job = makeActor("test", "desc", "daily", "Do work");
        job.setScheduleTime("08:00");
        job.setAllowedTools(List.of("@Job Tools"));

        ScheduledJobValidator.KnownNames known = new ScheduledJobValidator.KnownNames(
                null, null, Set.of("Job Tools"), null);

        assertFalse(ScheduledJobValidator.validate(job, known).errors().stream()
                .anyMatch(m -> m.field().contains("allowedTools")));
    }

    // ── Environment validation ──────────────────────────────────────

    @Test
    void invalidEnvKeyIsError() {
        NewScheduledJob job = makeActor("test", "desc", "daily", "Do work");
        job.setScheduleTime("08:00");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("my-token", "value");
        job.setEnvironment(env);

        assertTrue(ScheduledJobValidator.validate(job).hasErrors());
    }

    @Test
    void malformedSecretRefIsError() {
        NewScheduledJob job = makeActor("test", "desc", "daily", "Do work");
        job.setScheduleTime("08:00");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("TOKEN", "${secret:}");
        job.setEnvironment(env);

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("environment.TOKEN") && m.message().contains("Malformed")));
    }

    @Test
    void unknownSecretWithKnownNamesIsError() {
        NewScheduledJob job = makeActor("test", "desc", "daily", "Do work");
        job.setScheduleTime("08:00");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("TOKEN", "${secret:MISSING}");
        job.setEnvironment(env);

        ScheduledJobValidator.KnownNames known = new ScheduledJobValidator.KnownNames(
                Set.of("OTHER"), null, null, null);

        assertTrue(ScheduledJobValidator.validate(job, known).hasErrors());
    }

    @Test
    void validSecretRefPasses() {
        NewScheduledJob job = makeActor("test", "desc", "daily", "Do work");
        job.setScheduleTime("08:00");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("GH_TOKEN", "${secret:GH_TOKEN}");
        job.setEnvironment(env);

        ScheduledJobValidator.KnownNames known = new ScheduledJobValidator.KnownNames(
                Set.of("GH_TOKEN"), null, null, null);

        assertFalse(ScheduledJobValidator.validate(job, known).hasErrors());
    }

    // ── Result helpers ──────────────────────────────────────────────

    @Test
    void errorsAndWarningsFilterCorrectly() {
        NewScheduledJob job = new NewScheduledJob();

        ValidationResult result = ScheduledJobValidator.validate(job);

        assertTrue(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.errors().stream().allMatch(m -> m.severity() == Severity.ERROR));
        assertTrue(result.warnings().stream().allMatch(m -> m.severity() == Severity.WARNING));
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static NewScheduledJob makeActor(String name, String description,
                                              String schedule, String promptTemplate) {
        NewScheduledJob job = new NewScheduledJob();
        job.setName(name);
        job.setDescription(description);
        job.setSchedule(schedule);
        job.setExecutionMode("agent");
        job.setPromptTemplate(promptTemplate);
        return job;
    }

    private static NewScheduledJob makeScript(String name, String description,
                                               String schedule, String scriptTemplate) {
        NewScheduledJob job = new NewScheduledJob();
        job.setName(name);
        job.setDescription(description);
        job.setSchedule(schedule);
        job.setExecutionMode("script");
        job.setScriptTemplate(scriptTemplate);
        return job;
    }
}
