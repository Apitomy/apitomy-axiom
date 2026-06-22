import { Button } from "@patternfly/react-core";
import PencilAltIcon from "@patternfly/react-icons/dist/esm/icons/pencil-alt-icon";
import { ColoredLabel } from "./ColoredLabel";

/**
 * Read-only display of labels as colored chips with a pencil icon
 * button to trigger editing. Shows "No labels" in italic when empty.
 */
export function LabelDisplay({ labels, onEdit }: {
    labels: string[];
    onEdit: () => void;
}) {
    return (
        <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
            {labels.length > 0 ? (
                <div style={{ display: "flex", flexWrap: "wrap", gap: "4px" }}>
                    {labels.map((label) => (
                        <ColoredLabel key={label} isCompact>{label}</ColoredLabel>
                    ))}
                </div>
            ) : (
                <span style={{ color: "#6a6e73", fontStyle: "italic" }}>No labels</span>
            )}
            <Button variant="plain" size="sm" style={{ padding: 0 }}
                onClick={onEdit} aria-label="Edit labels">
                <PencilAltIcon />
            </Button>
        </div>
    );
}
