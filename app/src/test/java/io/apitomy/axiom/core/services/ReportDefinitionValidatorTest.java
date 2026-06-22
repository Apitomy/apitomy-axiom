package io.apitomy.axiom.core.services;

import io.apitomy.axiom.api.beans.NewReportDefinition;
import io.apitomy.axiom.core.services.ReportDefinitionValidator.Severity;
import io.apitomy.axiom.core.services.ReportDefinitionValidator.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ReportDefinitionValidator}.
 */
class ReportDefinitionValidatorTest {

    // ── Valid definition ─────────────────────────────────────────────

    @Test
    void validDefinitionPassesWithNoErrors() {
        NewReportDefinition def = makeDef("weekly-status", "Weekly report",
                "weekly", "last-7d",
                "Report for {{repositories}} from {{timeRangeStart}} to {{timeRangeEnd}}");
        def.setScheduleTime("08:00");
        def.setScheduleDayOfWeek("monday");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    @Test
    void validOnDemandDefinition() {
        NewReportDefinition def = makeDef("ad-hoc", "On demand",
                "none", "last-24h",
                "Report for {{repositories}} window {{timeWindow}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.hasErrors());
    }

    // ── Name validation ─────────────────────────────────────────────

    @Test
    void missingNameIsError() {
        NewReportDefinition def = makeDef(null, "desc", "daily", "last-24h", "prompt {{repositories}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("name")));
    }

    @Test
    void blankNameIsError() {
        NewReportDefinition def = makeDef("  ", "desc", "daily", "last-24h", "prompt {{repositories}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
    }

    // ── Description validation ──────────────────────────────────────

    @Test
    void missingDescriptionIsWarning() {
        NewReportDefinition def = makeDef("test", null, "daily", "last-24h", "prompt {{repositories}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.hasErrors());
        assertTrue(result.warnings().stream().anyMatch(m -> m.field().equals("description")));
    }

    // ── Schedule validation ─────────────────────────────────────────

    @Test
    void missingScheduleIsError() {
        NewReportDefinition def = makeDef("test", "desc", null, "last-24h", "prompt {{repositories}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("schedule")));
    }

    @Test
    void invalidScheduleIsError() {
        NewReportDefinition def = makeDef("test", "desc", "biweekly", "last-24h", "prompt {{repositories}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("schedule") && m.message().contains("biweekly")));
    }

    @Test
    void cronScheduleIsValid() {
        NewReportDefinition def = makeDef("test", "desc", "0 8 * * 1", "last-7d", "prompt {{repositories}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("schedule")));
    }

    @Test
    void allPresetSchedulesAreValid() {
        for (String schedule : List.of("none", "hourly", "daily", "weekly", "monthly")) {
            NewReportDefinition def = makeDef("test", "desc", schedule, "last-24h", "prompt {{repositories}}");
            if ("weekly".equals(schedule)) {
                def.setScheduleDayOfWeek("monday");
            }
            def.setScheduleTime("09:00");

            ValidationResult result = ReportDefinitionValidator.validate(def);

            assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("schedule")),
                    "Schedule '" + schedule + "' should be valid");
        }
    }

    // ── Schedule time validation ────────────────────────────────────

    @Test
    void invalidScheduleTimeIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt {{repositories}}");
        def.setScheduleTime("25:99");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("scheduleTime")));
    }

    @Test
    void validScheduleTimeAccepted() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt {{repositories}}");
        def.setScheduleTime("14:30");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("scheduleTime")));
    }

