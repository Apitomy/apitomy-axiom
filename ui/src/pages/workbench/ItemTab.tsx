import { useCallback, useEffect, useRef, useState } from "react";
import { Alert, AlertActionLink } from "@patternfly/react-core";
import type { NewActionType, NewToolDefinition } from "../../config/api";
import { ActionTypeEditor, type ValidationMessage } from "../../components/ActionTypeEditor";
import { ToolEditor } from "../../components/ToolEditor";
import {
    ApiError,
    type Composition,
    type CompositionItem,
    type ConfigProblem,
    fetchItem,
    saveItem,
} from "../../config/compositions";
import { isLockedForUser } from "./workbenchLogic";

const SAVE_DEBOUNCE_MS: number = 500;

/** Props of {@link ItemTab}. */
export interface ItemTabProps {
    compositionId: number;
    item: CompositionItem;
    content: Record<string, unknown>;
    composition: Composition;
    problems: ConfigProblem[] | undefined;
    onSaved: (item: CompositionItem, content: Record<string, unknown>) => void;
}

type SaveError = { kind: "conflict" } | { kind: "error"; message: string };

/**
 * Maps a server config problem to an editor validation message.
 * @param p the server problem
 * @returns the editor message
 */
export function toValidationMessage(p: ConfigProblem): ValidationMessage {
    return { severity: p.severity, field: p.path ?? "", message: p.message };
}

/**
 * Editor tab for an action type or tool composition item: autosaves with optimistic concurrency.
 */
export function ItemTab(props: ItemTabProps) {
    const { compositionId, item, content, composition, problems } = props;
    const [value, setValue] = useState<Record<string, unknown>>(content);
    const [saveError, setSaveError] = useState<SaveError | null>(null);
    const baseRevision = useRef<string>(item.contentRevision);
    const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
    const pendingContent = useRef<Record<string, unknown> | null>(null);
    const inFlight = useRef<Promise<void> | null>(null);
    const latest = useRef({ compositionId, item, onSaved: props.onSaved });
    latest.current = { compositionId, item, onSaved: props.onSaved };

    // Adopt external content changes only when no local save is pending.
    useEffect(() => {
        if (!timer.current && !inFlight.current && !pendingContent.current) {
            setValue(content);
        }
    }, [content]);

    const runSave = useCallback(async (): Promise<void> => {
        const next: Record<string, unknown> | null = pendingContent.current;
        if (!next) {
            return;
        }
        pendingContent.current = null;
        const { compositionId: cid, item: it } = latest.current;
        try {
            const saved: CompositionItem = await saveItem(cid, it.id, {
                content: next,
                baseRevision: baseRevision.current,
            });
            baseRevision.current = saved.contentRevision;
            setSaveError(null);
            latest.current.onSaved(saved, next);
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

    // Unmount: save any scheduled change.
    useEffect(() => () => {
        if (timer.current) {
            clearTimeout(timer.current);
            timer.current = null;
            void startSave();
        }
    }, [startSave]);

    const onChange = useCallback((next: Record<string, unknown>) => {
        setValue(next);
        pendingContent.current = next;
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
            baseRevision.current = fresh.contentRevision;
            setValue(fresh.content ?? {});
            setSaveError(null);
        } catch (e) {
            setSaveError({ kind: "error", message: e instanceof Error ? e.message : String(e) });
        }
    }, []);

    const locked: boolean = isLockedForUser(item, composition);
    const messages: ValidationMessage[] = (problems ?? []).map(toValidationMessage);

    return (
        <div className="axiom-workbench__item-tab">
            {locked && (
                <Alert isInline variant="info"
                    title="The AI is editing this item. It will be editable when the turn ends." />
            )}
            {saveError?.kind === "conflict" && (
                <Alert isInline variant="warning" title="Changed elsewhere"
                    actionLinks={<AlertActionLink onClick={() => void reload()}>Reload</AlertActionLink>} />
            )}
            {saveError?.kind === "error" && (
                <Alert isInline variant="danger" title={`Could not save: ${saveError.message}`} />
            )}
            {item.type === "tool" ? (
                <ToolEditor value={value as unknown as NewToolDefinition} readOnly={locked} problems={messages}
                    toolId={item.baseItemId}
                    onChange={(v: NewToolDefinition) => onChange(v as unknown as Record<string, unknown>)} />
            ) : (
                <ActionTypeEditor value={value as unknown as NewActionType} readOnly={locked} problems={messages}
                    onChange={(v: NewActionType) => onChange(v as unknown as Record<string, unknown>)} />
            )}
        </div>
    );
}
