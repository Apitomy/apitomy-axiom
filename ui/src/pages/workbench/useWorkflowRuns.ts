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
    // Only the latest request may update the state, so a slow earlier response cannot overwrite a newer one.
    const sequence = useRef<number>(0);

    const refresh = useCallback(() => {
        if (definitionId === null) {
            sequence.current++;
            setRuns([]);
            setError(null);
            return;
        }
        setLoading(true);
        const request: number = ++sequence.current;
        const latest = (): boolean => request === sequence.current && current.current === definitionId;
        fetchWorkflowRuns(1, 50, { definitionId }).then((result) => {
            if (latest()) {
                setRuns(result.items ?? []);
                setError(null);
            }
        }).catch((e: unknown) => {
            if (latest()) {
                setError(e instanceof Error ? e.message : String(e));
            }
        }).finally(() => {
            if (latest()) {
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
