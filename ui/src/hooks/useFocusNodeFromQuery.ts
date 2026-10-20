import { useEffect, useRef } from "react";
import { useSearchParams } from "react-router-dom";
import type { WorkflowEditorHandle } from "@apitomy/flow-ui";

/**
 * Selects and centers the workflow node named by the {@code ?node=} query parameter once the editor is
 * ready, then removes the parameter so a reload or later edit does not jump back to it. A node id that is
 * not in the workflow is ignored by the editor.
 *
 * @param editor the workflow editor handle, or null until the editor has mounted
 */
export function useFocusNodeFromQuery(editor: WorkflowEditorHandle | null): void {
    const [searchParams, setSearchParams] = useSearchParams();
    const nodeId: string | null = searchParams.get("node");
    const focused = useRef<boolean>(false);

    useEffect(() => {
        if (!editor || !nodeId || focused.current) {
            return;
        }
        focused.current = true;
        editor.select({ nodeId }, { center: true });
        setSearchParams((previous) => {
            const next = new URLSearchParams(previous);
            next.delete("node");
            return next;
        }, { replace: true });
    }, [editor, nodeId, setSearchParams]);
}
