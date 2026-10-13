import { type ReactNode, useState } from "react";
import type { Workflow } from "@apitomy/flow-ui";
import { Alert, AlertActionCloseButton, AlertActionLink, Button, Tooltip } from "@patternfly/react-core";
import { UndoIcon } from "@patternfly/react-icons";
import { AssistantChatPanel } from "../../components/assistant/AssistantChatPanel";
import { ApiError, type Composition, interruptComposition, sendCompositionMessage, undoTurn,
    type UndoResult } from "../../config/compositions";
import { undoPlan } from "./workbenchLogic";

/** Props for {@link ChatPane}. */
export interface ChatPaneProps {
    composition: Composition;
    sessionError: string | null;
    lastCompletedTurnId: string | undefined;
    prefill: { text: string; nonce: number } | undefined;
    /** Flushes pending saves and returns the current canvas selection. */
    beforeSend: () => Promise<{ nodeIds: string[]; edgeIds: string[] }>;
    onRetrySession: () => void;
    /** Applies the undo plan's workflow replacement in the editor. */
    onReplaceWorkflow: (workflow: Workflow) => void;
    lastWorkflowSaveTurnId: string | undefined;
    onUndone: () => void;
    /** Flushes unsaved editor changes before undoing. */
    beforeUndo?: () => Promise<void>;
    /** Controls shown at the left of the chat toolbar (for example the pane collapse button). */
    toolbarStart?: ReactNode;
}

function summarise(result: UndoResult, workflowRestored: boolean): string {
    const part = (label: string, list: string[]) => `${label}: ${list.length > 0 ? list.join(", ") : "none"}`;
    const restored: string[] = workflowRestored ? ["workflow", ...result.restored] : result.restored;
    return [part("Restored", restored), part("Removed", result.removed), part("Skipped", result.skipped)]
        .join(" · ");
}

/**
 * Workbench chat pane: a toolbar (host controls on the left, "Undo AI turn" on the right) above the
 * composition's assistant chat.
 */
export function ChatPane(props: ChatPaneProps) {
    const { composition, sessionError, lastCompletedTurnId, toolbarStart } = props;
    const [notice, setNotice] = useState<{ variant: "info" | "danger"; title: string; body?: string } | null>(null);
    const [undoing, setUndoing] = useState<boolean>(false);
    const [undoneTurnId, setUndoneTurnId] = useState<string | undefined>(undefined);

    const canUndo: boolean = Boolean(lastCompletedTurnId) && lastCompletedTurnId !== undoneTurnId
        && composition.turnState === "idle" && !sessionError;
    const toolbar = (
        <div className="axiom-workbench__chat-toolbar">
            {toolbarStart}
            {canUndo && lastCompletedTurnId && (
                <Tooltip content="Undo the last AI turn">
                    <Button variant="plain" size="sm" aria-label="Undo AI turn" icon={<UndoIcon />}
                        isDisabled={undoing} className="axiom-workbench__chat-toolbar-end"
                        onClick={() => void undo(lastCompletedTurnId)} />
                </Tooltip>
            )}
        </div>
    );

    if (sessionError) {
        return (
            <div className="axiom-workbench__chat">
                {toolbar}
                <Alert isInline variant="danger" title={`Assistant unavailable: ${sessionError}`}
                    actionLinks={<AlertActionLink onClick={props.onRetrySession}>Retry</AlertActionLink>} />
            </div>
        );
    }
    const sessionId: string | undefined = composition.assistantSessionId;
    if (!sessionId) {
        return (
            <div className="axiom-workbench__chat">
                {toolbar}
                <div className="axiom-workbench__placeholder">Starting the AI…</div>
            </div>
        );
    }

    const onSend = async (message: string): Promise<void> => {
        if (message.trim() === "/clear") {
            throw new Error("Clearing the conversation isn't supported in the workbench.");
        }
        const sel = await props.beforeSend();
        try {
            await sendCompositionMessage(composition.id, { message, ...sel });
        } catch (e) {
            if (e instanceof ApiError && e.status === 409) {
                throw new Error("The AI is already working on a turn.");
            }
            throw e;
        }
    };

    const undo = async (turnId: string): Promise<void> => {
        setUndoing(true);
        setNotice(null);
        try {
            await props.beforeUndo?.();
            const result: UndoResult = await undoTurn(composition.id, turnId);
            const plan = undoPlan(result, props.lastWorkflowSaveTurnId, turnId);
            if (plan.replaceWorkflow) {
                props.onReplaceWorkflow(plan.replaceWorkflow);
            }
            setNotice({ variant: "info", title: plan.message, body: summarise(result, Boolean(plan.replaceWorkflow)) });
            setUndoneTurnId(turnId);
            props.onUndone();
        } catch (e) {
            const title: string = e instanceof ApiError && e.status === 409
                ? "Wait for the AI turn to finish before undoing."
                : `Undo failed: ${e instanceof Error ? e.message : String(e)}`;
            setNotice({ variant: "danger", title });
        } finally {
            setUndoing(false);
        }
    };

    return (
        <div className="axiom-workbench__chat">
            {toolbar}
            {notice && (
                <Alert isInline variant={notice.variant} title={notice.title}
                    actionClose={<AlertActionCloseButton onClose={() => setNotice(null)} />}>
                    {notice.body}
                </Alert>
            )}
            <AssistantChatPanel sessionId={sessionId} onSend={onSend}
                onInterrupt={() => interruptComposition(composition.id)}
                disabledReason={composition.turnState === "ai-working" ? "The AI is working…" : undefined}
                prefill={props.prefill} />
        </div>
    );
}
