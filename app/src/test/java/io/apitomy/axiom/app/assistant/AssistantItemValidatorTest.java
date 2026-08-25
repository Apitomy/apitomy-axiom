package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AssistantItemValidator}. Uses a temporary directory
 * for JSON files and directly injects the ObjectMapper — no Quarkus container
 * required.
 */
class AssistantItemValidatorTest {

    private AssistantItemValidator validator;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        validator = new AssistantItemValidator();
        Field omField = AssistantItemValidator.class.getDeclaredField("objectMapper");
        omField.setAccessible(true);
        omField.set(validator, new ObjectMapper());
    }

    // ── Tool validation ─────────────────────────────────────────────

    @Test
    void validToolPassesValidation() throws IOException {
        Path file = writeJson(tempDir, "tools/my-tool.json", """
                {
                  "name": "my-tool",
                  "description": "A test tool",
                  "scriptTemplate": "echo hello",
                  "parameters": [
                    {"name": "input", "type": "string", "description": "The input", "required": true}
                  ],
                  "labels": ["test"]
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "tools");
        assertTrue(vr.isValid(), "Valid tool should have no errors: " + vr.errors());
    }

    @Test
    void toolMissingNameFails() throws IOException {
        Path file = writeJson(tempDir, "tools/no-name.json", """
                {
                  "description": "A tool without a name",
                  "scriptTemplate": "echo hi"
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "tools");
        assertFalse(vr.isValid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("name")));
    }

    @Test
    void toolMissingDescriptionFails() throws IOException {
        Path file = writeJson(tempDir, "tools/no-desc.json", """
                {
                  "name": "no-desc",
                  "scriptTemplate": "echo hi"
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "tools");
        assertTrue(vr.isValid(), "Missing description is a warning, not an error");
        assertTrue(vr.warnings().stream().anyMatch(w -> w.contains("description")));
    }

    @Test
    void toolMissingScriptTemplateFails() throws IOException {
        Path file = writeJson(tempDir, "tools/no-script.json", """
                {
                  "name": "no-script",
                  "description": "A tool without script"
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "tools");
        assertFalse(vr.isValid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("scriptTemplate")));
    }

    @Test
    void toolInvalidParametersTypeFails() throws IOException {
        Path file = writeJson(tempDir, "tools/bad-params.json", """
                {
                  "name": "bad-params",
                  "description": "Tool with non-array params",
                  "scriptTemplate": "echo hi",
                  "parameters": "not-an-array"
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "tools");
        assertFalse(vr.isValid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("parameters")));
    }

    @Test
    void toolParameterMissingNameFails() throws IOException {
        Path file = writeJson(tempDir, "tools/param-no-name.json", """
                {
                  "name": "param-no-name",
                  "description": "Tool with unnamed param",
                  "scriptTemplate": "echo hi",
                  "parameters": [{"type": "string"}]
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "tools");
        assertFalse(vr.isValid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("index 0") && e.contains("name")));
    }

    @Test
    void toolInvalidLabelsTypeFails() throws IOException {
        Path file = writeJson(tempDir, "tools/bad-labels.json", """
                {
                  "name": "bad-labels",
                  "description": "Tool with non-array labels",
                  "scriptTemplate": "echo hi",
                  "labels": "not-an-array"
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "tools");
        assertFalse(vr.isValid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("labels")));
    }

    @Test
    void toolWithNullParametersIsValid() throws IOException {
        Path file = writeJson(tempDir, "tools/null-params.json", """
                {
                  "name": "null-params",
                  "description": "Tool with null params",
                  "scriptTemplate": "echo hi",
                  "parameters": null
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "tools");
        assertTrue(vr.isValid(), "Null parameters should be allowed: " + vr.errors());
    }

    // ── Action type validation ──────────────────────────────────────

    @Test
    void validActorActionTypePassesValidation() throws IOException {
        Path file = writeJson(tempDir, "action-types/my-action.json", """
                {
                  "name": "my-action",
                  "description": "A test action",
                  "executionMode": "agent",
                  "userTriggerable": true,
                  "managerTriggerable": false,
                  "promptTemplate": "Do the thing: {{input}}"
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "action-types");
        assertTrue(vr.isValid(), "Valid action type should have no errors: " + vr.errors());
    }

    @Test
    void validScriptActionTypePassesValidation() throws IOException {
        Path file = writeJson(tempDir, "action-types/script-action.json", """
                {
                  "name": "script-action",
                  "description": "A script action",
                  "executionMode": "script",
                  "scriptTemplate": "#!/bin/bash\\necho done"
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "action-types");
        assertTrue(vr.isValid(), "Valid script action should have no errors: " + vr.errors());
    }

    @Test
    void actionTypeMissingExecutionModeFails() throws IOException {
        Path file = writeJson(tempDir, "action-types/no-mode.json", """
                {
                  "name": "no-mode",
                  "description": "Action without execution mode"
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "action-types");
        assertFalse(vr.isValid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("executionMode")));
    }

    @Test
    void actionTypeInvalidExecutionModeFails() throws IOException {
        Path file = writeJson(tempDir, "action-types/bad-mode.json", """
                {
                  "name": "bad-mode",
                  "description": "Action with invalid mode",
                  "executionMode": "invalid"
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "action-types");
        assertFalse(vr.isValid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("executionMode") && e.contains("invalid")));
    }

    @Test
    void actorActionTypeMissingPromptFails() throws IOException {
        Path file = writeJson(tempDir, "action-types/no-prompt.json", """
                {
                  "name": "no-prompt",
                  "description": "Agent action without prompt",
                  "executionMode": "agent"
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "action-types");
        assertFalse(vr.isValid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("promptTemplate")));
    }

    @Test
    void scriptActionTypeMissingScriptFails() throws IOException {
        Path file = writeJson(tempDir, "action-types/no-script.json", """
                {
                  "name": "no-script",
                  "description": "Script action without script",
                  "executionMode": "script"
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "action-types");
        assertFalse(vr.isValid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("scriptTemplate")));
    }

    // ── Report definition validation ────────────────────────────────

    @Test
    void validReportDefinitionPassesValidation() throws IOException {
        Path file = writeJson(tempDir, "report-definitions/weekly.json", """
                {
                  "name": "weekly-status",
                  "description": "Weekly status report",
                  "schedule": "weekly",
                  "scheduleTime": "08:00",
                  "scheduleDayOfWeek": "monday",
                  "timeWindow": "last-7d",
                  "promptTemplate": "Generate a report for {{repositories}}"
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "report-definitions");
        assertTrue(vr.isValid(), "Valid report should have no errors: " + vr.errors());
    }

    @Test
    void reportDefinitionMissingScheduleFails() throws IOException {
        Path file = writeJson(tempDir, "report-definitions/no-schedule.json", """
                {
                  "name": "no-schedule",
                  "description": "Report without schedule",
                  "promptTemplate": "Generate report"
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "report-definitions");
        assertFalse(vr.isValid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("schedule")));
    }

    @Test
    void reportDefinitionInvalidScheduleFails() throws IOException {
        Path file = writeJson(tempDir, "report-definitions/bad-schedule.json", """
                {
                  "name": "bad-schedule",
                  "description": "Report with invalid schedule",
                  "schedule": "biweekly",
                  "promptTemplate": "Generate report"
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "report-definitions");
        assertFalse(vr.isValid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("schedule") && e.contains("biweekly")));
    }

    @Test
    void reportDefinitionCronScheduleIsValid() throws IOException {
        Path file = writeJson(tempDir, "report-definitions/cron.json", """
                {
                  "name": "cron-report",
                  "description": "Report with cron schedule",
                  "schedule": "0 8 * * 1",
                  "timeWindow": "last-7d",
                  "promptTemplate": "Generate report"
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "report-definitions");
        assertTrue(vr.isValid(), "Cron schedule should be accepted: " + vr.errors());
    }

    @Test
    void reportDefinitionInvalidTimeWindowFails() throws IOException {
        Path file = writeJson(tempDir, "report-definitions/bad-tw.json", """
                {
                  "name": "bad-tw",
                  "description": "Report with bad time window",
                  "schedule": "daily",
                  "timeWindow": "last-year",
                  "promptTemplate": "Generate report"
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "report-definitions");
        assertFalse(vr.isValid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("timeWindow")));
    }

    @Test
    void reportDefinitionMissingPromptFails() throws IOException {
        Path file = writeJson(tempDir, "report-definitions/no-prompt.json", """
                {
                  "name": "no-prompt",
                  "description": "Report without prompt",
                  "schedule": "daily"
                }
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "report-definitions");
        assertFalse(vr.isValid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("promptTemplate")));
    }

    // ── General validation ──────────────────────────────────────────

    @Test
    void invalidJsonFails() throws IOException {
        Path file = tempDir.resolve("tools");
        Files.createDirectories(file);
        file = file.resolve("bad.json");
        Files.writeString(file, "this is not JSON");

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "tools");
        assertFalse(vr.isValid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("Invalid JSON")));
    }

    @Test
    void nonObjectRootFails() throws IOException {
        Path file = writeJson(tempDir, "tools/array-root.json", "[1, 2, 3]");

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "tools");
        assertFalse(vr.isValid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("Invalid JSON")));
    }

    @Test
    void nonExistentFileFails() {
        Path file = tempDir.resolve("tools/does-not-exist.json");

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "tools");
        assertFalse(vr.isValid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("does not exist")));
    }

    @Test
    void unknownItemTypeFails() throws IOException {
        Path file = writeJson(tempDir, "unknown/item.json", """
                {"name": "item", "description": "test"}
                """);

        AssistantItemValidator.ValidationResult vr = validator.validate(file, "unknown");
        assertFalse(vr.isValid());
        assertTrue(vr.errors().stream().anyMatch(e -> e.contains("Unknown item type")));
    }

    // ── detectItemType ──────────────────────────────────────────────

    @Test
    void detectItemTypeForTools() {
        Path file = tempDir.resolve("tools/my-tool.json");
        assertEquals("tools", validator.detectItemType(file, tempDir));
    }

    @Test
    void detectItemTypeForActionTypes() {
        Path file = tempDir.resolve("action-types/my-action.json");
        assertEquals("action-types", validator.detectItemType(file, tempDir));
    }

    @Test
    void detectItemTypeForReportDefinitions() {
        Path file = tempDir.resolve("report-definitions/my-report.json");
        assertEquals("report-definitions", validator.detectItemType(file, tempDir));
    }

    @Test
    void detectItemTypeReturnsNullForUnknown() {
        Path file = tempDir.resolve("other/something.json");
        assertNull(validator.detectItemType(file, tempDir));
    }

    // ── Helper ──────────────────────────────────────────────────────

    private Path writeJson(Path base, String relativePath, String content) throws IOException {
        Path file = base.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
