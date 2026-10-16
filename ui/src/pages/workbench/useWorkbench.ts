import { useCallback, useEffect, useRef, useState } from "react";
import {
    type ChangeSet,
    type Composition,
    type CompositionItem,
    type CompositionTestRun,
    type ConfigProblem,
    ensureCompositionSession,
    fetchComposition,
    deleteItem,
    fetchItem,
    listItems,
    listTestRuns,
    mergeTestRun,
    mergeTestRuns,
    testItem,
    subscribeComposition,
    validateComposition,
} from "../../config/compositions";
import { sseClient } from "../../config/sse";

/** A change set waiting to be applied, with where it came from. */
export interface PendingChangeSet {
    turnId: string;
    changeSet: ChangeSet;
    fromPending: boolean;
}

/** Live state of a workbench composition. */
export interface WorkbenchState {
    composition: Composition | null;
    items: CompositionItem[];
    /** Item content by item id, loaded on demand (the workflow is loaded eagerly). */
    contents: Record<number, Record<string, unknown>>;
    /** Content revision of each entry in `contents` (set together with it by loads, SSE reloads and setContent). */
    revisions: Record<number, string>;
    /** Problems by item id from POST /validate (refreshed on load, after saves and after turns; debounced 800 ms). */
    problems: Record<number, ConfigProblem[]>;
    /** Pending change set to apply, with where it came from. Cleared by markPendingHandled. */
    pending: PendingChangeSet | null;
    /** The last AI turn that completed in this browser session (for Undo AI turn and AI badges). */
    lastCompletedTurnId: string | undefined;
    sessionError: string | null;
    loadContent(itemId: number): Promise<Record<string, unknown>>;
    refreshItems(): Promise<void>;
    refreshComposition(): Promise<void>;
    setContent(itemId: number, content: Record<string, unknown>, revision: string): void;
    /** Replaces one item's metadata (e.g. after toggling a subscription's enabled flag). */
    replaceItem(item: CompositionItem): void;
    /** Removes an item from the composition, drops its loaded content and revalidates. */
    removeItem(itemId: number): Promise<void>;
    markPendingHandled(): void;
    retrySession(): Promise<void>;
    /** Recent test runs of this composition, newest first (loaded on mount, updated by SSE and runTest). */
    testRuns: CompositionTestRun[];
    /** Whether the initial run history of the current composition has loaded (or failed to load). */
    testRunsLoaded: boolean;
    /** Test-runs a draft action type or tool, records the finished run and returns it. */
    /**
     * Starts a test run. With a `runId`, a "running" placeholder for that id is added to `testRuns` at once, so
     * the run can be shown before the request returns.
     */
    runTest(itemId: number, inputs: Record<string, unknown>, subjectId?: number, runId?: string,
            environment?: Record<string, string>): Promise<CompositionTestRun>;
}

const VALIDATE_DEBOUNCE_MS: number = 800;

function errorMessage(e: unknown): string {
    return e instanceof Error ? e.message : String(e);
}

function storedPending(comp: Composition): PendingChangeSet | null {
    if (comp.pendingTurnId && comp.pendingChangeSet) {
        return { turnId: comp.pendingTurnId, changeSet: comp.pendingChangeSet as unknown as ChangeSet,
            fromPending: true };
    }
    return null;
}

/**
 * Loads a composition and keeps it in sync with the server through SSE.
 *
 * @param compositionId the composition to edit
 * @returns the live workbench state and actions
 */
