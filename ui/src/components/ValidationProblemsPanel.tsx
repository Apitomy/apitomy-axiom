import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";
import ExclamationTriangleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-triangle-icon";
import { Button } from "@patternfly/react-core";
import type { ToolValidationMessage } from "../config/api";

/**
 * Renders a list of validation messages with severity-based styling.
 *
 * Used by ActionTypeDetailPage and ToolDetailPage. In the workbench, `onAskAi` adds an "Ask AI to fix this"
 * action to each problem.
 */
export function ValidationProblemsPanel({ messages, onAskAi, askAiDisabled }: {
    messages: ToolValidationMessage[];
    /** When set, each problem offers "Ask AI to fix this", which calls this with the problem. */
    onAskAi?: (message: ToolValidationMessage) => void;
    /** Disables the "Ask AI to fix this" actions, for example while the AI is working. */
    askAiDisabled?: boolean;
}) {
    return (
        <div style={{ maxWidth: "700px" }}>
            {messages.map((msg, i) => (
                <div key={i} style={{
                    display: "flex",
                    alignItems: "flex-start",
                    gap: 8,
                    padding: "10px 12px",
                    marginBottom: 4,
                    borderRadius: 4,
                    backgroundColor: msg.severity === "error"
                        ? "var(--axiom--color--danger--bg)" : "var(--axiom--color--warning--bg)",
                    border: `1px solid ${msg.severity === "error"
                        ? "var(--pf-t--global--color--status--danger--default, #c9190b)" : "var(--pf-t--global--color--status--warning--default, #f0ab00)"}`,
                }}>
                    {msg.severity === "error"
                        ? <ExclamationCircleIcon className="axiom-icon-danger" style={{ marginTop: 2 }} />
                        : <ExclamationTriangleIcon className="axiom-icon-warning" style={{ marginTop: 2 }} />
                    }
                    <div>
                        <div style={{ fontSize: "13px" }}>{msg.message}</div>
                        <div className="axiom-text-subtle" style={{ fontSize: "12px", marginTop: 2 }}>
                            Field: <code>{msg.field}</code>
                        </div>
                    </div>
                    {onAskAi && (
                        <Button variant="link" isInline size="sm" style={{ marginLeft: "auto", whiteSpace: "nowrap" }}
                            isDisabled={askAiDisabled} onClick={() => onAskAi(msg)}>Ask AI to fix this</Button>
                    )}
                </div>
            ))}
        </div>
    );
}