    @Test
    void missingScheduleTimeIsWarningForNonNone() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt {{repositories}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.warnings().stream().anyMatch(
                m -> m.field().equals("scheduleTime") && m.message().contains("08:00")));
    }

    @Test
    void missingScheduleTimeNoWarningForNone() {
        NewReportDefinition def = makeDef("test", "desc", "none", "last-24h", "prompt {{repositories}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.messages().stream().anyMatch(m -> m.field().equals("scheduleTime")));
    }

    // ── Day of week validation ──────────────────────────────────────

    @Test
    void weeklyWithoutDayIsWarning() {
        NewReportDefinition def = makeDef("test", "desc", "weekly", "last-7d", "prompt {{repositories}}");
        def.setScheduleTime("08:00");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.warnings().stream().anyMatch(m -> m.field().equals("scheduleDayOfWeek")));
    }

    @Test
    void weeklyWithInvalidDayIsError() {
        NewReportDefinition def = makeDef("test", "desc", "weekly", "last-7d", "prompt {{repositories}}");
        def.setScheduleTime("08:00");
        def.setScheduleDayOfWeek("funday");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("scheduleDayOfWeek") && m.message().contains("funday")));
    }

    @Test
    void weeklyWithValidDayAccepted() {
        NewReportDefinition def = makeDef("test", "desc", "weekly", "last-7d", "prompt {{repositories}}");
        def.setScheduleTime("08:00");
        def.setScheduleDayOfWeek("friday");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.messages().stream().anyMatch(m -> m.field().equals("scheduleDayOfWeek")));
    }

    // ── Time window validation ──────────────────────────────────────

    @Test
    void missingTimeWindowIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", null, "prompt {{repositories}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("timeWindow")));
    }

    @Test
    void invalidTimeWindowIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-year", "prompt {{repositories}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("timeWindow") && m.message().contains("last-year")));
    }

    // ── Prompt template validation ──────────────────────────────────

    @Test
    void missingPromptTemplateIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", null);

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("promptTemplate")));
    }

    @Test
    void blankPromptTemplateIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "  ");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
    }

    @Test
    void promptWithContentIsValid() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h",
                "Generate a report about recent activity.");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("promptTemplate")));
    }

    @Test
    void promptWithRecognizedPlaceholdersIsValid() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h",
                "Report for {{repositories}} from {{timeRangeStart}} to {{timeRangeEnd}} window {{timeWindow}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("promptTemplate")));
    }

    @Test
    void promptWithUnrecognizedPlaceholderIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h",
                "Report for {{foo}} and {{repositories}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("promptTemplate") && m.message().contains("{{foo}}")));
    }

    @Test
    void promptWithMultipleUnrecognizedPlaceholders() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h",
                "Report for {{foo}} and {{bar}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertEquals(2, result.errors().stream()
                .filter(m -> m.field().equals("promptTemplate") && m.message().contains("Unrecognized"))
                .count());
    }

    // ── Title template validation ─────────────────────────────────────

    @Test
    void nullTitleTemplateNoMessages() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt {{repositories}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.messages().stream().anyMatch(m -> m.field().equals("titleTemplate")));
    }

    @Test
    void blankTitleTemplateNoMessages() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt {{repositories}}");
        def.setTitleTemplate("   ");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.messages().stream().anyMatch(m -> m.field().equals("titleTemplate")));
    }

    @Test
    void titleTemplateWithRecognizedPlaceholdersIsValid() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt {{repositories}}");
        def.setTitleTemplate("{{name}} — {{date}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("titleTemplate")));
    }

    @Test
    void titleTemplateWithAllPlaceholdersIsValid() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt {{repositories}}");
        def.setTitleTemplate("{{name}} {{date}} {{time}} {{datetime}} {{timeWindow}} {{schedule}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("titleTemplate")));
    }

    @Test
    void titleTemplateWithUnrecognizedPlaceholderIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt {{repositories}}");
        def.setTitleTemplate("{{name}} — {{foo}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("titleTemplate") && m.message().contains("{{foo}}")));
    }

    @Test
    void titleTemplateWithPlainTextIsValid() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt {{repositories}}");
        def.setTitleTemplate("My Custom Report Title");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().equals("titleTemplate")));
    }

    // ── Timeout validation ──────────────────────────────────────────

    @Test
    void zeroTimeoutIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt {{repositories}}");
        def.setTimeoutSeconds(0);

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("timeoutSeconds")));
    }

    @Test
    void negativeTimeoutIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt {{repositories}}");
        def.setTimeoutSeconds(-10);

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
    }

    @Test
    void veryLowTimeoutIsWarning() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt {{repositories}}");
        def.setTimeoutSeconds(10);

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.hasErrors());
        assertTrue(result.warnings().stream().anyMatch(
                m -> m.field().equals("timeoutSeconds") && m.message().contains("very low")));
    }

    @Test
    void veryHighTimeoutIsWarning() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt {{repositories}}");
        def.setTimeoutSeconds(7200);

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.hasErrors());
        assertTrue(result.warnings().stream().anyMatch(
                m -> m.field().equals("timeoutSeconds") && m.message().contains("over one hour")));
    }

    @Test
    void normalTimeoutNoMessages() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt {{repositories}}");
        def.setTimeoutSeconds(300);

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.messages().stream().anyMatch(m -> m.field().equals("timeoutSeconds")));
    }

    @Test
    void nullTimeoutNoMessages() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt {{repositories}}");

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.messages().stream().anyMatch(m -> m.field().equals("timeoutSeconds")));
    }

    // ── Labels validation ───────────────────────────────────────────

    @Test
    void blankLabelIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt {{repositories}}");
        def.setInitialLabels(List.of("good", "  "));

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(m -> m.field().contains("initialLabels")));
    }

    @Test
    void duplicateLabelsIsWarning() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt {{repositories}}");
        def.setInitialLabels(List.of("weekly", "Weekly"));

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.warnings().stream().anyMatch(
                m -> m.message().contains("Duplicate label")));
    }

    // ── Allowed tools validation ──────────────────────────────────

    @Test
    void toolsetRefToMissingToolsetIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        def.setAllowedTools(List.of("@My Toolset"));

        ReportDefinitionValidator.KnownNames known = new ReportDefinitionValidator.KnownNames(
                null, null, Set.of("Other Toolset"), null);
        ValidationResult result = ReportDefinitionValidator.validate(def, known);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.message().contains("My Toolset") && m.message().contains("no toolset")));
    }

    @Test
    void toolsetRefToExistingToolsetIsValid() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        def.setAllowedTools(List.of("@Report Tools"));

        ReportDefinitionValidator.KnownNames known = new ReportDefinitionValidator.KnownNames(
                null, null, Set.of("Report Tools"), null);
        ValidationResult result = ReportDefinitionValidator.validate(def, known);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().contains("allowedTools")));
    }

    @Test
    void customToolRefToMissingToolIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        def.setAllowedTools(List.of("mcp__axiom-tools__nonexistent"));

        ReportDefinitionValidator.KnownNames known = new ReportDefinitionValidator.KnownNames(
                null, Set.of("list_github_prs"), null, null);
        ValidationResult result = ReportDefinitionValidator.validate(def, known);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.message().contains("nonexistent") && m.message().contains("no tool")));
    }

    @Test
    void customToolRefToExistingToolIsValid() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        def.setAllowedTools(List.of("mcp__axiom-tools__list_github_prs"));

        ReportDefinitionValidator.KnownNames known = new ReportDefinitionValidator.KnownNames(
                null, Set.of("list_github_prs"), null, null);
        ValidationResult result = ReportDefinitionValidator.validate(def, known);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().contains("allowedTools")));
    }

    @Test
    void sdkToolRefToInvalidSdkToolIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        def.setAllowedTools(List.of("mcp__axiom-sdk__axiom_fake_tool"));

        ReportDefinitionValidator.KnownNames known = new ReportDefinitionValidator.KnownNames(
                null, null, null, Set.of("mcp__axiom-sdk__axiom_list_projects"));
        ValidationResult result = ReportDefinitionValidator.validate(def, known);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.message().contains("axiom_fake_tool") && m.message().contains("not a recognized")));
    }

    @Test
    void sdkToolRefToValidSdkToolIsValid() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        def.setAllowedTools(List.of("mcp__axiom-sdk__axiom_list_projects"));

        ReportDefinitionValidator.KnownNames known = new ReportDefinitionValidator.KnownNames(
                null, null, null, Set.of("mcp__axiom-sdk__axiom_list_projects"));
        ValidationResult result = ReportDefinitionValidator.validate(def, known);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().contains("allowedTools")));
    }

    @Test
    void externalMcpToolIsValidNoCheck() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        def.setAllowedTools(List.of("mcp__my-server__some_tool"));

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().contains("allowedTools")));
    }

    @Test
    void builtInToolIsValid() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        def.setAllowedTools(List.of("Read", "Bash(gh pr *)"));

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().contains("allowedTools")));
    }

    @Test
    void blankToolEntryIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        def.setAllowedTools(List.of("Read", "  "));

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().contains("allowedTools[1]") && m.message().contains("blank")));
    }

    @Test
    void nullKnownNamesSkipsExistenceChecks() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        def.setAllowedTools(List.of("@Anything", "mcp__axiom-tools__anything", "mcp__axiom-sdk__axiom_anything"));

        ValidationResult result = ReportDefinitionValidator.validate(def, null);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().contains("allowedTools")));
    }

    // ── Environment / secret validation ────────────────────────────

    @Test
    void secretRefToMissingSecretIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("MY_TOKEN", "${secret:FOO}");
        def.setEnvironment(env);

        ValidationResult result = ReportDefinitionValidator.validate(def,
                new ReportDefinitionValidator.KnownNames(Set.of("BAR"), null, null, null));

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("environment.MY_TOKEN") && m.message().contains("FOO")));
    }

    @Test
    void secretRefToExistingSecretIsValid() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("MY_TOKEN", "${secret:GH_TOKEN}");
        def.setEnvironment(env);

        ValidationResult result = ReportDefinitionValidator.validate(def,
                new ReportDefinitionValidator.KnownNames(Set.of("GH_TOKEN"), null, null, null));

        assertFalse(result.errors().stream().anyMatch(m -> m.field().contains("environment")));
    }

    @Test
    void secretRefWithNullKnownSecretsSkipsCheck() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("MY_TOKEN", "${secret:ANYTHING}");
        def.setEnvironment(env);

        ValidationResult result = ReportDefinitionValidator.validate(def, null);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().contains("environment")));
    }

    @Test
    void invalidEnvKeyIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("my-token", "value");
        def.setEnvironment(env);

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("environment.my-token") && m.message().contains("not valid")));
    }

    @Test
    void validEnvKeyAccepted() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("GH_TOKEN", "value");
        def.setEnvironment(env);

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertFalse(result.errors().stream().anyMatch(m -> m.field().contains("environment")));
    }

    @Test
    void malformedSecretRefEmptyNameIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("MY_TOKEN", "${secret:}");
        def.setEnvironment(env);

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("environment.MY_TOKEN") && m.message().contains("Malformed")));
    }

    @Test
    void malformedSecretRefMissingCloseBraceIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("MY_TOKEN", "${secret:FOO");
        def.setEnvironment(env);

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("environment.MY_TOKEN") && m.message().contains("Malformed")));
    }

    @Test
    void malformedSecretRefNoColonIsError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("MY_TOKEN", "${secret}");
        def.setEnvironment(env);

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("environment.MY_TOKEN") && m.message().contains("Malformed")));
    }

    @Test
    void wellFormedSecretRefNoMalformedError() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("MY_TOKEN", "${secret:GH_TOKEN}");
        def.setEnvironment(env);

        ValidationResult result = ReportDefinitionValidator.validate(def,
                new ReportDefinitionValidator.KnownNames(Set.of("GH_TOKEN"), null, null, null));

        assertFalse(result.errors().stream().anyMatch(
                m -> m.message().contains("Malformed")));
    }

    @Test
    void envWithNoSecretRefsIsValid() {
        NewReportDefinition def = makeDef("test", "desc", "daily", "last-24h", "prompt");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("PLAIN_VAR", "some-value");
        def.setEnvironment(env);

        ValidationResult result = ReportDefinitionValidator.validate(def,
                new ReportDefinitionValidator.KnownNames(Set.of(), null, null, null));

        assertFalse(result.errors().stream().anyMatch(m -> m.field().contains("environment")));
    }

    // ── Result helpers ──────────────────────────────────────────────

    @Test
    void errorsAndWarningsFilterCorrectly() {
        NewReportDefinition def = makeDef(null, null, null, null, null);

        ValidationResult result = ReportDefinitionValidator.validate(def);

        assertTrue(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.errors().stream().allMatch(m -> m.severity() == Severity.ERROR));
        assertTrue(result.warnings().stream().allMatch(m -> m.severity() == Severity.WARNING));
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static NewReportDefinition makeDef(String name, String description,
                                                String schedule, String timeWindow,
                                                String promptTemplate) {
        NewReportDefinition def = new NewReportDefinition();
        def.setName(name);
        def.setDescription(description);
        def.setSchedule(schedule);
        def.setTimeWindow(timeWindow);
        def.setPromptTemplate(promptTemplate);
        return def;
    }
}
