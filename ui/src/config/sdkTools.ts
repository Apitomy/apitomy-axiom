const PREFIX = "mcp__axiom-sdk__";

interface SdkToolEntry {
    value: string;
    label: string;
    description: string;
}

/**
 * Canonical list of all SDK tools exposed by the Axiom MCP SDK server.
 * Must stay in sync with the SDK_TOOLS array in sdk-server.js.
 */
export const SDK_TOOLS: SdkToolEntry[] = [
    { value: PREFIX + "axiom_fire_event", label: "axiom_fire_event", description: "Fire a new event into Axiom for processing by the Manager" },
    { value: PREFIX + "axiom_list_projects", label: "axiom_list_projects", description: "List existing Axiom projects with optional filtering" },
    { value: PREFIX + "axiom_get_project", label: "axiom_get_project", description: "Get details of a specific Axiom project" },
    { value: PREFIX + "axiom_create_task", label: "axiom_create_task", description: "Create a new task on an Axiom project" },
    { value: PREFIX + "axiom_get_task_status", label: "axiom_get_task_status", description: "Get the status and details of a specific task" },
    { value: PREFIX + "axiom_add_thread_entry", label: "axiom_add_thread_entry", description: "Post an update or message to a project's conversation thread" },
    { value: PREFIX + "axiom_close_project", label: "axiom_close_project", description: "Close (complete) an Axiom project" },
    { value: PREFIX + "axiom_reopen_project", label: "axiom_reopen_project", description: "Reopen a previously closed Axiom project" },
    { value: PREFIX + "axiom_add_project_label", label: "axiom_add_project_label", description: "Add a label to an Axiom project" },
    { value: PREFIX + "axiom_remove_project_label", label: "axiom_remove_project_label", description: "Remove a label from an Axiom project" },
    { value: PREFIX + "axiom_add_report_label", label: "axiom_add_report_label", description: "Add a label to a generated Axiom report" },
    { value: PREFIX + "axiom_remove_report_label", label: "axiom_remove_report_label", description: "Remove a label from a generated Axiom report" },
    { value: PREFIX + "axiom_list_tools", label: "axiom_list_tools", description: "List all custom tool definitions configured in Axiom" },
    { value: PREFIX + "axiom_list_report_definitions", label: "axiom_list_report_definitions", description: "List all report definitions configured in Axiom" },
    { value: PREFIX + "axiom_list_reports", label: "axiom_list_reports", description: "List generated reports with optional filtering" },
    { value: PREFIX + "axiom_get_project_thread", label: "axiom_get_project_thread", description: "Read the conversation thread for a project" },
    { value: PREFIX + "axiom_list_action_types", label: "axiom_list_action_types", description: "List all action types configured in Axiom" },
    { value: PREFIX + "axiom_list_agents", label: "axiom_list_agents", description: "List all agents configured in Axiom" },
    { value: PREFIX + "axiom_update_project", label: "axiom_update_project", description: "Update an Axiom project's metadata" },
    { value: PREFIX + "axiom_list_events", label: "axiom_list_events", description: "List events related to an Axiom project" },
    { value: PREFIX + "axiom_respond_to_task", label: "axiom_respond_to_task", description: "Submit a response to a task awaiting human input" },
    { value: PREFIX + "axiom_get_activity_log", label: "axiom_get_activity_log", description: "Get the global activity log" },
    { value: PREFIX + "axiom_update_project_body", label: "axiom_update_project_body", description: "Update an Axiom project's body with markdown content" },
];

/** All SDK tool qualified names (value strings). */
export const SDK_TOOL_VALUES: string[] = SDK_TOOLS.map(t => t.value);
