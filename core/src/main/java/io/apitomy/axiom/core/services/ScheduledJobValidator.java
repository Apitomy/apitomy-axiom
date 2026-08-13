package io.apitomy.axiom.core.services;

import io.apitomy.axiom.api.beans.NewScheduledJob;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates scheduled job definitions and returns a list of errors and warnings.
 *
 * <p>This validator is standalone (no CDI dependencies) so it can be used from
 * REST endpoints, the AI Assistant, or unit tests.</p>
 */
public final class ScheduledJobValidator {

    private static final Set<String> VALID_SCHEDULES = Set.of(
            "none", "hourly", "daily", "weekly", "monthly");
    private static final Set<String> VALID_DAYS = Set.of(
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}\\}");
    private static final Pattern MALFORMED_PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{\\s+|\\s+\\}\\}|\\{\\{[^}]*$");
    private static final Set<String> RECOGNIZED_PROMPT_PLACEHOLDERS = Set.of(
            "jobName", "apiBaseUrl");
    private static final Set<String> RECOGNIZED_SCRIPT_PLACEHOLDERS = Set.of(
            "jobName", "jobId", "runId", "apiBaseUrl");
    private static final Pattern SECRET_REF_PATTERN =
            Pattern.compile("\\$\\{secret:([^}]+)\\}");
    private static final Pattern MALFORMED_SECRET_REF_PATTERN =
            Pattern.compile("\\$\\{secret:\\}|\\$\\{secret:[^}]*$|\\$\\{secret\\}");
    private static final Pattern VALID_ENV_KEY_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /**
     * Known configuration state for cross-referencing. Any field may be null
     * to skip that check.
     *
     * @param secretNames  secret names configured in Axiom
     * @param toolNames    custom script tool names (without prefix)
     * @param toolsetNames toolset names (without @ prefix)
     * @param sdkToolNames full SDK tool identifiers
     */
    public record KnownNames(
            Set<String> secretNames,
            Set<String> toolNames,
            Set<String> toolsetNames,
            Set<String> sdkToolNames) {
    }

    private ScheduledJobValidator() {
    }

    /**
     * Validates a scheduled job definition without cross-reference checks.
     *
     * @param def the scheduled job definition to validate
     * @return the validation result
     */
    public static ValidationResult validate(NewScheduledJob def) {
        return validate(def, null);
    }

    /**
     * Validates a scheduled job definition with optional cross-reference checking.
     *
     * @param def   the scheduled job definition to validate
     * @param known known configuration names, or null to skip existence checks
     * @return the validation result
     */
    public static ValidationResult validate(NewScheduledJob def, KnownNames known) {
        List<ValidationMessage> messages = new ArrayList<>();

        validateName(def, messages);
        validateDescription(def, messages);
        validateSchedule(def, messages);
        validateExecutionMode(def, messages);
        validatePromptTemplate(def, messages);
        validateScriptTemplate(def, messages);
        validateLabels(def, messages);
        validateAllowedTools(def, known, messages);
        validateEnvironment(def, known, messages);

        return new ValidationResult(messages);
    }

    private static void validateName(NewScheduledJob def, List<ValidationMessage> messages) {
        if (def.getName() == null || def.getName().isBlank()) {
            messages.add(error("name", "Scheduled job name is required."));
        }
    }

    private static void validateDescription(NewScheduledJob def, List<ValidationMessage> messages) {
        if (def.getDescription() == null || def.getDescription().isBlank()) {
            messages.add(warning("description",
                    "Scheduled job has no description. A description helps "
                            + "identify the purpose of this job."));
        }
    }

    private static void validateSchedule(NewScheduledJob def, List<ValidationMessage> messages) {
        String schedule = def.getSchedule();
        if (schedule == null || schedule.isBlank()) {
            messages.add(error("schedule", "Schedule is required."));
            return;
        }

        if (!VALID_SCHEDULES.contains(schedule)) {
            messages.add(error("schedule",
                    "Invalid schedule '" + schedule + "'. Must be one of "
                            + VALID_SCHEDULES + "."));
            return;
        }

        if ("weekly".equals(schedule)) {
            String day = def.getScheduleDayOfWeek();
            if (day != null && !day.isBlank()) {
                if (!VALID_DAYS.contains(day.toLowerCase())) {
                    messages.add(error("scheduleDayOfWeek",
                            "Invalid day of week '" + day + "'. Must be one of: "
                                    + VALID_DAYS + "."));
                }
            } else {
                messages.add(warning("scheduleDayOfWeek",
                        "Schedule is 'weekly' but no day of week is specified. "
                                + "The job will run on the same day each week."));
            }
        }

        if (!"none".equals(schedule)) {
            String time = def.getScheduleTime();
            if (time == null || time.isBlank()) {
                messages.add(warning("scheduleTime",
                        "No schedule time specified. Defaults to 08:00."));
            } else {
                try {
                    LocalTime.parse(time);
                } catch (DateTimeParseException e) {
                    messages.add(error("scheduleTime",
                            "Invalid schedule time '" + time
                                    + "'. Use HH:MM format (e.g. '08:00', '14:30')."));
                }
            }
        }
    }

    private static void validateExecutionMode(NewScheduledJob def, List<ValidationMessage> messages) {
        String mode = def.getExecutionMode();
        if (mode == null || mode.isBlank()) {
            messages.add(error("executionMode", "Execution mode is required."));
        } else if (!"actor".equals(mode) && !"script".equals(mode)) {
            messages.add(error("executionMode",
                    "Invalid execution mode '" + mode + "'. Must be 'actor' or 'script'."));
        }
    }

    private static void validatePromptTemplate(NewScheduledJob def, List<ValidationMessage> messages) {
        if (!"actor".equals(def.getExecutionMode())) {
            return;
        }
        String prompt = def.getPromptTemplate();
        if (prompt == null || prompt.isBlank()) {
            boolean enabled = def.getEnabled() != null && def.getEnabled();
            if (enabled) {
                messages.add(error("promptTemplate",
                        "Prompt template is required for actor-mode scheduled jobs."));
            } else {
                messages.add(warning("promptTemplate",
                        "Prompt template is recommended for actor-mode scheduled jobs."));
            }
            return;
        }

        checkMalformedPlaceholders(prompt, "promptTemplate", messages);

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(prompt);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!RECOGNIZED_PROMPT_PLACEHOLDERS.contains(name)) {
                messages.add(error("promptTemplate",
                        "Unrecognized placeholder '{{" + name + "}}'. Supported: "
                                + RECOGNIZED_PROMPT_PLACEHOLDERS + "."));
            }
        }
    }

    private static void validateScriptTemplate(NewScheduledJob def, List<ValidationMessage> messages) {
        if (!"script".equals(def.getExecutionMode())) {
            return;
        }
        String script = def.getScriptTemplate();
        if (script == null || script.isBlank()) {
            boolean enabled = def.getEnabled() != null && def.getEnabled();
            if (enabled) {
                messages.add(error("scriptTemplate",
                        "Script template is required for script-mode scheduled jobs."));
            } else {
                messages.add(warning("scriptTemplate",
                        "Script template is recommended for script-mode scheduled jobs."));
            }
            return;
        }

        checkMalformedPlaceholders(script, "scriptTemplate", messages);

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(script);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!RECOGNIZED_SCRIPT_PLACEHOLDERS.contains(name)) {
                messages.add(error("scriptTemplate",
                        "Unrecognized placeholder '{{" + name + "}}'. Supported: "
                                + RECOGNIZED_SCRIPT_PLACEHOLDERS + "."));
            }
        }
    }

    private static void validateLabels(NewScheduledJob def, List<ValidationMessage> messages) {
        List<String> labels = def.getLabels();
        if (labels == null || labels.isEmpty()) {
            return;
        }

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            if (label == null || label.isBlank()) {
                messages.add(error("labels[" + i + "]",
                        "Label at index " + i + " is blank."));
            } else if (!seen.add(label.toLowerCase())) {
                messages.add(warning("labels",
                        "Duplicate label: '" + label + "'."));
            }
        }
    }

    private static void validateAllowedTools(NewScheduledJob def, KnownNames known,
                                              List<ValidationMessage> messages) {
        if (!"actor".equals(def.getExecutionMode())) {
            return;
        }
        List<String> tools = def.getAllowedTools();
        if (tools == null || tools.isEmpty()) {
            return;
        }

        for (int i = 0; i < tools.size(); i++) {
            String tool = tools.get(i);
            if (tool == null || tool.isBlank()) {
                messages.add(error("allowedTools[" + i + "]",
                        "Allowed tool entry at index " + i + " is blank."));
                continue;
            }

            if (tool.startsWith("@")) {
                String toolsetName = tool.substring(1);
                if (known != null && known.toolsetNames() != null
                        && !known.toolsetNames().contains(toolsetName)) {
                    messages.add(error("allowedTools[" + i + "]",
                            "References toolset '@" + toolsetName
                                    + "' but no toolset with that name exists."));
                }
            } else if (tool.startsWith("mcp__axiom-tools__")) {
                String toolName = tool.substring("mcp__axiom-tools__".length());
                if (known != null && known.toolNames() != null
                        && !known.toolNames().contains(toolName)) {
                    messages.add(error("allowedTools[" + i + "]",
                            "References custom tool '" + toolName
                                    + "' but no tool with that name exists."));
                }
            } else if (tool.startsWith("mcp__axiom-sdk__")) {
                if (known != null && known.sdkToolNames() != null
                        && !known.sdkToolNames().contains(tool)) {
                    messages.add(error("allowedTools[" + i + "]",
                            "References SDK tool '" + tool
                                    + "' which is not a recognized Axiom SDK tool."));
                }
            } else if (tool.startsWith("mcp__")) {
                // External MCP server tool — valid pattern
            } else if (!tool.matches("^[A-Za-z].*")) {
                messages.add(warning("allowedTools[" + i + "]",
                        "Tool reference '" + tool
                                + "' does not match any known pattern."));
            }
        }
    }

    private static void validateEnvironment(NewScheduledJob def, KnownNames known,
                                             List<ValidationMessage> messages) {
        var env = def.getEnvironment();
        if (env == null) {
            return;
        }
        Map<String, Object> vars = env.getAdditionalProperties();
        if (vars == null || vars.isEmpty()) {
            return;
        }

        Set<String> knownSecretNames = known != null ? known.secretNames() : null;

        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                messages.add(error("environment", "Environment variable name is blank."));
                continue;
            }
            if (!VALID_ENV_KEY_PATTERN.matcher(key).matches()) {
                messages.add(error("environment." + key,
                        "Environment variable name '" + key
                                + "' is not valid. Use letters, digits, and underscores."));
            }

            String value = entry.getValue() != null ? entry.getValue().toString() : "";

            if (MALFORMED_SECRET_REF_PATTERN.matcher(value).find()) {
                messages.add(error("environment." + key,
                        "Malformed secret reference in value. "
                                + "Use the format ${secret:SECRET_NAME}."));
            }

            Matcher matcher = SECRET_REF_PATTERN.matcher(value);
            while (matcher.find()) {
                String secretName = matcher.group(1);
                if (knownSecretNames != null && !knownSecretNames.contains(secretName)) {
                    messages.add(error("environment." + key,
                            "References secret '${secret:" + secretName
                                    + "}' but no secret named '" + secretName
                                    + "' exists in Axiom."));
                }
            }
        }
    }

    private static void checkMalformedPlaceholders(String text, String field,
                                                    List<ValidationMessage> messages) {
        Matcher malformed = MALFORMED_PLACEHOLDER_PATTERN.matcher(text);
        if (malformed.find()) {
            messages.add(error(field,
                    "Contains a malformed placeholder near: '"
                            + malformed.group().trim() + "'. "
                            + "Use {{name}} with no spaces."));
        }
    }

    private static ValidationMessage error(String field, String message) {
        return new ValidationMessage(Severity.ERROR, field, message);
    }

    private static ValidationMessage warning(String field, String message) {
        return new ValidationMessage(Severity.WARNING, field, message);
    }

    /** Severity level for a validation message. */
    public enum Severity {
        ERROR,
        WARNING
    }

    /**
     * A single validation finding.
     *
     * @param severity whether this is an error or warning
     * @param field    the field or path that the message relates to
     * @param message  human-readable description of the issue
     */
    public record ValidationMessage(Severity severity, String field, String message) {
    }

    /**
     * The result of validating a scheduled job definition.
     *
     * @param messages all validation findings (errors and warnings)
     */
    public record ValidationResult(List<ValidationMessage> messages) {

        /** Returns true if there are any errors. */
        public boolean hasErrors() {
            return messages.stream().anyMatch(m -> m.severity() == Severity.ERROR);
        }

        /** Returns true if there are any warnings. */
        public boolean hasWarnings() {
            return messages.stream().anyMatch(m -> m.severity() == Severity.WARNING);
        }

        /** Returns only the error messages. */
        public List<ValidationMessage> errors() {
            return messages.stream()
                    .filter(m -> m.severity() == Severity.ERROR)
                    .collect(Collectors.toList());
        }

        /** Returns only the warning messages. */
        public List<ValidationMessage> warnings() {
            return messages.stream()
                    .filter(m -> m.severity() == Severity.WARNING)
                    .collect(Collectors.toList());
        }
    }
}
