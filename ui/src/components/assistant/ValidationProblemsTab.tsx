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
                    backgroundColor: "#fef3f2",
                    border: "1px solid #c9190b",
                }}>
                    <ExclamationCircleIcon style={{ color: "#c9190b", marginTop: 2, flexShrink: 0 }} />
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
                    backgroundColor: "#fdf7e7",
                    border: "1px solid #f0ab00",
                }}>
                    <ExclamationTriangleIcon style={{ color: "#f0ab00", marginTop: 2, flexShrink: 0 }} />
                    <div style={{ fontSize: "13px" }}>{msg}</div>
                </div>
            ))}
        </div>
    );
}
