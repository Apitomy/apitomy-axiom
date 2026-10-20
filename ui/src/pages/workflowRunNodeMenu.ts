import type { WorkflowViewerNodeMenuItem } from "@apitomy/flow-ui";

/** The parts of a workflow run the node context menu needs. */
export interface RunForNodeMenu {
    definitionId?: number;
    traceId?: string;
    history?: { nodeId?: string; taskId?: number | null }[];
}

/** Actions the node context menu can trigger. */
export interface RunNodeMenuCallbacks {
    /** Opens the execution log of a task. */
    openLog: (taskId: number) => void;
    /** Shows the run's execution trace. */
    viewTrace: () => void;
    /** Navigates to a URL in the workflow editor. */
    openInEditor: (url: string) => void;
}

/**
 * Builds the URL that opens a workflow definition in the editor with one node selected and centered.
 *
 * @param definitionId the workflow definition id
 * @param nodeId the node to focus
 * @returns the URL
 */
export function workflowEditorNodeUrl(definitionId: number, nodeId: string): string {
    return `/components/workflows/${definitionId}?node=${encodeURIComponent(nodeId)}`;
}

/**
 * Builds the context menu for a node in a workflow run's graph.
 *
 * @param run the run
 * @param nodeId the node that was right-clicked
 * @param callbacks the actions the items trigger
 * @returns the menu items
 */
export function buildRunNodeMenuItems(run: RunForNodeMenu, nodeId: string,
                                      callbacks: RunNodeMenuCallbacks): WorkflowViewerNodeMenuItem[] {
    const items: WorkflowViewerNodeMenuItem[] = [];
    const entry = (run.history ?? []).find((h) => h.nodeId === nodeId);
    if (entry?.taskId != null) {
        const taskId: number = entry.taskId;
        items.push({ id: "open-log", label: "Open execution log", onSelect: () => callbacks.openLog(taskId) });
    }
    if (run.traceId) {
        items.push({ id: "view-trace", label: "View in execution trace", onSelect: callbacks.viewTrace });
    }
    if (run.definitionId != null) {
        const url: string = workflowEditorNodeUrl(run.definitionId, nodeId);
        items.push({ id: "open-in-editor", label: "Open in workflow editor", onSelect: () => callbacks.openInEditor(url) });
    }
    return items;
}
