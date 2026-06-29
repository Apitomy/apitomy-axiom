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
                        ? "#fef3f2" : "#fdf7e7",
                    border: `1px solid ${msg.severity === "error"
                        ? "#c9190b" : "#f0ab00"}`,
                }}>
                    {msg.severity === "error"
                        ? <ExclamationCircleIcon style={{ color: "#c9190b", marginTop: 2 }} />
                        : <ExclamationTriangleIcon style={{ color: "#f0ab00", marginTop: 2 }} />
                    }
                    <div>
                        <div style={{ fontSize: "13px" }}>{msg.message}</div>
                        <div style={{ fontSize: "12px", color: "#6a6e73", marginTop: 2 }}>
                            Field: <code>{msg.field}</code>
                        </div>
                    </div>
                </div>
            ))}
        </div>
    );
}
