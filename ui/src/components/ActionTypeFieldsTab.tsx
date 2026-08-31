import { useState } from "react";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Checkbox,
    Flex,
    FlexItem,
    FormGroup,
    FormSelect,
    FormSelectOption,
    TextInput,
} from "@patternfly/react-core";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import type { ActionTypeField } from "../config/api";

const FIELD_TYPES: ActionTypeField["type"][] = ["string", "number", "boolean", "object"];

/**
 * Editable list of typed fields (name/type/required/description). Reused by the
 * Action Type Inputs and Outputs tabs. Preserves order.
 */
export function ActionTypeFieldsTab({ kind, fields, onChange }: {
    kind: "input" | "output";
    fields: ActionTypeField[];
    onChange: (updated: ActionTypeField[]) => void;
}) {
    const [newName, setNewName] = useState("");

    const handleAdd = () => {
        const trimmed = newName.trim();
        if (!trimmed || fields.some((f) => f.name === trimmed)) return;
        onChange([...fields, { name: trimmed, type: "string", required: false }]);
        setNewName("");
    };

    const handleRemove = (index: number) => {
        onChange(fields.filter((_, i) => i !== index));
    };

    const handleUpdate = (index: number, patch: Partial<ActionTypeField>) => {
        onChange(fields.map((f, i) => (i === index ? { ...f, ...patch } : f)));
    };

    return (
        <div style={{ maxWidth: "800px" }}>
            <p className="axiom-text-subtle" style={{ marginBottom: "16px" }}>
                {kind === "input"
                    ? "Typed inputs this action expects when used as a workflow action node."
                    : "Typed outputs this action produces when used as a workflow action node."}
            </p>

            <Flex alignItems={{ default: "alignItemsFlexEnd" }} style={{ marginBottom: "16px", gap: "8px" }}>
                <FlexItem style={{ flex: 1 }}>
                    <FormGroup label="Name" fieldId={`${kind}-new-name`}>
                        <TextInput id={`${kind}-new-name`} value={newName}
                            onChange={(_e, v) => setNewName(v)}
                            placeholder="e.g. repository"
                            onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); handleAdd(); } }}
                        />
                    </FormGroup>
                </FlexItem>
                <FlexItem>
                    <Button variant="secondary" icon={<PlusCircleIcon />} onClick={handleAdd}
                        isDisabled={!newName.trim() || fields.some((f) => f.name === newName.trim())}
                        style={{ marginBottom: "1px" }}>
                        Add
                    </Button>
                </FlexItem>
            </Flex>

            {fields.length === 0 ? (
                <EmptyState>
                    <EmptyStateBody>No {kind}s declared.</EmptyStateBody>
                </EmptyState>
            ) : (
                <div style={{ display: "flex", flexDirection: "column", gap: "4px" }}>
                    {fields.map((f, i) => (
                        <Flex key={i} alignItems={{ default: "alignItemsCenter" }}
                            style={{
                                padding: "8px 12px",
                                backgroundColor: "var(--pf-t--global--background--color--secondary--default)",
                                borderRadius: "4px",
                                gap: "8px",
                            }}>
                            <FlexItem style={{ minWidth: "160px" }}>
                                <code style={{ fontSize: "13px", fontWeight: 600 }}>{f.name}</code>
                            </FlexItem>
                            <FlexItem style={{ width: "130px" }}>
                                <FormSelect value={f.type} aria-label={`Type for ${f.name}`}
                                    onChange={(_e, v) => handleUpdate(i, { type: v as ActionTypeField["type"] })}>
                                    {FIELD_TYPES.map((t) => (
                                        <FormSelectOption key={t} value={t} label={t} />
                                    ))}
                                </FormSelect>
                            </FlexItem>
                            <FlexItem>
                                <Checkbox id={`${kind}-required-${i}`} label="Required"
                                    isChecked={!!f.required}
                                    onChange={(_e, v) => handleUpdate(i, { required: v })} />
                            </FlexItem>
                            <FlexItem grow={{ default: "grow" }}>
                                <TextInput value={f.description || ""}
                                    onChange={(_e, v) => handleUpdate(i, { description: v })}
                                    placeholder="Description (optional)"
                                    aria-label={`Description for ${f.name}`}
                                    style={{ fontSize: "13px" }} />
                            </FlexItem>
                            <FlexItem>
                                <Button variant="plain" size="sm" onClick={() => handleRemove(i)}
                                    aria-label={`Remove ${f.name}`}>
                                    <TimesIcon />
                                </Button>
                            </FlexItem>
                        </Flex>
                    ))}
                </div>
            )}
        </div>
    );
}
