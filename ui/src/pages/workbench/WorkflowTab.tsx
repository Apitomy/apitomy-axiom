import { useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState } from "react";
import { Alert, AlertActionLink } from "@patternfly/react-core";
import {
    type ActionTypeDescriptor,
    type ApplyResult,
    type ChangeSet,
    type ContextAction,
    type EditorSpi,
    type FlowContext,
    type ProposalOutcome,
    type ProposeResult,
    type ValidationProblem,
    type Workflow,
    WorkflowEditor,
    type WorkflowEditorHandle,
} from "@apitomy/flow-ui";
import {
    ApiError,
    type ChangeSetOutcome,
    type Composition,
    type CompositionItem,
    type ConfigProblem,
    fetchItem,
    resolveChangeSet,
    saveItem,
    validateItemContent,
} from "../../config/compositions";
import { useEffectiveTheme } from "../../hooks/useTheme";

const SAVE_DEBOUNCE_MS: number = 500;

/** Imperative API of the workflow tab. */
export interface WorkflowTabHandle {
    /** Saves any pending debounced change immediately. */
    flush(): Promise<void>;
    /** Replaces the editor document (host origin), e.g. for Undo AI turn or a restored revision. */
    replace(workflow: Workflow): void;
    /** Current editor selection. */
    selection(): { nodeIds: string[]; edgeIds: string[] };
}

/** Props of {@link WorkflowTab}. */
export interface WorkflowTabProps {
    compositionId: number;
    item: CompositionItem;
    initialContent: Workflow;
    composition: Composition;
    catalog: ActionTypeDescriptor[];
    applyLayout: "place" | "tidy";
    pending: { turnId: string; changeSet: ChangeSet; fromPending: boolean } | null;
    onPendingHandled: () => void;
    onSaved: (item: CompositionItem, content: Workflow, savedTurnId: string | undefined) => void;
    onOpenActionType: (value: string, resolved: boolean) => void;
    onAskAi: (prefill: string) => void;
    ref?: React.Ref<WorkflowTabHandle>;
}

type SaveError = { kind: "conflict" } | { kind: "error"; message: string };

const NODE_PATH: RegExp = /^nodes(?:\[([^\]]+)\]|\/([^/[\]]+))/;

/**
 * Maps a server config problem to a Flow validation problem.
 * @param p the server problem
 * @returns the Flow problem, targeting a node when the path names one
 */
