import { useCallback, useEffect, useRef, useState } from "react";
import { fetchWorkflowRuns, type WorkflowRunSummary } from "../../config/api";
import { sseClient } from "../../config/sse";

/** State of {@link useWorkflowRuns}. */
export interface WorkflowRunsState {
    /** Runs of the definition, newest first. */
    runs: WorkflowRunSummary[];
    loading: boolean;
    error: string | null;
    /** Whether the first load for the current definition has finished. */
    loaded: boolean;
    /** Reloads the runs. */
    refresh(): void;
}

const DEBOUNCE_MS: number = 500;

/**
 * Loads the recent runs of a published workflow definition and keeps them current via `workflow-updated` SSE events.
 * @param definitionId the definition id, or null when the workflow is not published
 * @returns the runs state
 */
export function useWorkflowRuns(definitionId: number | null): WorkflowRunsState {
    const [runs, setRuns] = useState<WorkflowRunSummary[]>([]);
    const [loading, setLoading] = useState<boolean>(false);
    const [error, setError] = useState<string | null>(null);
    const [loadedFor, setLoadedFor] = useState<number | null>(null);
    const current = useRef<number | null>(definitionId);
    current.current = definitionId;

    const refresh = useCallback(() => {
        if (definitionId === null) {
            setRuns([]);
            setError(null);
            return;
        }
        setLoading(true);
        fetchWorkflowRuns(1, 50, { definitionId }).then((result) => {
            if (current.current === definitionId) {
                setRuns(result.items ?? []);
                setError(null);
            }
        }).catch((e: unknown) => {
            if (current.current === definitionId) {
                setError(e instanceof Error ? e.message : String(e));
            }
        }).finally(() => {
            if (current.current === definitionId) {
                setLoading(false);
                setLoadedFor(definitionId);
            }
        });
    }, [definitionId]);

    useEffect(() => {
        refresh();
        if (definitionId === null) {
            return undefined;
        }
        let timer: ReturnType<typeof setTimeout> | undefined;
        const unsubscribe = sseClient.subscribe((event) => {
            if (event.type === "workflow-updated") {
                clearTimeout(timer);
                timer = setTimeout(refresh, DEBOUNCE_MS);
            }
        });
        return () => {
            clearTimeout(timer);
            unsubscribe();
        };
    }, [definitionId, refresh]);

    return { runs, loading, error, loaded: definitionId !== null && loadedFor === definitionId, refresh };
}