export function useWorkbench(compositionId: number): WorkbenchState {
    const [composition, setComposition] = useState<Composition | null>(null);
    const [items, setItems] = useState<CompositionItem[]>([]);
    const [contents, setContents] = useState<Record<number, Record<string, unknown>>>({});
    const [problems, setProblems] = useState<Record<number, ConfigProblem[]>>({});
    const [pending, setPending] = useState<PendingChangeSet | null>(null);
    const [lastCompletedTurnId, setLastCompletedTurnId] = useState<string | undefined>(undefined);
    const [sessionError, setSessionError] = useState<string | null>(null);

    const [revisions, setRevisions] = useState<Record<number, string>>({});
    const revisionsRef = useRef<Record<number, string>>({});
    const handledRef = useRef<Set<string>>(new Set());
    const pendingRef = useRef<PendingChangeSet | null>(null);
    const timerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
    const aliveRef = useRef<boolean>(true);
    const [testRuns, setTestRuns] = useState<CompositionTestRun[]>([]);
    /** Composition whose initial run history has loaded; compared to compositionId so a switch resets it at once. */
    const [testRunsLoadedFor, setTestRunsLoadedFor] = useState<number | null>(null);

    const updatePending = useCallback((p: PendingChangeSet | null) => {
        pendingRef.current = p;
        setPending(p);
    }, []);

    const validate = useCallback(async (isCancelled: () => boolean = () => false) => {
        try {
            const result = await validateComposition(compositionId);
            if (!aliveRef.current || isCancelled()) {
                return;
            }
            const next: Record<number, ConfigProblem[]> = {};
            result.items.forEach((r) => { next[r.itemId] = r.problems; });
            setProblems(next);
        } catch (e) {
            console.error(e);
        }
    }, [compositionId]);

    const scheduleValidate = useCallback(() => {
        clearTimeout(timerRef.current);
        timerRef.current = setTimeout(() => { void validate(); }, VALIDATE_DEBOUNCE_MS);
    }, [validate]);

    const loadContent = useCallback(async (itemId: number, isCancelled: () => boolean = () => false) => {
        const item = await fetchItem(compositionId, itemId);
        if (isCancelled()) {
            return {};
        }
        const content: Record<string, unknown> = (item as CompositionItem & { content?: Record<string, unknown> })
            .content ?? {};
        revisionsRef.current[itemId] = item.contentRevision;
        if (aliveRef.current) {
            setContents((prev) => ({ ...prev, [itemId]: content }));
            setRevisions((prev) => ({ ...prev, [itemId]: item.contentRevision }));
        }
        return content;
    }, [compositionId]);

    const refreshItems = useCallback(async () => {
        const list = await listItems(compositionId);
        if (aliveRef.current) {
            setItems(list);
        }
    }, [compositionId]);

    const adoptPending = useCallback((comp: Composition) => {
        const stored = storedPending(comp);
        if (stored && !handledRef.current.has(stored.turnId) && !pendingRef.current) {
            updatePending(stored);
        }
    }, [updatePending]);

    const refreshComposition = useCallback(async () => {
        const comp = await fetchComposition(compositionId);
        if (aliveRef.current) {
            setComposition(comp);
        }
    }, [compositionId]);

    const startSession = useCallback(async (isCancelled: () => boolean = () => false) => {
        try {
            const comp = await ensureCompositionSession(compositionId);
            if (isCancelled()) {
                return;
            }
            if (aliveRef.current) {
                setSessionError(null);
                setComposition((prev) => (prev ? { ...prev, ...comp } : prev));
            }
        } catch (e) {
            if (aliveRef.current && !isCancelled()) {
                setSessionError(errorMessage(e));
            }
        }
    }, [compositionId]);

    useEffect(() => {
        aliveRef.current = true;
        setTestRuns([]);
        let cancelled: boolean = false;
        const isCancelled = (): boolean => cancelled;
        (async () => {
            try {
                const [comp, list] = await Promise.all([fetchComposition(compositionId), listItems(compositionId)]);
                if (cancelled) {
                    return;
                }
                setComposition(comp);
                setItems(list);
                adoptPending(comp);
                const workflow = list.find((i) => i.type === "workflow");
                if (workflow) {
                    await loadContent(workflow.id, isCancelled);
                }
            } catch (e) {
                console.error(e);
            }
            if (cancelled) {
                return;
            }
            void listTestRuns(compositionId)
                .then((runs) => { if (aliveRef.current && !isCancelled()) setTestRuns((prev) => mergeTestRuns(prev, runs)); })
                .catch(console.error)
                .finally(() => { if (aliveRef.current && !isCancelled()) setTestRunsLoadedFor(compositionId); });
            // Validation does not depend on the agent: run it alongside the (possibly slow) session start.
            await Promise.all([startSession(isCancelled), validate(isCancelled)]);
        })();
        return () => {
            cancelled = true;
            aliveRef.current = false;
            clearTimeout(timerRef.current);
        };
    }, [compositionId, adoptPending, loadContent, startSession, validate]);

    useEffect(() => {
        const unsubscribe = subscribeComposition(compositionId, {
            testRun: (ev) => setTestRuns((prev) => mergeTestRun(prev, ev.run)),
            itemChanged: (ev) => {
                void refreshItems().catch(console.error);
                const loaded = revisionsRef.current[ev.itemId];
                if (loaded !== undefined && loaded !== ev.contentRevision) {
                    void loadContent(ev.itemId).catch(console.error);
                }
                scheduleValidate();
            },
            turn: (ev) => {
                void refreshComposition().catch(console.error);
                if (ev.state === "idle" && ev.turnId) {
                    setLastCompletedTurnId(ev.turnId);
                    scheduleValidate();
                }
            },
            changeSet: (ev) => {
                if (ev.turnId && !handledRef.current.has(ev.turnId)
                        && pendingRef.current?.turnId !== ev.turnId) {
                    updatePending({ turnId: ev.turnId, changeSet: ev.changeSet, fromPending: false });
                }
            },
        });
        const offReconnect = sseClient.onReconnect(() => {
            void (async () => {
                try {
                    const [comp] = await Promise.all([fetchComposition(compositionId), refreshItems()]);
                    if (aliveRef.current) {
                        setComposition(comp);
                        adoptPending(comp);
                    }
                } catch (e) {
                    console.error(e);
                }
            })();
        });
        return () => {
            unsubscribe();
            offReconnect();
        };
    }, [compositionId, refreshItems, loadContent, scheduleValidate, refreshComposition, updatePending, adoptPending]);

    // Action type drafts feed the canvas catalog and subscription routing feeds publish dependencies, so keep
    // every action type's and subscription's content loaded and current.
    const loadingRef = useRef<Map<number, string>>(new Map());
    useEffect(() => {
        items.filter((i) => i.type === "action-type" || i.type === "subscription").forEach((i) => {
            if (revisionsRef.current[i.id] === i.contentRevision || loadingRef.current.get(i.id) === i.contentRevision) {
                return;
            }
            loadingRef.current.set(i.id, i.contentRevision);
            void loadContent(i.id)
                .catch(console.error)
                .finally(() => {
                    if (loadingRef.current.get(i.id) === i.contentRevision) {
                        loadingRef.current.delete(i.id);
                    }
                });
        });
    }, [items, loadContent]);

    const setContent = useCallback((itemId: number, content: Record<string, unknown>, revision: string) => {
        revisionsRef.current[itemId] = revision;
        setContents((prev) => ({ ...prev, [itemId]: content }));
        setRevisions((prev) => ({ ...prev, [itemId]: revision }));
        scheduleValidate();
    }, [scheduleValidate]);

    const replaceItem = useCallback((item: CompositionItem) => {
        setItems((prev) => prev.map((i) => (i.id === item.id ? item : i)));
    }, []);

    const removeItem = useCallback(async (itemId: number) => {
        await deleteItem(compositionId, itemId);
        delete revisionsRef.current[itemId];
        if (aliveRef.current) {
            setContents((prev) => {
                const next = { ...prev };
                delete next[itemId];
                return next;
            });
            setRevisions((prev) => {
                const next = { ...prev };
                delete next[itemId];
                return next;
            });
        }
        await refreshItems();
        scheduleValidate();
    }, [compositionId, refreshItems, scheduleValidate]);

    const markPendingHandled = useCallback(() => {
        if (pendingRef.current) {
            handledRef.current.add(pendingRef.current.turnId);
        }
        updatePending(null);
    }, [updatePending]);

    const itemsRef = useRef<CompositionItem[]>([]);
    itemsRef.current = items;

    const runTest = useCallback(async (itemId: number, inputs: Record<string, unknown>, subjectId?: number,
                                       runId?: string, environment?: Record<string, string>) => {
        const item: CompositionItem | undefined = itemsRef.current.find((i) => i.id === itemId);
        if (runId && item && (item.type === "action-type" || item.type === "tool")) {
            const placeholder: CompositionTestRun = { id: runId, itemId, itemType: item.type, itemName: item.name,
                author: "user", status: "running", startedAt: new Date().toISOString(), inputs, subjectId };
            setTestRuns((prev) => mergeTestRun(prev, placeholder));
        }
        const data: { inputs: Record<string, unknown>; subjectId?: number; runId?: string;
            environment?: Record<string, string> } = { inputs };
        if (subjectId !== undefined) {
            data.subjectId = subjectId;
        }
        if (runId) {
            data.runId = runId;
        }
        if (environment && Object.keys(environment).length > 0) {
            data.environment = environment;
        }
        let run: CompositionTestRun;
        try {
            run = await testItem(compositionId, itemId, data);
        } catch (e) {
            // The run never started: drop its placeholder.
            if (runId && aliveRef.current) {
                setTestRuns((prev) => prev.filter((r) => r.id !== runId || r.status !== "running"));
            }
            throw e;
        }
        if (aliveRef.current) {
            // If the server chose another id, the placeholder would stay "running" forever: drop it.
            setTestRuns((prev) => mergeTestRun(runId && run.id !== runId
                ? prev.filter((r) => r.id !== runId) : prev, run));
        }
        return run;
    }, [compositionId]);

    return {
        testRuns, testRunsLoaded: testRunsLoadedFor === compositionId, runTest,
        composition, items, contents, revisions, problems, pending, lastCompletedTurnId, sessionError,
        loadContent, refreshItems, refreshComposition, setContent, replaceItem, removeItem, markPendingHandled, retrySession: () => startSession(),
    };
}