export function toFlowProblem(p: ConfigProblem): ValidationProblem {
    const problem: ValidationProblem = { severity: p.severity, code: p.code, message: p.message };
    const m: RegExpMatchArray | null = p.path ? p.path.match(NODE_PATH) : null;
    if (m) {
        problem.nodeId = (m[1] ?? m[2]).replace(/^['"]|['"]$/g, "");
    }
    return problem;
}

/**
 * Builds the chat prefill for a context-menu target.
 * @param context the Flow context
 * @returns the prefill text
 */
export function askAiPrefill(context: Pick<FlowContext, "target" | "workflow">): string {
    const t = context.target;
    switch (t.kind) {
        case "node": {
            const node = context.workflow.nodes.find((n) => n.id === t.nodeId);
            return `About node \`${t.nodeId}\` (${node?.name ?? ""}, ${node?.type ?? ""}): `;
        }
        case "edge": {
            const edge = context.workflow.edges.find((e) => e.id === t.edgeId);
            return `About edge \`${t.edgeId}\` (${edge?.source ?? ""} → ${edge?.target ?? ""}): `;
        }
        case "selection":
            return `About nodes ${t.nodeIds.join(", ")} and edges ${t.edgeIds.join(", ")}: `;
        case "problem":
            return `Please fix this problem: ${t.problem.message} `;
        default:
            return "";
    }
}

function proposalOutcome(outcome: ProposalOutcome): ChangeSetOutcome {
    if (outcome === "accepted") {
        return "applied";
    }
    return outcome === "stale" ? "stale" : "rejected";
}

/**
 * The workflow editor tab: embeds the Flow editor, autosaves, applies AI change sets and offers "Ask AI".
 */
export function WorkflowTab(props: WorkflowTabProps) {
    const { compositionId, item, composition, catalog, pending, onPendingHandled, onSaved, onOpenActionType,
        onAskAi } = props;
    const theme = useEffectiveTheme();
    const editorRef = useRef<WorkflowEditorHandle | null>(null);
    const lastSavedRevision = useRef<string>(item.contentRevision);
    const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
    const pendingContent = useRef<Workflow | null>(null);
    const inFlight = useRef<Promise<void> | null>(null);
    const turnIdForNextSave = useRef<string | undefined>(undefined);
    const applyingTurn = useRef<string | undefined>(undefined);
    const suppressNextSave = useRef<boolean>(false);
    const handledTurns = useRef<Set<string>>(new Set());
    const proposals = useRef<Map<string, { turnId: string; fromPending: boolean }>>(new Map());
    const selectionRef = useRef<{ nodeIds: string[]; edgeIds: string[] }>({ nodeIds: [], edgeIds: [] });
    const [saveError, setSaveError] = useState<SaveError | null>(null);
    const [applyError, setApplyError] = useState<string | null>(null);

    // Keep latest callbacks/values reachable from stable closures.
    const latest = useRef({ compositionId, item, onSaved, onPendingHandled, onOpenActionType, onAskAi, composition });
    latest.current = { compositionId, item, onSaved, onPendingHandled, onOpenActionType, onAskAi, composition };

    const runSave = useCallback(async (): Promise<void> => {
        const content: Workflow | null = pendingContent.current;
        if (!content) {
            return;
        }
        pendingContent.current = null;
        const turnId: string | undefined = turnIdForNextSave.current;
        turnIdForNextSave.current = undefined;
        const { compositionId: cid, item: it } = latest.current;
        try {
            const saved: CompositionItem = await saveItem(cid, it.id, {
                content: content as unknown as Record<string, unknown>,
                baseRevision: lastSavedRevision.current,
                ...(turnId ? { turnId } : {}),
            });
            lastSavedRevision.current = saved.contentRevision;
            setSaveError(null);
            latest.current.onSaved(saved, content, turnId);
        } catch (e) {
            if (e instanceof ApiError && e.status === 409) {
                setSaveError({ kind: "conflict" });
            } else {
                setSaveError({ kind: "error", message: e instanceof Error ? e.message : String(e) });
            }
        }
    }, []);

    const startSave = useCallback((): Promise<void> => {
        const previous: Promise<void> = inFlight.current ?? Promise.resolve();
        const next: Promise<void> = previous.then(runSave);
        inFlight.current = next;
        void next.finally(() => {
            if (inFlight.current === next) {
                inFlight.current = null;
            }
        });
        return next;
    }, [runSave]);

    const flush = useCallback(async (): Promise<void> => {
        if (timer.current) {
            clearTimeout(timer.current);
            timer.current = null;
            await startSave();
        } else if (inFlight.current) {
            await inFlight.current;
        }
    }, [startSave]);

    useEffect(() => () => {
        if (timer.current) {
            clearTimeout(timer.current);
        }
    }, []);

    const onChange = useCallback((workflow: Workflow, meta: { origin: string }) => {
        if (suppressNextSave.current) {
            suppressNextSave.current = false;
            return;
        }
        pendingContent.current = workflow;
        if (meta.origin.startsWith("agent:")) {
            turnIdForNextSave.current = applyingTurn.current;
        }
        if (timer.current) {
            clearTimeout(timer.current);
        }
        timer.current = setTimeout(() => {
            timer.current = null;
            void startSave();
        }, SAVE_DEBOUNCE_MS);
    }, [startSave]);

    const reload = useCallback(async () => {
        const { compositionId: cid, item: it } = latest.current;
        try {
            const fresh: CompositionItem = await fetchItem(cid, it.id);
            if (timer.current) {
                clearTimeout(timer.current);
                timer.current = null;
            }
            pendingContent.current = null;
            lastSavedRevision.current = fresh.contentRevision;
            suppressNextSave.current = true;
            editorRef.current?.replace(fresh.content as unknown as Workflow, "host");
            setSaveError(null);
        } catch (e) {
            setSaveError({ kind: "error", message: e instanceof Error ? e.message : String(e) });
        }
    }, []);

    const resolve = useCallback(async (turnId: string, outcome: ChangeSetOutcome, fromPending: boolean) => {
        try {
            await resolveChangeSet(latest.current.compositionId, { turnId, outcome, fromPending });
        } catch (e) {
            console.error(e);
        }
        latest.current.onPendingHandled();
    }, []);

    useEffect(() => {
        if (!pending || handledTurns.current.has(pending.turnId)) {
            return;
        }
        handledTurns.current.add(pending.turnId);
        const { turnId, changeSet, fromPending } = pending;
        void (async () => {
            await flush();
            const handle: WorkflowEditorHandle | null = editorRef.current;
            if (!handle) {
                return;
            }
            setApplyError(null);
            applyingTurn.current = turnId;
            if (latest.current.composition.applyMode === "review") {
                proposals.current.set(changeSet.id, { turnId, fromPending });
                const result: ProposeResult = handle.propose(changeSet);
                if (result.status === "staged") {
                    return;
                }
                proposals.current.delete(changeSet.id);
                await rejected(turnId, fromPending, result.error.code, result.error.reason);
            } else {
                const result: ApplyResult = handle.apply(changeSet);
                if (result.status === "applied") {
                    await resolve(turnId, "applied", fromPending);
                } else {
                    await rejected(turnId, fromPending, result.error.code, result.error.reason);
                }
            }
        })();

        async function rejected(id: string, fp: boolean, code: string, reason: string) {
            if (code === "stale") {
                await resolve(id, "stale", fp);
            } else {
                setApplyError(`The AI's workflow changes could not be applied: ${reason}`);
                await resolve(id, "rejected", fp);
            }
        }
    }, [pending, flush, resolve]);

    const onProposalResolved = useCallback((id: string, outcome: ProposalOutcome) => {
        if (!proposals.current.has(id)) {
            return;
        }
        const entry = proposals.current.get(id);
        proposals.current.delete(id);
        if (entry) {
            void resolve(entry.turnId, proposalOutcome(outcome), entry.fromPending);
        }
    }, [resolve]);

    const onSelectionChange = useCallback((s: { nodeIds: string[]; edgeIds: string[] }) => {
        selectionRef.current = { nodeIds: [...s.nodeIds], edgeIds: [...s.edgeIds] };
    }, []);

    const aiWorking: boolean = composition.turnState === "ai-working";
    const spi: EditorSpi = useMemo(() => ({
        actionTypes: catalog,
        openActionType: (r: { value: string; resolved: boolean }) => latest.current.onOpenActionType(r.value, r.resolved),
        validate: async (workflow: Workflow): Promise<ValidationProblem[]> => {
            const { compositionId: cid, item: it } = latest.current;
            const result = await validateItemContent(cid, it.id, workflow as unknown as Record<string, unknown>);
            return result.problems.map(toFlowProblem);
        },
        contextActions: (context: FlowContext): ContextAction[] => {
            if (context.readOnly) {
                return [];
            }
            return [{
                id: "axiom-ask-ai",
                label: context.target.kind === "problem" ? "Ask AI to fix this" : "Ask AI about this…",
                disabled: aiWorking,
                onSelect: (ctx: FlowContext) => latest.current.onAskAi(askAiPrefill(ctx)),
            }];
        },
    }), [catalog, aiWorking]);

    useImperativeHandle(props.ref, () => ({
        flush,
        replace: (workflow: Workflow) => editorRef.current?.replace(workflow, "host"),
        selection: () => selectionRef.current,
    }), [flush]);

    return (
        <div className="axiom-workbench__workflow-tab">
            {saveError?.kind === "conflict" && (
                <Alert isInline variant="warning" title="This workflow was changed elsewhere."
                    actionLinks={<AlertActionLink onClick={() => void reload()}>Reload</AlertActionLink>} />
            )}
            {saveError?.kind === "error" && (
                <Alert isInline variant="danger" title={`Could not save the workflow: ${saveError.message}`} />
            )}
            {applyError && (
                <Alert isInline variant="danger" title={applyError}
                    actionLinks={<AlertActionLink onClick={() => setApplyError(null)}>Dismiss</AlertActionLink>} />
            )}
            <WorkflowEditor ref={editorRef} workflow={props.initialContent} theme={theme} highlightApplied
                appliedChangeLayout={props.applyLayout} spi={spi} onChange={onChange}
                onProposalResolved={onProposalResolved} onSelectionChange={onSelectionChange} />
        </div>
    );
}
