/**
 * Shared presentation (label and colour) for activity log entry types.
 *
 * Used by the Activity Log page and the Recent Activity dashboard widget so both show 3.0 entry types
 * consistently. Unknown types fall back to the raw type name in grey.
 */

/** PatternFly label colours used for activity entry types. */
export type ActivityTypeColor = "blue" | "green" | "orange" | "red" | "purple" | "teal" | "grey";

/** Display information for one activity entry type. */
export interface ActivityTypePresentation {
    label: string;
    color: ActivityTypeColor;
}

/** Known activity entry types (3.0). */
export const ACTIVITY_TYPES: Readonly<Record<string, ActivityTypePresentation>> = {
    "workflow-started": { label: "Workflow started", color: "blue" },
    "workflow-completed": { label: "Workflow completed", color: "green" },
    "workflow-failed": { label: "Workflow failed", color: "red" },
    "workflow-cancelled": { label: "Workflow cancelled", color: "orange" },
    "task-started": { label: "Task started", color: "teal" },
    "task-completed": { label: "Task completed", color: "green" },
    "task-failed": { label: "Task failed", color: "red" },
    "task-awaiting-input": { label: "Awaiting input", color: "orange" },
    "subject-created": { label: "Subject created", color: "purple" },
    "message": { label: "Message", color: "blue" },
};

/**
 * Returns the label and colour for an activity entry type.
 *
 * @param entryType the raw entry type from the activity log
 * @returns the known presentation, or the raw type in grey when unknown
 */
export function activityTypePresentation(entryType: string): ActivityTypePresentation {
    return ACTIVITY_TYPES[entryType] ?? { label: entryType, color: "grey" };
}
