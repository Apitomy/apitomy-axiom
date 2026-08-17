package io.apitomy.axiom.core;

import java.util.Set;

/**
 * Canonical set of all SDK tool names exposed by the Axiom MCP SDK server.
 * This must stay in sync with the SDK_TOOLS array in sdk-server.js.
 */
public final class SdkToolNames {

    private static final String PREFIX = "mcp__axiom-sdk__";

    /** All SDK tool names, fully qualified with the MCP server prefix. */
    public static final Set<String> ALL = Set.of(
            PREFIX + "axiom_fire_event",
            PREFIX + "axiom_list_projects",
            PREFIX + "axiom_get_project",
            PREFIX + "axiom_create_task",
            PREFIX + "axiom_get_task_status",
            PREFIX + "axiom_add_thread_entry",
            PREFIX + "axiom_close_project",
            PREFIX + "axiom_reopen_project",
            PREFIX + "axiom_add_project_label",
            PREFIX + "axiom_remove_project_label",
            PREFIX + "axiom_add_report_label",
            PREFIX + "axiom_remove_report_label",
            PREFIX + "axiom_list_tools",
            PREFIX + "axiom_list_report_definitions",
            PREFIX + "axiom_list_reports",
            PREFIX + "axiom_get_project_thread",
            PREFIX + "axiom_list_action_types",
            PREFIX + "axiom_list_actors",
            PREFIX + "axiom_update_project",
            PREFIX + "axiom_list_events",
            PREFIX + "axiom_respond_to_task",
            PREFIX + "axiom_get_activity_log",
            PREFIX + "axiom_update_project_body",
            PREFIX + "axiom_create_project"
    );

    /** All SDK tool names as a comma-separated string. */
    public static final String ALL_CSV = String.join(",", ALL);

    private SdkToolNames() {
    }
}
