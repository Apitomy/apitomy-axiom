import { EmptyState, EmptyStateBody, Label } from "@patternfly/react-core";

/** A declared input or output field. */
export interface DeclaredField {
    name: string;
    type?: string;
    required?: boolean;
    description?: string;
}

/**
 * Lists declared fields (action type inputs/outputs, workflow start inputs): name, type, required badge and
 * description, one row per field.
 */
export function FieldsTable({ fields }: { fields: DeclaredField[] }) {
    if (fields.length === 0) {
        return (
            <EmptyState variant="sm">
                <EmptyStateBody>No fields declared.</EmptyStateBody>
            </EmptyState>
        );
    }
    return (
        <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
            {fields.map((f, i) => (
                <div key={i} data-testid="field-row" style={{
                    display: "flex", alignItems: "center", gap: 8,
                    padding: "8px 12px",
                    backgroundColor: "var(--pf-t--global--background--color--secondary--default)",
                    borderRadius: 4,
                }}>
                    <span style={{ fontWeight: 600, fontSize: 13, minWidth: 140 }}>{f.name}</span>
                    <Label isCompact color="blue">{f.type || "string"}</Label>
                    {f.required && <Label isCompact color="orange">required</Label>}
                    {f.description && (
                        <span className="axiom-text-subtle" style={{ fontSize: 13, flex: 1 }}>{f.description}</span>
                    )}
                </div>
            ))}
        </div>
    );
}
