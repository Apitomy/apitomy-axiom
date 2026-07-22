import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";
import ExclamationTriangleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-triangle-icon";
import type { ToolValidationMessage } from "../config/api";

/**
 * Renders a list of validation messages with severity-based styling.
 *
 * Used by ActionTypeDetailPage, ToolDetailPage, and ReportDefinitionDetailPage.
 */
export function ValidationProblemsPanel({ messages }: {
    messages: ToolValidationMessage[];
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
                </div>
            ))}
        </div>
    );
}
