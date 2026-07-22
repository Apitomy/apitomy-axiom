import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";
import ExclamationTriangleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-triangle-icon";

interface ValidationProblemsTabProps {
    errors?: string[];
    warnings?: string[];
}

export function ValidationProblemsTab({ errors = [], warnings = [] }: ValidationProblemsTabProps) {
    return (
        <div style={{ paddingTop: 16, maxWidth: "700px" }}>
            {errors.map((msg, i) => (
                <div key={`e-${i}`} style={{
                    display: "flex",
                    alignItems: "flex-start",
                    gap: 8,
                    padding: "10px 12px",
                    marginBottom: 4,
                    borderRadius: 4,
                    backgroundColor: "var(--axiom--color--danger--bg)",
                    border: "1px solid var(--pf-t--global--color--status--danger--default, #c9190b)",
                }}>
                    <ExclamationCircleIcon className="axiom-icon-danger" style={{ marginTop: 2, flexShrink: 0 }} />
                    <div style={{ fontSize: "13px" }}>{msg}</div>
                </div>
            ))}
            {warnings.map((msg, i) => (
                <div key={`w-${i}`} style={{
                    display: "flex",
                    alignItems: "flex-start",
                    gap: 8,
                    padding: "10px 12px",
                    marginBottom: 4,
                    borderRadius: 4,
                    backgroundColor: "var(--axiom--color--warning--bg)",
                    border: "1px solid var(--pf-t--global--color--status--warning--default, #f0ab00)",
                }}>
                    <ExclamationTriangleIcon className="axiom-icon-warning" style={{ marginTop: 2, flexShrink: 0 }} />
                    <div style={{ fontSize: "13px" }}>{msg}</div>
                </div>
            ))}
        </div>
    );
}
