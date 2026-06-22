package io.apitomy.axiom.core.services;

import io.apitomy.axiom.api.beans.NewReportDefinition;

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
 * Validates report definitions and returns a list of errors and warnings.
 *
 * <p>This validator is standalone (no CDI dependencies) so it can be used from
 * REST endpoints, the AI Assistant, or unit tests.</p>
 */
public final class ReportDefinitionValidator {

    private static final Set<String> VALID_SCHEDULES = Set.of(
            "none", "hourly", "daily", "weekly", "monthly");
    private static final Set<String> VALID_TIME_WINDOWS = Set.of(
            "since-last-run", "last-24h", "last-7d", "last-30d");
    private static final Set<String> VALID_DAYS = Set.of(
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");
    private static final Pattern SECRET_REF_PATTERN =
            Pattern.compile("\\$\\{secret:([^}]+)\\}");
    private static final Pattern MALFORMED_SECRET_REF_PATTERN =
            Pattern.compile("\\$\\{secret:\\}|\\$\\{secret:[^}]*$|\\$\\{secret\\}");
    private static final Pattern VALID_ENV_KEY_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}\\}");
    private static final Set<String> RECOGNIZED_PLACEHOLDERS = Set.of(
            "repositories", "timeRangeStart", "timeRangeEnd", "timeWindow");
    private static final Set<String> RECOGNIZED_TITLE_PLACEHOLDERS = Set.of(
            "name", "date", "time", "datetime", "timeWindow", "schedule");

    private ReportDefinitionValidator() {
    }

    /**
     * Known configuration state passed to the validator for cross-referencing.
     * Any field may be null to skip that check.
     *
     * @param secretNames   secret names configured in Axiom
     * @param toolNames     custom script tool names (without prefix)
     * @param toolsetNames  toolset names (without @ prefix)
     * @param sdkToolNames  full SDK tool identifiers (e.g. mcp__axiom-sdk__axiom_list_projects)
     */
    public record KnownNames(
            Set<String> secretNames,
            Set<String> toolNames,
            Set<String> toolsetNames,
            Set<String> sdkToolNames) {
    }

    /**
     * Validates a report definition and returns all errors and warnings.
     *
     * @param def the report definition to validate
     * @return the validation result containing errors and warnings
     */
    public static ValidationResult validate(NewReportDefinition def) {
        return validate(def, null);
    }

    /**
     * Validates a report definition with optional cross-reference checking.
     *
     * @param def the report definition to validate
     * @param known known configuration names, or null to skip existence checks
     * @return the validation result containing errors and warnings
     */
    public static ValidationResult validate(NewReportDefinition def, KnownNames known) {
        List<ValidationMessage> messages = new ArrayList<>();

        validateName(def, messages);
        validateDescription(def, messages);
        validateSchedule(def, messages);
        validateTimeWindow(def, messages);
        validatePromptTemplate(def, messages);
        validateTitleTemplate(def, messages);
        validateTimeoutSeconds(def, messages);
        validateInitialLabels(def, messages);
        validateAllowedTools(def, known, messages);
        validateEnvironment(def, known, messages);

        return new ValidationResult(messages);
    }

    private static void validateName(NewReportDefinition def, List<ValidationMessage> messages) {
        if (def.getName() == null || def.getName().isBlank()) {
            messages.add(error("name", "Report definition name is required."));
        }
    }

    private static void validateDescription(NewReportDefinition def, List<ValidationMessage> messages) {
        if (def.getDescription() == null || def.getDescription().isBlank()) {
            messages.add(warning("description",
                    "Report definition has no description. A description helps "
                            + "identify the purpose of this report."));
        }
    }

    private static void validateSchedule(NewReportDefinition def, List<ValidationMessage> messages) {
        String schedule = def.getSchedule();
        if (schedule == null || schedule.isBlank()) {
            messages.add(error("schedule", "Schedule is required."));
            return;
        }

        boolean isPreset = VALID_SCHEDULES.contains(schedule);
        boolean isCron = schedule.contains(" ");

        if (!isPreset && !isCron) {
            messages.add(error("schedule",
                    "Invalid schedule '" + schedule + "'. Must be one of "
                            + VALID_SCHEDULES + " or a cron expression."));
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
                                + "The report will run on the same day each week."));
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

    private static void validateTimeWindow(NewReportDefinition def, List<ValidationMessage> messages) {
        String timeWindow = def.getTimeWindow();
        if (timeWindow == null || timeWindow.isBlank()) {
            messages.add(error("timeWindow", "Time window is required."));
            return;
        }
        if (!VALID_TIME_WINDOWS.contains(timeWindow)) {
            messages.add(error("timeWindow",
                    "Invalid time window '" + timeWindow + "'. Must be one of: "
                            + VALID_TIME_WINDOWS + "."));
        }
    }

    private static void validatePromptTemplate(NewReportDefinition def,
                                                List<ValidationMessage> messages) {
        String prompt = def.getPromptTemplate();
        if (prompt == null || prompt.isBlank()) {
            messages.add(error("promptTemplate", "Prompt template is required."));
            return;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(prompt);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!RECOGNIZED_PLACEHOLDERS.contains(name)) {
                messages.add(error("promptTemplate",
                        "Unrecognized placeholder '{{" + name + "}}'. "
                                + "Supported placeholders are: {{repositories}}, "
                                + "{{timeRangeStart}}, {{timeRangeEnd}}, {{timeWindow}}."));
            }
        }
    }

    private static void validateTitleTemplate(NewReportDefinition def,
                                               List<ValidationMessage> messages) {
        String template = def.getTitleTemplate();
        if (template == null || template.isBlank()) {
            return;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!RECOGNIZED_TITLE_PLACEHOLDERS.contains(name)) {
                messages.add(error("titleTemplate",
                        "Unrecognized placeholder '{{" + name + "}}'. "
                                + "Supported placeholders are: {{name}}, {{date}}, "
                                + "{{time}}, {{datetime}}, {{timeWindow}}, {{schedule}}."));
            }
        }
    }

    private static void validateTimeoutSeconds(NewReportDefinition def,
                                                List<ValidationMessage> messages) {
        Integer timeout = def.getTimeoutSeconds();
        if (timeout == null) {
            return;
        }
        if (timeout <= 0) {
            messages.add(error("timeoutSeconds",
                    "Timeout must be a positive number of seconds. Got: " + timeout + "."));
        } else if (timeout < 30) {
            messages.add(warning("timeoutSeconds",
                    "Timeout of " + timeout + "s is very low. Most reports need "
                            + "at least 30 seconds to complete."));
        } else if (timeout > 3600) {
            messages.add(warning("timeoutSeconds",
                    "Timeout of " + timeout + "s is over one hour. Consider whether "
                            + "this report truly needs that long."));
        }
    }

    private static void validateInitialLabels(NewReportDefinition def,
                                               List<ValidationMessage> messages) {
        List<String> labels = def.getInitialLabels();
        if (labels == null || labels.isEmpty()) {
            return;
        }

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            if (label == null || label.isBlank()) {
                messages.add(error("initialLabels[" + i + "]",
                        "Label at index " + i + " is blank."));
            } else if (!seen.add(label.toLowerCase())) {
                messages.add(warning("initialLabels",
                        "Duplicate label: '" + label + "'."));
            }
        }
    }

    private static void validateAllowedTools(NewReportDefinition def,
                                                KnownNames known,
                                                List<ValidationMessage> messages) {
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
                // Toolset reference
                String toolsetName = tool.substring(1);
                if (known != null && known.toolsetNames() != null
                        && !known.toolsetNames().contains(toolsetName)) {
                    messages.add(error("allowedTools[" + i + "]",
                            "References toolset '@" + toolsetName
                                    + "' but no toolset with that name exists."));
                }
            } else if (tool.startsWith("mcp__axiom-tools__")) {
                // Custom script tool
                String toolName = tool.substring("mcp__axiom-tools__".length());
                if (known != null && known.toolNames() != null
                        && !known.toolNames().contains(toolName)) {
                    messages.add(error("allowedTools[" + i + "]",
                            "References custom tool '" + toolName
                                    + "' but no tool with that name exists."));
                }
            } else if (tool.startsWith("mcp__axiom-sdk__")) {
                // SDK tool
                if (known != null && known.sdkToolNames() != null
                        && !known.sdkToolNames().contains(tool)) {
                    messages.add(error("allowedTools[" + i + "]",
                            "References SDK tool '" + tool
                                    + "' which is not a recognized Axiom SDK tool."));
                }
            } else if (tool.startsWith("mcp__")) {
                // External MCP server tool — valid pattern, no existence check
            } else if (!tool.matches("^[A-Za-z].*")) {
                // Doesn't start with a letter and isn't a known pattern
                messages.add(warning("allowedTools[" + i + "]",
                        "Tool reference '" + tool
                                + "' does not match any known pattern "
                                + "(mcp__*__*, @Toolset, or built-in tool name)."));
            }
        }
    }

    private static void validateEnvironment(NewReportDefinition def,
                                               KnownNames known,
                                               List<ValidationMessage> messages) {
        Set<String> knownSecretNames = known != null ? known.secretNames() : null;
        var env = def.getEnvironment();
        if (env == null) {
            return;
        }
        Map<String, Object> vars = env.getAdditionalProperties();
        if (vars == null || vars.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                messages.add(error("environment", "Environment variable name is blank."));
                continue;
            }
            if (!VALID_ENV_KEY_PATTERN.matcher(key).matches()) {
                messages.add(error("environment." + key,
                        "Environment variable name '" + key
                                + "' is not valid. Use letters, digits, and underscores"
                                + " (e.g. 'GH_TOKEN')."));
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
                    messages.add(error("environment." + entry.getKey(),
                            "References secret '${secret:" + secretName
                                    + "}' but no secret named '" + secretName
                                    + "' exists in Axiom."));
                }
            }
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
     * @param field the field or path that the message relates to
     * @param message human-readable description of the issue
     */
    public record ValidationMessage(Severity severity, String field, String message) {
    }

    /**
     * The result of validating a report definition.
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
