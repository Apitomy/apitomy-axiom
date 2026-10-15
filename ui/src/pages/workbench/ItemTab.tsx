import { type Ref, useCallback, useEffect, useImperativeHandle, useRef, useState } from "react";
import { Alert, AlertActionLink, Banner, Flex } from "@patternfly/react-core";
import { LockIcon } from "@patternfly/react-icons";
import type { NewActionType, NewToolDefinition, ToolValidationMessage } from "../../config/api";
import { ActionTypeEditor, type ValidationMessage } from "../../components/ActionTypeEditor";
import { ToolEditor } from "../../components/ToolEditor";
import { SubscriptionItemEditor } from "../../components/SubscriptionItemEditor";
import {
    ApiError,
    type Composition,
    type CompositionItem,
    type ConfigProblem,
    fetchItem,
    saveItem,
    setItemEnabled,
} from "../../config/compositions";
import { isLockedForUser, itemKindLabel } from "./workbenchLogic";

const SAVE_DEBOUNCE_MS: number = 500;

/** Props of {@link ItemTab}. */
export interface ItemTabProps {
    compositionId: number;
    item: CompositionItem;
    content: Record<string, unknown>;
    /** Revision of `content`; adopted as the save base when external content is adopted. */
    contentRevision?: string;
    composition: Composition;
    problems: ConfigProblem[] | undefined;
    onSaved: (item: CompositionItem, content: Record<string, unknown>) => void;
    /** Pre-fills the chat; enables "Ask AI to fix this" on each problem. */
    onAskAi?: (text: string) => void;
    /** Receives the tab's imperative handle. */
    ref?: Ref<ItemTabHandle>;
    /** Name of the composition's workflow (targeted by subscription start rules). */
    workflowName?: string;
    /** Whether the composition's workflow is published. */
    workflowPublished?: boolean;
    /** Called with an item's updated metadata (e.g. after toggling a subscription's enabled flag). */
    onItemChanged?: (item: CompositionItem) => void;
}

/** Imperative handle of {@link ItemTab}. */
export interface ItemTabHandle {
    /** Saves any scheduled change now and resolves when all saves have settled. */
    flush(): Promise<void>;
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
 * Editor tab for an action type, tool or subscription composition item: autosaves with optimistic concurrency.
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
            if (props.contentRevision) {
                baseRevision.current = props.contentRevision;
            }
        }
    }, [content, props.contentRevision]);

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

    const flush = useCallback(async (): Promise<void> => {
        if (timer.current) {
            clearTimeout(timer.current);
            timer.current = null;
            await startSave();
        } else if (inFlight.current) {
            await inFlight.current;
        }
    }, [startSave]);

    useImperativeHandle(props.ref, () => ({ flush }), [flush]);

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

    const aiWorking: boolean = composition.turnState === "ai-working";
    const askAiAboutProblem = props.onAskAi
        ? (message: ToolValidationMessage) => props.onAskAi?.(problemPrefill(item, message))
        : undefined;

    return (
        <div className="axiom-workbench__item-tab">
            {locked && (
                <Banner status="info" screenReaderText="Info banner">
                    <Flex spaceItems={{ default: "spaceItemsSm" }} flexWrap={{ default: "nowrap" }}>
                        <LockIcon />
                        <span>The AI is editing this item. It will be editable when the turn ends.</span>
                    </Flex>
                </Banner>
            )}
            {saveError?.kind === "conflict" && (
                <Alert isInline variant="warning" title="Changed elsewhere"
                    actionLinks={<AlertActionLink onClick={() => void reload()}>Reload</AlertActionLink>} />
            )}
            {saveError?.kind === "error" && (
                <Alert isInline variant="danger" title={`Could not save: ${saveError.message}`} />
            )}
            {item.type === "subscription" ? (
                <SubscriptionItemEditor value={value} readOnly={locked} problems={messages}
                    onChange={onChange} workflowName={props.workflowName ?? ""}
                    published={item.baseItemId != null} workflowPublished={props.workflowPublished ?? false}
                    enabled={item.enabled ?? false}
                    onToggleEnabled={(enabled: boolean) => {
                        setItemEnabled(compositionId, item.id, enabled)
                            .then((updated) => props.onItemChanged?.(updated))
                            .catch((e: unknown) => setSaveError({ kind: "error",
                                message: e instanceof Error ? e.message : String(e) }));
                    }} />
            ) : item.type === "tool" ? (
                // No toolId: the workbench tests tools through the Test output strip, not the editor's Test tab.
                <ToolEditor value={value as unknown as NewToolDefinition} readOnly={locked} problems={messages}
                    onAskAiAboutProblem={askAiAboutProblem} askAiDisabled={aiWorking} fillHeight
                    onChange={(v: NewToolDefinition) => onChange(v as unknown as Record<string, unknown>)} />
            ) : (
                <ActionTypeEditor value={value as unknown as NewActionType} readOnly={locked} problems={messages}
                    onAskAiAboutProblem={askAiAboutProblem} askAiDisabled={aiWorking} fillHeight
                    onChange={(v: NewActionType) => onChange(v as unknown as Record<string, unknown>)} />
            )}
        </div>
    );
}

/**
 * Chat text asking the AI to fix one validation problem of an action type, tool or subscription.
 *
 * @param item the composition item the problem belongs to
 * @param message the validation problem
 * @returns the message to pre-fill in the chat
 */
export function problemPrefill(item: CompositionItem, message: ToolValidationMessage): string {
    const kind: string = itemKindLabel(item.type);
    const field: string = message.field ? ` (field \`${message.field}\`)` : "";
    return `Please fix this problem in ${kind} \`${item.name}\`${field}: ${message.message}`;
}
