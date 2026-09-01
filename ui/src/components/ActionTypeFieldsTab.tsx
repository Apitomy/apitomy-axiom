import { useState } from "react";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Checkbox,
    Flex,
    FlexItem,
    FormGroup,
    FormHelperText,
    FormSelect,
    FormSelectOption,
    HelperText,
    HelperTextItem,
    TextInput,
} from "@patternfly/react-core";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import type { ActionTypeField } from "../config/api";

const FIELD_TYPES: ActionTypeField["type"][] = ["string", "number", "boolean", "object"];

// Mirrors the server-side identifier rule (ActionTypeValidator.VALID_FIELD_NAME_PATTERN).
const VALID_FIELD_NAME_PATTERN = /^[a-zA-Z_][a-zA-Z0-9_]*$/;

/**
 * Client-side validation of a field name mirroring the server rules: non-blank,
 * a valid identifier, and unique within the list. Returns an error message, or
 * null when the name is valid. `selfIndex` excludes a field from the uniqueness
 * check so it can keep its own name while being edited.
 */
function validateFieldName(name: string, fields: ActionTypeField[], selfIndex: number): string | null {
    const trimmed = name.trim();
    if (!trimmed) {
        return "Field name is required.";
    }
    if (!VALID_FIELD_NAME_PATTERN.test(trimmed)) {
        return "Use letters, digits, and underscores; must not start with a digit.";
    }
    if (fields.some((f, i) => i !== selfIndex && f.name === trimmed)) {
        return "Field name must be unique.";
    }
    return null;
}

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

    // Only surface the "required" error once the user has started typing.
    const newNameError = newName.trim() ? validateFieldName(newName, fields, -1) : null;

    const handleAdd = () => {
        if (validateFieldName(newName, fields, -1)) return;
        onChange([...fields, { name: newName.trim(), type: "string", required: false }]);
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
                            validated={newNameError ? "error" : "default"}
                            onChange={(_e, v) => setNewName(v)}
                            placeholder="e.g. repository"
                            onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); handleAdd(); } }}
                        />
                        {newNameError && (
                            <FormHelperText>
                                <HelperText>
                                    <HelperTextItem variant="error">{newNameError}</HelperTextItem>
                                </HelperText>
                            </FormHelperText>
                        )}
                    </FormGroup>
                </FlexItem>
                <FlexItem>
                    <Button variant="secondary" icon={<PlusCircleIcon />} onClick={handleAdd}
                        isDisabled={!!validateFieldName(newName, fields, -1)}
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
                    {fields.map((f, i) => {
                        const nameError = validateFieldName(f.name, fields, i);
                        return (
                        <Flex key={i} alignItems={{ default: "alignItemsFlexStart" }}
                            style={{
                                padding: "8px 12px",
                                backgroundColor: "var(--pf-t--global--background--color--secondary--default)",
                                borderRadius: "4px",
                                gap: "8px",
                            }}>
                            <FlexItem style={{ minWidth: "160px" }}>
                                <TextInput value={f.name}
                                    validated={nameError ? "error" : "default"}
                                    onChange={(_e, v) => handleUpdate(i, { name: v })}
                                    aria-label={`Name for ${f.name}`}
                                    style={{ fontSize: "13px", fontWeight: 600 }} />
                                {nameError && (
                                    <FormHelperText>
                                        <HelperText>
                                            <HelperTextItem variant="error">{nameError}</HelperTextItem>
                                        </HelperText>
                                    </FormHelperText>
                                )}
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
                        );
                    })}
                </div>
            )}
        </div>
    );
}
