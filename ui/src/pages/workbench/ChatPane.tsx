import { useState } from "react";
import type { Workflow } from "@apitomy/flow-ui";
import { Alert, AlertActionCloseButton, AlertActionLink, Button } from "@patternfly/react-core";
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
}

function summarise(result: UndoResult): string {
    const part = (label: string, list: string[]) => `${label}: ${list.length > 0 ? list.join(", ") : "none"}`;
    return [part("Restored", result.restored), part("Removed", result.removed), part("Skipped", result.skipped)]
        .join(" · ");
}

/**
 * Workbench chat pane: the composition's assistant chat with an "Undo AI turn" bar.
 */
export function ChatPane(props: ChatPaneProps) {
    const { composition, sessionError, lastCompletedTurnId } = props;
    const [notice, setNotice] = useState<{ variant: "info" | "danger"; title: string; body?: string } | null>(null);
    const [undoing, setUndoing] = useState<boolean>(false);
    const [undoneTurnId, setUndoneTurnId] = useState<string | undefined>(undefined);

    if (sessionError) {
        return (
            <Alert isInline variant="danger" title={`Assistant unavailable: ${sessionError}`}
                actionLinks={<AlertActionLink onClick={props.onRetrySession}>Retry</AlertActionLink>} />
        );
    }
    const sessionId: string | undefined = composition.assistantSessionId;
    if (!sessionId) {
        return <div className="axiom-workbench__placeholder">Starting the AI…</div>;
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
            setNotice({ variant: "info", title: plan.message, body: summarise(result) });
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
            {lastCompletedTurnId && lastCompletedTurnId !== undoneTurnId && composition.turnState === "idle" && (
                <div className="axiom-workbench__undo-bar">
                    <span>Last AI turn ·</span>
                    <Button variant="link" isInline isDisabled={undoing}
                        onClick={() => void undo(lastCompletedTurnId)}>Undo AI turn</Button>
                </div>
            )}
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
